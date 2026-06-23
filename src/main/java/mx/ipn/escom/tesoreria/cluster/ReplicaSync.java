package mx.ipn.escom.tesoreria.cluster;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;

import mx.ipn.escom.tesoreria.core.Bank;
import mx.ipn.escom.tesoreria.core.Transfer;

/**
 * Replica-side endpoint of the replication link for "Tesoreria Distribuida"
 * (Equipo 18).
 *
 * <p>This synchronizer keeps a follower node aligned with the leader. It dials
 * the leader's replication port and opens the conversation with a one-line
 * greeting {@code "CATCHUP <seq>"}, where the sequence is {@link Bank#lastSeq()},
 * the highest transfer the local {@link Bank} has already applied. The leader then
 * streams, as JSON lines, first the backlog past that watermark and afterwards
 * every live commit. Each incoming line is turned back into a {@link Transfer} by
 * {@link WireCodec}.
 *
 * <p>Incoming transfers are applied through a small reorder buffer so the bank
 * always sees them in strict, gap-free sequence order. The leader fans commits
 * out from concurrent threads, so a higher sequence can arrive before a lower
 * one; the buffer holds out-of-order arrivals until the missing sequences fill
 * in, then drains them contiguously into {@link Bank#applyReplicated(Transfer)}.
 * A transfer at or below the bank's watermark is a duplicate and is dropped, so
 * an overlap resent after a reconnect is harmless.
 *
 * <p>The link is treated as unreliable. The reader runs inside a loop that, on
 * any drop, waits briefly and dials again, re-issuing CATCHUP with the bank's
 * latest watermark so the replay resumes from there rather than from scratch.
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
        // Fresh buffer per connection: a reconnect re-fetches from the watermark,
        // so any stale out-of-order remainder from the previous link is discarded.
        TreeMap<Long, Transfer> pending = new TreeMap<>();
        try {
            OutputStream out = connection.getOutputStream();
            out.write(("CATCHUP " + bank.lastSeq() + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while (running && (line = reader.readLine()) != null) {
                try {
                    bufferAndDrain(pending, WireCodec.decode(line));
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

    /**
     * Buffers one received transfer and applies every transfer that is now
     * contiguous with the bank's watermark, in strict sequence order.
     *
     * @param pending out-of-order arrivals waiting for their predecessors
     * @param t       the freshly received transfer
     */
    private void bufferAndDrain(TreeMap<Long, Transfer> pending, Transfer t) {
        if (t.seq() > bank.lastSeq()) {
            pending.put(t.seq(), t);
        }
        while (!pending.isEmpty() && pending.firstKey() <= bank.lastSeq() + 1) {
            Transfer next = pending.pollFirstEntry().getValue();
            if (next.seq() > bank.lastSeq()) {
                bank.applyReplicated(next);
            }
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
