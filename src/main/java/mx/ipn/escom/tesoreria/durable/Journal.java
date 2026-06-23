package mx.ipn.escom.tesoreria.durable;

import mx.ipn.escom.tesoreria.core.CommitListener;
import mx.ipn.escom.tesoreria.core.Transfer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Leader-only durability orchestrator backed by {@link GcsStore}.
 *
 * On a cold start {@link #recover(CommitListener)} replays every persisted
 * transfer into the ledger, and {@link #start(int)} seeds the stored counter and
 * launches the background writer. Once registered as a {@link CommitListener} on
 * the leader's bank, each newly committed transfer is <b>enqueued</b> by
 * {@link #onCommit(Transfer)} and uploaded to GCS off the request thread by a
 * single writer thread, so a transfer's HTTP response never blocks on a Cloud
 * Storage round trip. {@link #stored()} exposes how many are durably uploaded
 * (it trails the applied count by the queue depth, which is the true GCS figure
 * the dashboard should show). {@link #stop()} drains the queue on a graceful
 * shutdown so an orderly restart recovers the complete log. Replicas never own a
 * Journal.
 */
public final class Journal implements CommitListener {

    /** How long the writer waits for a queued transfer before re-checking state. */
    private static final long POLL_MS = 200L;

    /** Most transfers composed into a single Cloud Storage object. */
    private static final int MAX_BATCH = 1000;

    /** Object store where each transfer is persisted as a journal object. */
    private final GcsStore store;

    /** Number of transfers known to be durably stored in GCS. */
    private final AtomicLong stored = new AtomicLong();

    /** Hand-off from the commit path to the background writer. */
    private final BlockingQueue<Transfer> queue = new LinkedBlockingQueue<>();

    /** Background thread that uploads queued transfers to GCS. */
    private volatile Thread writer;

    /** Lifecycle flag; the writer drains the queue while this stays true. */
    private volatile boolean running;

    /**
     * Creates a journal over the given object store.
     *
     * @param store the GCS-backed transfer store
     */
    public Journal(GcsStore store) {
        this.store = store;
    }

    /**
     * Replays all persisted transfers into the ledger at cold start and
     * returns how many were recovered.
     *
     * @param apply callback that applies one recovered transfer to the ledger
     * @return the number of transfers replayed
     * @throws IOException          if the journal cannot be read
     * @throws InterruptedException if an HTTP exchange is interrupted
     */
    public int recover(CommitListener apply) throws IOException, InterruptedException {
        List<Transfer> all = store.readAll();
        for (Transfer t : all) {
            apply.onCommit(t);
        }
        return all.size();
    }

    /**
     * Seeds the stored counter after recovery and starts the background writer
     * that uploads live commits.
     *
     * @param recovered the count returned by {@link #recover(CommitListener)}
     */
    public void start(int recovered) {
        stored.set(recovered);
        running = true;
        writer = new Thread(this::drainLoop, "journal-writer");
        writer.setDaemon(true);
        writer.start();
    }

    /**
     * {@link CommitListener} hook: enqueues a freshly committed transfer for the
     * background writer to upload. The enqueue never blocks the commit path.
     *
     * @param transfer the committed transfer reported by the bank
     */
    @Override
    public void onCommit(Transfer transfer) {
        queue.offer(transfer);
    }

    /**
     * Background loop: takes the queued transfers, composes everything available
     * into one batch (up to {@link #MAX_BATCH}) and uploads it as a single Cloud
     * Storage object, advancing the stored counter by the batch size. Batching is
     * what lets the durable log keep up with a high commit rate. An upload failure
     * is logged but does not stop the writer; the loop runs until {@link #stop()}
     * is called and the queue is drained.
     */
    private void drainLoop() {
        List<Transfer> batch = new ArrayList<>();
        while (running || !queue.isEmpty()) {
            Transfer first;
            try {
                first = queue.poll(POLL_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (first == null) {
                continue;
            }
            batch.clear();
            batch.add(first);
            queue.drainTo(batch, MAX_BATCH - 1);
            try {
                store.putBatch(batch);
                stored.addAndGet(batch.size());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                System.err.println("journal: could not persist batch of " + batch.size()
                        + " ending at seq " + batch.get(batch.size() - 1).seq()
                        + ": " + e.getMessage());
            }
        }
    }

    /**
     * Stops accepting new work and drains the queue so every already-committed
     * transfer is uploaded before the process exits. Intended for a graceful
     * shutdown hook; a hard kill leaves the in-flight queue as the durability
     * window inherent to asynchronous journaling.
     */
    public void stop() {
        running = false;
        Thread w = writer;
        if (w != null) {
            try {
                w.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Reports how many transfers are durably stored in GCS.
     *
     * @return the current stored count
     */
    public long stored() {
        return stored.get();
    }
}
