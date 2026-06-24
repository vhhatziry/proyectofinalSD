package mx.ipn.escom.tesoreria.cluster;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import mx.ipn.escom.tesoreria.core.CommitListener;
import mx.ipn.escom.tesoreria.core.Transfer;
import mx.ipn.escom.tesoreria.core.TransferLog;

/**
 * Leader-side endpoint of the replication link for "Tesoreria Distribuida"
 * (Equipo 18).
 *
 * <p>The feed publishes the leader's commit stream over plain TCP. It listens on
 * the replication port; when a replica dials in it announces how far it has
 * already caught up with a one-line greeting {@code "CATCHUP <seq>"}. The feed
 * answers by replaying, in order, every transfer in the {@link TransferLog} whose
 * sequence is past that watermark, each rendered as a JSON line by
 * {@link WireCodec}, and then keeps streaming fresh commits live.
 *
 * <p><b>The socket I/O is decoupled from the commit path.</b> The feed is a
 * {@link CommitListener} invoked inline on the leader's commit threads, so it must
 * never block there. Each subscriber owns a bounded queue and a dedicated writer
 * thread; {@link #onCommit} only does a non-blocking {@code offer} into each
 * queue. A replica whose socket stalls (e.g. a powered-off VM whose TCP send
 * buffer fills and whose writes would block forever) only backs up its OWN queue;
 * when that queue fills, the subscriber is dropped and its socket closed, which
 * unblocks its writer and makes the replica reconnect. The commit path and the
 * other replicas are never affected. This is what keeps the leader serving when a
 * replica is stopped, as the contract's fault-tolerance scenarios require.
 *
 * <p>Ordering is upheld without a global broadcast lock: the leader appends a
 * transfer to the log before notifying listeners, and a subscriber is added to
 * the live audience before its writer reads the backlog, so every commit reaches
 * a replica via the backlog, the live queue, or (harmlessly) both. Duplicates and
 * minor reordering across that boundary are absorbed by the replica, which drops
 * any sequence at or below its watermark and reorders the rest.
 */
public final class ReplicaFeed implements CommitListener {

    /** Outbound lines buffered per subscriber before it is judged too slow and dropped. */
    private static final int QUEUE_CAPACITY = 8192;

    /** How long a writer waits for a queued line before re-checking its liveness. */
    private static final long POLL_MS = 200L;

    /** TCP port replicas connect to (TES_REPL_PORT). */
    private final int port;

    /** History of committed transfers used to satisfy a CATCHUP request. */
    private final TransferLog log;

    /** Live audience: subscribers currently receiving fresh commits. */
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    /** Listening server socket; null until {@link #start()} runs. */
    private volatile ServerSocket serverSocket;

    /** Background thread accepting replica connections. */
    private volatile Thread acceptThread;

    /** Lifecycle flag toggled by {@link #start()} and {@link #stop()}. */
    private volatile boolean running;

    /**
     * Builds a feed bound to a replication port and backed by a transfer log.
     *
     * @param port the TCP port to listen on (TES_REPL_PORT)
     * @param log  the leader transfer log consulted for catch-up replay
     */
    public ReplicaFeed(int port, TransferLog log) {
        this.port = port;
        this.log = log;
    }

