package mx.ipn.escom.tesoreria.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Append-only, thread-safe history of committed transfers.
 *
 * <p>Holds the ordered sequence of {@link Transfer} commits so the replica
 * feed can replay catch-up history to a freshly connected replica. Backed by a
 * copy-on-write list because reads (catch-up scans) coexist with rare appends
 * from the commit path.
 */
public final class TransferLog {

    /** Ordered commit history; index roughly tracks sequence order. */
    private final List<Transfer> entries = new CopyOnWriteArrayList<>();

    /**
     * Appends a committed transfer to the history.
     *
     * @param t the transfer to record
     */
    public void append(Transfer t) {
        // TODO: store the transfer; the commit path guarantees ascending seq.
        entries.add(t);
    }

    /**
     * Returns every recorded transfer whose sequence is strictly greater than
     * the given watermark, in ascending order (used for replica catch-up).
     *
     * @param seq the exclusive lower bound on sequence number
     * @return the matching transfers
     */
    public List<Transfer> since(long seq) {
        // TODO: filter entries with t.seq() > seq, preserving order.
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * @return the number of recorded transfers
     */
    public long size() {
        return entries.size();
    }
}
