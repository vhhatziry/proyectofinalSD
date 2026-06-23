package mx.ipn.escom.tesoreria.cluster;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
 * answers by replaying, in order, every transfer in the {@link TransferLog}
 * whose sequence is past that watermark, each rendered as a JSON line by
 * {@link WireCodec}. Once the backlog is drained the socket stays open and joins
 * the live audience.
 *
 * <p>Because the feed is a {@link CommitListener} attached to the leader, each
 * fresh commit is encoded once and fanned out to every connected replica. A
 * subscriber whose write fails is simply dropped from the audience; the leader
 * keeps serving the rest. The brief gap between finishing the replay and joining
 * the live audience is closed naturally on the replica's next reconnect, which
 * re-issues CATCHUP from its current watermark.
 */
public final class ReplicaFeed implements CommitListener {

    /** TCP port replicas connect to (TES_REPL_PORT). */
    private final int port;

    /** History of committed transfers used to satisfy a CATCHUP request. */
    private final TransferLog log;

    /** Live audience: replica sockets currently receiving fresh commits. */
    private final List<Socket> subscribers = new CopyOnWriteArrayList<>();

    /** Guards a single broadcast so concurrent commits never interleave bytes. */
    private final Object broadcastLock = new Object();

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
                Thread worker = new Thread(() -> serveReplica(socket), "replica-feed-worker");
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
     * Reads the {@code "CATCHUP <seq>"} greeting from a freshly connected
     * replica, replays every logged transfer past that watermark and then
     * registers the socket in the live audience.
     *
     * @param socket the accepted replica connection
     */
    private void serveReplica(Socket socket) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String greeting = reader.readLine();
            long since = parseCatchup(greeting);
            for (Transfer t : log.since(since)) {
                writeLine(socket, WireCodec.encode(t));
            }
            subscribers.add(socket);
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
     * Receives a freshly committed transfer from the leader and broadcasts it as
     * one JSON line to every connected replica.
     *
     * <p>The whole fan-out runs under a private lock so that, when several
     * commit threads notify at once, each transfer is written to a subscriber in
     * one piece instead of having its bytes interleaved with another transfer on
     * the same socket. The lock orders only the byte writes; it does not impose
     * any sequence ordering on the commits themselves.
     *
     * @param t the committed transfer to fan out
     */
    @Override
    public void onCommit(Transfer t) {
        String line = WireCodec.encode(t);
        synchronized (broadcastLock) {
            for (Socket socket : subscribers) {
                try {
                    writeLine(socket, line);
                } catch (IOException ex) {
                    subscribers.remove(socket);
                    closeQuietly(socket);
                }
            }
        }
    }

    /**
     * Writes one wire line followed by a newline and flushes it.
     *
     * @param socket the destination socket
     * @param line   the JSON line to send (without newline)
     * @throws IOException if the write fails
     */
    private void writeLine(Socket socket, String line) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Stops accepting connections, closes the listening socket and disconnects
     * every current subscriber.
     */
    public void stop() {
        running = false;
        closeServer();
        for (Socket socket : subscribers) {
            closeQuietly(socket);
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
}
