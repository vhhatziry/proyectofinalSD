package mx.ipn.escom.tesoreria.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Append-only, thread-safe history of committed transfers.
 *
 * <p>Holds the ordered sequence of {@link Transfer} commits so the replica feed
 * can replay catch-up history to a freshly connected replica. Appends happen on
 * the commit path at the full transfer rate (20% of contest traffic), so the
 * backing list is a plain {@link ArrayList} guarded by this object's monitor:
 * appending is amortised O(1). A copy-on-write list was wrong here because it
 * copies the whole backing array on every append, turning a sustained transfer
 * load into O(n^2) array copying and heavy GC. Catch-up scans ({@link #since})
 * are rare (only when a replica (re)connects); each takes the monitor, copies the
 * matching tail into a fresh list and returns it, so the caller iterates a
 * snapshot without holding the lock.
 */
public final class TransferLog {

    /** Ordered commit history, guarded by this instance's monitor. */
    private final List<Transfer> entries = new ArrayList<>();

    /**
     * Appends a committed transfer to the history.
     *
     * @param t the transfer to record
     */
    public synchronized void append(Transfer t) {
        entries.add(t);
    }

    /**
     * Returns every recorded transfer whose sequence is strictly greater than
     * the given watermark, in ascending order (used for replica catch-up). The
     * result is a fresh snapshot, safe to iterate after the lock is released.
     *
     * @param seq the exclusive lower bound on sequence number
     * @return the matching transfers
     */
    public synchronized List<Transfer> since(long seq) {
        List<Transfer> result = new ArrayList<>();
        for (Transfer t : entries) {
            if (t.seq() > seq) {
                result.add(t);
            }
        }
        return result;
    }

    /**
     * @return the number of recorded transfers
     */
    public synchronized long size() {
        return entries.size();
    }
}
