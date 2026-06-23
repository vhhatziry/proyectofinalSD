package mx.ipn.escom.tesoreria.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;

/**
 * The single reactor thread. It owns one Selector and does ONLY accept, read,
 * and write. It never computes a Reply inline: the moment a connection's
 * parser reports a complete request, the IoLoop submits a job to the worker
 * pool, which builds the Reply and enqueues the serialized bytes back onto the
 * Channel. The IoLoop then performs the actual flush on the next write event.
 * This is the Reactor + worker-pool design (Doug Lea pattern 3).
 *
 * <p>Interest-set mutations happen only on this thread. A worker that has
 * finished a reply hands the key back through {@code writeReady} and wakes the
 * selector; the loop then enables OP_WRITE for it. This keeps the classic NIO
 * cross-thread interestOps race out of the design.
 */
public final class IoLoop implements Runnable {

    private final Selector selector;
    private final ServerSocketChannel serverChannel;
    private final Routes routes;
    private final ExecutorService workers;
    private final int readBufferSize;

    // Keys whose response is ready; the loop enables OP_WRITE for each.
    private final ConcurrentLinkedQueue<SelectionKey> writeReady = new ConcurrentLinkedQueue<>();

    private volatile boolean running;
    private Thread thread;

    /**
     * Wires the loop to its selector, the listening server channel, the router
     * used by workers, and the worker pool requests are dispatched to.
     */
    public IoLoop(Selector selector,
                  ServerSocketChannel serverChannel,
                  Routes routes,
                  ExecutorService workers,
                  int readBufferSize) {
        this.selector = selector;
        this.serverChannel = serverChannel;
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

    /** Signals the loop to stop and wakes the selector. */
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
                enableQueuedWrites();
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid()) {
                        continue;
                    }
                    try {
                        if (key.isAcceptable()) {
                            onAccept(key);
                        } else if (key.isWritable()) {
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

    /** Turns on OP_WRITE for every key a worker has finished a reply for. */
    private void enableQueuedWrites() {
        SelectionKey key;
        while ((key = writeReady.poll()) != null) {
            if (key.isValid()) {
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
            }
        }
    }

    /** Accepts a new connection and registers it for reads with a Channel. */
    private void onAccept(SelectionKey key) throws IOException {
        SocketChannel socket = serverChannel.accept();
        if (socket == null) {
            return;
        }
        socket.configureBlocking(false);
        Channel channel = new Channel(socket, readBufferSize);
        socket.register(selector, SelectionKey.OP_READ, channel);
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
