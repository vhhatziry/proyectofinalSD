package mx.ipn.escom.tesoreria.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;

/**
 * One reactor. It owns a single Selector and does ONLY read and write for the
 * connections assigned to it; accepting is done by the {@link Server}'s acceptor
 * thread, which round-robins new connections across several IoLoops so the socket
 * I/O is spread over the cores instead of funnelling through one thread (a single
 * reactor saturates one core and caps throughput while the others sit idle).
 *
 * <p>It never computes a Reply inline: the moment a connection's parser reports a
 * complete request, the loop submits a job to the shared worker pool, which builds
 * the Reply and enqueues the serialized bytes back onto the Channel. The loop then
 * performs the actual flush on the next write event. This is the Reactor +
 * worker-pool design (Doug Lea pattern 3).
 *
 * <p>Interest-set mutations and channel registration happen only on this loop's
 * own thread. A new connection arrives via {@link #register(SocketChannel)} (from
 * the acceptor thread) and is queued; a worker that has finished a reply hands the
 * key back through {@code writeReady}. Both wake the selector, which drains the
 * queues at the top of its next pass, keeping the classic cross-thread
 * interestOps/register race out of the design.
 */
public final class IoLoop implements Runnable {

    private final Selector selector;
    private final Routes routes;
    private final ExecutorService workers;
    private final int readBufferSize;

    // Keys whose response is ready; the loop enables OP_WRITE for each.
    private final ConcurrentLinkedQueue<SelectionKey> writeReady = new ConcurrentLinkedQueue<>();

    // Sockets handed over by the acceptor; the loop registers each on its selector.
    private final ConcurrentLinkedQueue<SocketChannel> pending = new ConcurrentLinkedQueue<>();

    private volatile boolean running;
    private Thread thread;

    /**
     * Wires the loop to its own selector, the router used by workers, and the
     * shared worker pool requests are dispatched to.
     */
    public IoLoop(Selector selector, Routes routes, ExecutorService workers, int readBufferSize) {
        this.selector = selector;
        this.routes = routes;
        this.workers = workers;
        this.readBufferSize = readBufferSize;
    }

    /** Starts the loop on its own daemon thread. */
    public void start() {
        running = true;
        thread = new Thread(this, "tesoreria-ioloop");
        thread.setDaemon(true);
        thread.start();
    }

    /** Signals the loop to stop, wakes the selector and closes it after the join. */
    public void stop() {
        running = false;
        selector.wakeup();
        if (thread != null) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            selector.close();
        } catch (IOException ignored) {
            // Teardown is best effort.
        }
    }

    /**
     * Assigns a freshly accepted connection to this loop. Called from the acceptor
     * thread: the socket is only queued here and registered on the selector by the
     * loop's own thread, so no cross-thread Selector mutation occurs.
     */
    public void register(SocketChannel socket) {
        pending.add(socket);
        selector.wakeup();
    }

    /** The select loop: blocks on the selector and services ready keys. */
    @Override
    public void run() {
        try {
            while (running) {
                selector.select();
                if (!running) {
                    break;
                }
                registerPending();
                enableQueuedWrites();
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid()) {
                        continue;
                    }
                    try {
                        if (key.isWritable()) {
                            onWrite(key);
                        } else if (key.isReadable()) {
                            onRead(key);
                        }
                    } catch (IOException e) {
                        closeQuietly(key);
                    }
                }
            }
        } catch (IOException e) {
            // A selector failure ends the loop; the server is shutting down.
        }
    }

    /** Registers every queued new connection on this loop's selector for reads. */
    private void registerPending() {
        SocketChannel socket;
        while ((socket = pending.poll()) != null) {
            try {
                Channel channel = new Channel(socket, readBufferSize);
                socket.register(selector, SelectionKey.OP_READ, channel);
            } catch (IOException e) {
                closeQuietly(socket);
            }
        }
    }

    /** Turns on OP_WRITE for every key a worker has finished a reply for. */
    private void enableQueuedWrites() {
        SelectionKey key;
        while ((key = writeReady.poll()) != null) {
            if (key.isValid()) {
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
            }
        }
    }

    /**
     * Reads available bytes into the Channel buffer and feeds the parser. When
     * the parser completes a request, reads are paused for this connection and
     * the request is dispatched to the worker pool; the worker builds the Reply,
     * enqueues the bytes and hands the key back for an OP_WRITE flush.
     */
    private void onRead(SelectionKey key) throws IOException {
        Channel channel = (Channel) key.attachment();
        ByteBuffer buffer = channel.readBuffer();
        int n = channel.socket().read(buffer);
        if (n == -1) {
            closeQuietly(key);
            return;
        }
        if (n == 0) {
            return;
        }
        buffer.flip();
        boolean complete = channel.parser().feed(buffer);
        buffer.compact();
        if (complete) {
            Request request = channel.parser().take();
            channel.setProcessing(true);
            // Pause reads until this reply is flushed: one request in flight per
            // connection, which keeps responses ordered without pipelining.
            key.interestOps(0);
            workers.submit(() -> handle(key, channel, request));
        }
    }

    /** Worker job: route, serve, serialize, enqueue, and re-arm the key. */
    private void handle(SelectionKey key, Channel channel, Request request) {
        Reply reply;
        try {
            Routes.Match match = routes.match(request.method(), request.path());
            if (match.status != 200) {
                reply = Reply.status(match.status);
            } else {
                request.bindPathParams(match.params);
                Reply served = match.endpoint.serve(request);
                reply = (served != null) ? served : Reply.status(500);
            }
        } catch (RuntimeException e) {
            reply = Reply.status(500);
        }
        boolean close = request.wantsClose() || reply.isClose();
        channel.enqueue(serialize(reply, !close));
        if (close) {
            channel.markClose();
        }
        channel.setProcessing(false);
        writeReady.add(key);
        selector.wakeup();
    }

    /** Flushes queued outbound buffers; closes if a close was requested. */
    private void onWrite(SelectionKey key) throws IOException {
        Channel channel = (Channel) key.attachment();
        SocketChannel socket = channel.socket();
        ByteBuffer buffer;
        while ((buffer = channel.peekOutbound()) != null) {
            socket.write(buffer);
            if (buffer.hasRemaining()) {
                return; // socket send buffer full; stay interested in OP_WRITE
            }
            channel.popOutbound();
        }
        if (channel.isClosePending()) {
            closeQuietly(key);
            return;
        }
        key.interestOps(SelectionKey.OP_READ);
    }

    /** Cancels the key and closes its socket, swallowing errors. */
    private void closeQuietly(SelectionKey key) {
        key.cancel();
        try {
            key.channel().close();
        } catch (IOException ignored) {
            // Best effort on teardown.
        }
    }

    /** Closes a not-yet-registered socket, swallowing errors. */
    private void closeQuietly(SocketChannel socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    /** Serializes a Reply into a single HTTP/1.1 response buffer. */
    private static ByteBuffer serialize(Reply reply, boolean keepAlive) {
        byte[] body = reply.body();
        String head = "HTTP/1.1 " + reply.status() + " " + reason(reply.status()) + "\r\n"
                + "Content-Type: " + reply.contentType() + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n"
                + "\r\n";
        byte[] headBytes = head.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer out = ByteBuffer.allocate(headBytes.length + body.length);
        out.put(headBytes);
        out.put(body);
        out.flip();
        return out;
    }

    /** Reason phrase for the status codes this service emits. */
    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 201: return "Created";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 500: return "Internal Server Error";
            default: return "OK";
        }
    }
}