    /**
     * Opens the listening socket and starts accepting replica connections in a
     * background thread. Each accepted connection is handed to
     * {@link #serveReplica(Socket)}.
     *
     * @throws IOException if the listening socket cannot be opened
     */
    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        acceptThread = new Thread(this::acceptLoop, "replica-feed-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /**
     * Accepts replica connections until the feed is stopped, serving each one on
     * its own thread so a slow replica cannot block new arrivals.
     */
    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                Thread worker = new Thread(() -> serveReplica(socket), "replica-feed-accept-worker");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException ex) {
                // A close during shutdown surfaces here; exit quietly in that case.
                if (running) {
                    continue;
                }
                return;
            }
        }
    }

    /**
     * Reads the {@code "CATCHUP <seq>"} greeting, registers the connection in the
     * live audience and starts its writer thread. The writer replays the backlog
     * past the watermark and then streams live commits; registering before the
     * writer reads the log guarantees no commit slips through the gap between the
     * two (it is delivered by the backlog, the live queue, or both).
     *
     * @param socket the accepted replica connection
     */
    private void serveReplica(Socket socket) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            long since = parseCatchup(reader.readLine());
            Subscriber subscriber = new Subscriber(socket, since);
            subscribers.add(subscriber);
            subscriber.start();
        } catch (IOException ex) {
            closeQuietly(socket);
        }
    }

    /**
     * Extracts the sequence watermark from a {@code "CATCHUP <seq>"} greeting.
     *
     * @param greeting the handshake line read from the replica
     * @return the watermark, or 0 if the line is missing or malformed
     */
    private long parseCatchup(String greeting) {
        if (greeting == null) {
            return 0L;
        }
        String[] parts = greeting.trim().split("\\s+");
        if (parts.length == 2 && "CATCHUP".equals(parts[0])) {
            try {
                return Long.parseLong(parts[1]);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    /**
     * Receives a freshly committed transfer and offers it to every subscriber's
     * outbound queue. The offer never blocks: a subscriber whose queue is full has
     * fallen too far behind (a stalled or dead socket) and is dropped, so the
     * commit path keeps moving no matter how a replica misbehaves.
     *
     * @param t the committed transfer to fan out
     */
    @Override
    public void onCommit(Transfer t) {
        String line = WireCodec.encode(t);
        for (Subscriber subscriber : subscribers) {
            if (!subscriber.enqueue(line)) {
                drop(subscriber);
            }
        }
    }

    /** Removes a subscriber and closes its socket, which unblocks and ends its writer. */
    private void drop(Subscriber subscriber) {
        subscribers.remove(subscriber);
        subscriber.close();
    }

    /**
     * Stops accepting connections, closes the listening socket and disconnects
     * every current subscriber.
     */
    public void stop() {
        running = false;
        closeServer();
        for (Subscriber subscriber : subscribers) {
            subscriber.close();
        }
        subscribers.clear();
    }

    /** Closes the listening socket, ignoring any error. */
    private void closeServer() {
        ServerSocket s = serverSocket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // Nothing useful to do while shutting down.
            }
        }
    }

    /** Closes a replica socket, ignoring any error. */
    private void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already failing; the drop is the intended outcome.
        }
    }

    /**
     * One connected replica: its socket, a bounded outbound queue, and a writer
     * thread that drains the queue to the socket. Decoupling the write onto this
     * thread is what keeps a stalled replica from blocking the leader's commit
     * path; the queue bounds how far a slow replica may lag before it is dropped.
     */
    private final class Subscriber {

        /** Connection to the replica. */
        private final Socket socket;

        /** Replica watermark from its CATCHUP greeting; the backlog starts past it. */
        private final long since;

        /** Outbound lines awaiting the writer; bounded so a stalled socket is detected. */
        private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

        /** False once the subscriber is dropped (queue overflow, write failure or stop). */
        private volatile boolean alive = true;

        /** Thread that replays the backlog and then streams live commits. */
        private final Thread writer = new Thread(this::run, "replica-feed-writer");

        Subscriber(Socket socket, long since) {
            this.socket = socket;
            this.since = since;
            this.writer.setDaemon(true);
        }

        /** Starts the writer thread. */
        void start() {
            writer.start();
        }

        /**
         * Offers one line from the commit path. Returns false (and marks the
         * subscriber dead) when the queue is full, i.e. the replica cannot keep up
         * and must be dropped rather than allowed to back-pressure the leader.
         */
        boolean enqueue(String line) {
            if (!alive) {
                return false;
            }
            if (!queue.offer(line)) {
                alive = false;
                return false;
            }
            return true;
        }

        /** Marks the subscriber dead and closes the socket, unblocking the writer. */
        void close() {
            alive = false;
            writer.interrupt();
            closeQuietly(socket);
        }

        /** Replays the backlog past {@link #since}, then streams the live queue. */
        private void run() {
            try {
                BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
                // Backlog: one buffered flush so a long catch-up is not a flush per
                // line. Overlap with the live queue is harmless (the replica drops
                // any sequence at or below its watermark).
                for (Transfer t : log.since(since)) {
                    out.write((WireCodec.encode(t) + "\n").getBytes(StandardCharsets.UTF_8));
                }
                out.flush();
                // Live: flush per line so fresh commits reach the replica promptly.
                while (alive || !queue.isEmpty()) {
                    String line = queue.poll(POLL_MS, TimeUnit.MILLISECONDS);
                    if (line != null) {
                        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                }
            } catch (IOException | InterruptedException ex) {
                // Broken pipe or interrupted on drop: this subscriber is finished.
            } finally {
                alive = false;
                subscribers.remove(this);
                closeQuietly(socket);
            }
        }
    }
}
