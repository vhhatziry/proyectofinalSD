package mx.ipn.escom.tesoreria.durable;

import mx.ipn.escom.tesoreria.core.CommitListener;
import mx.ipn.escom.tesoreria.core.Transfer;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Leader-only durability orchestrator backed by {@link GcsStore}.
 *
 * On a cold start {@link #recover(CommitListener)} replays every persisted transfer
 * into the ledger, and {@link #start(int)} seeds the stored counter. Once
 * registered as a {@link CommitListener} on the leader's ledger, each newly
 * committed transfer flows through {@link #onCommit(Transfer)} which uploads
 * it to GCS. {@link #stored()} exposes the running count for NodeStats.
 * Replicas never own a Journal.
 */
public final class Journal implements CommitListener {

    /** Object store where each transfer is persisted as a journal object. */
    private final GcsStore store;

    /** Number of transfers known to be durably stored in GCS. */
    private final AtomicLong stored = new AtomicLong();

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
        // TODO: List<Transfer> all = store.readAll();
        // for (Transfer t : all) apply.onCommit(t); return all.size();
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * Seeds the stored counter after recovery, before live commits begin.
     *
     * @param recovered the count returned by {@link #recover(CommitListener)}
     */
    public void start(int recovered) {
        // TODO: stored.set(recovered);
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * Persists a freshly committed transfer to GCS and bumps the counter.
     *
     * @param transfer the committed transfer to durably record
     * @throws IOException          if the upload fails
     * @throws InterruptedException if the HTTP exchange is interrupted
     */
    public void record(Transfer transfer) throws IOException, InterruptedException {
        // TODO: store.put(transfer); stored.incrementAndGet();
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * {@link CommitListener} hook: delegates to {@link #record(Transfer)},
     * swallowing checked exceptions so a journaling failure does not abort the
     * commit notification path.
     *
     * @param transfer the committed transfer reported by the ledger
     */
    @Override
    public void onCommit(Transfer transfer) {
        // TODO: try { record(transfer); } catch (IOException | InterruptedException e) {
        // restore interrupt if needed and log; do not rethrow. }
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * Reports how many transfers are durably stored.
     *
     * @return the current stored count
     */
    public long stored() {
        return stored.get();
    }
}
