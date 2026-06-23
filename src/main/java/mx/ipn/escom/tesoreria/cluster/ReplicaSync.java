package mx.ipn.escom.tesoreria.cluster;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import mx.ipn.escom.tesoreria.core.Bank;
import mx.ipn.escom.tesoreria.core.Transfer;

/**
 * Replica-side endpoint of the replication link for "Tesoreria Distribuida"
 * (Equipo 18).
 *
 * <p>This synchronizer keeps a follower node aligned with the leader. It dials
 * the leader's replication port and opens the conversation with a one-line
 * greeting {@code "CATCHUP <seq>"}, where the sequence is the watermark the
 * local {@link Bank} has already applied. The leader then streams, as JSON lines,
 * first the backlog past that watermark and afterwards every live commit. Each
 * incoming line is turned back into a {@link Transfer} by {@link WireCodec} and
 * handed to {@link Bank#applyReplicated(Transfer)}.
 *
 * <p>The link is treated as unreliable. The reader runs inside a loop that, on
 * any drop, waits briefly and dials again, re-issuing CATCHUP with the bank's
 * latest sequence so the replay resumes from the new watermark rather than from
 * scratch.
 */
public final class ReplicaSync {

    /** Delay between reconnection attempts, in milliseconds. */
    private static final long RECONNECT_DELAY_MS = 1000L;

    /** Leader host to connect to (TES_LEADER_HOST). */
    private final String leaderHost;

    /** Leader replication port to connect to (TES_REPL_PORT). */
    private final int port;

    /** Local bank that receives and orders the replicated transfers. */
    private final Bank bank;

    /** Current connection to the leader feed; null while disconnected. */
    private volatile Socket socket;

    /** Background thread driving the connect-read-reconnect loop. */
    private volatile Thread readerThread;

    /** Lifecycle flag toggled by {@link #start()} and {@link #stop()}. */
    private volatile boolean running;

    /**
     * Builds a synchronizer pointed at a leader feed.
     *
     * @param leaderHost the leader host (TES_LEADER_HOST)
     * @param port       the leader replication port (TES_REPL_PORT)
     * @param bank       the local bank that applies replicated transfers
     */
    public ReplicaSync(String leaderHost, int port, Bank bank) {
        this.leaderHost = leaderHost;
        this.port = port;
        this.bank = bank;
    }

    /**
     * Starts the background loop that keeps the replica connected to the leader,
     * catching up after every reconnect.
     */
    public void start() {
        running = true;
        readerThread = new Thread(this::syncLoop, "replica-sync");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     * Repeatedly connects to the leader and consumes its stream, dialing again
     * after any failure for as long as the synchronizer is running.
     */
    private void syncLoop() {
        while (running) {
            try {
                consumeStream();
            } catch (IOException ex) {
                // Connection lost or refused; fall through to the backoff below.
            }
            if (running) {
                sleepBeforeRetry();
            }
        }
    }

    /**
     * Opens one connection, sends the CATCHUP greeting and applies every line
     * received until the stream ends or the synchronizer stops.
     *
     * @throws IOException if the connection or a read fails
     */
    private void consumeStream() throws IOException {
        Socket connection = new Socket(leaderHost, port);
        socket = connection;
        try {
            OutputStream out = connection.getOutputStream();
            out.write(("CATCHUP " + bank.sequence() + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while (running && (line = reader.readLine()) != null) {
                try {
                    Transfer t = WireCodec.decode(line);
                    bank.applyReplicated(t);
                } catch (RuntimeException ex) {
                    // A corrupted line must not kill the reader: break so the
                    // outer loop reconnects and the leader resends from the
                    // current watermark.
                    break;
                }
            }
        } finally {
            closeQuietly(connection);
            socket = null;
        }
    }

    /** Waits the reconnection delay, exiting early if interrupted. */
    private void sleepBeforeRetry() {
        try {
            Thread.sleep(RECONNECT_DELAY_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    /**
     * Stops the reader loop and closes the connection so the reader unblocks.
     */
    public void stop() {
        running = false;
        closeQuietly(socket);
    }

    /** Closes a socket if present, ignoring any error. */
    private void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // Shutting down or already broken; nothing to recover.
            }
        }
    }
}
