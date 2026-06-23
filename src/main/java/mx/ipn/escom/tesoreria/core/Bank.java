package mx.ipn.escom.tesoreria.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Turns the bare money mechanics of a {@link Ledger} into committed bank
 * transactions. The bank owns everything the ledger intentionally does not: the
 * monotonic sequence that numbers each accepted transfer, the in-memory commit
 * log that lets replicas catch up, and the listeners that observe commits as
 * they happen (the journal and the live replica feed on the leader).
 *
 * <p>There are two ways a transfer enters the bank. {@link #transfer} is the
 * client-facing path on the leader: it screens out the operations that are
 * never valid as policy, asks the ledger to perform the movement, and only on
 * success stamps a fresh sequence number, records the entry, and fans it out to
 * listeners. {@link #applyReplicated} is the follower path: it replays a
 * transfer that the leader already accepted, so the sequence number arrives with
 * the entry instead of being generated here.
 *
 * <p>The listener list is copy-on-write so commits can notify observers without
 * blocking registration, and the counters are atomic so progress can be read
 * for the stats panel from any thread.
 */
public final class Bank {

    private final Ledger ledger;
    private final AtomicLong sequence = new AtomicLong(0L);
    private final AtomicLong applied = new AtomicLong(0L);
    private volatile long lastSeq = 0L;
    private final TransferLog log = new TransferLog();
    private final List<CommitListener> listeners = new CopyOnWriteArrayList<>();

    public Bank(Ledger ledger) {
        this.ledger = ledger;
    }

    /**
     * Performs a client-requested transfer and, when it succeeds, commits it.
     * The amount and the source/destination distinction are checked here because
     * they are bank policy rather than ledger mechanics; the actual debit and
     * credit are delegated to the ledger. A committed transfer receives the next
     * sequence number, is appended to the log, advances the applied counters,
     * and is delivered to every listener.
     *
     * @return the sequence number assigned to the committed transfer
     * @throws TransferException for a self transfer, a non-positive amount, an
     *     unknown account, or insufficient funds
     */
    public long transfer(int from, int to, long cents) throws TransferException {
        if (from == to) {
            throw TransferException.selfTransfer();
        }
        if (cents <= 0L) {
            throw TransferException.badAmount();
        }

        // TODO: ledger.move(from, to, cents); on success assign seq =
        // sequence.incrementAndGet(), build Transfer(seq, from, to, cents),
        // log.append(it), bump applied and lastSeq, notify listeners, return seq.
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * Replays a transfer that the leader already committed. The movement is
     * attempted against the local ledger; a missing account is tolerated (a
     * replica may not have every account materialized) by swallowing that
     * particular failure, while the entry is still recorded so the log mirrors
     * the leader. The local sequence counter is raised to the entry's sequence
     * under a lock with an explicit comparison, so it never moves backwards and
     * never advances past what has actually been seen.
     */
    public void applyReplicated(Transfer t) {
        // TODO: try { ledger.move(t.from(), t.to(), t.cents()); } catch
        // (TransferException e) { ignore only when e.code() is "no_such_account",
        // otherwise rethrow as unchecked or log }. Then log.append(t), bump
        // applied. Raise the counter with:
        //   synchronized (this) { if (t.seq() > lastSeq) lastSeq = t.seq(); }
        // (do NOT use accumulateAndGet(Math::max)).
        throw new UnsupportedOperationException("TODO");
    }

    /** Highest sequence number handed out so far on this node. */
    public long sequence() {
        return sequence.get();
    }

    /** Count of transfers committed (locally or replayed) on this node. */
    public long appliedCount() {
        return applied.get();
    }

    /** Sequence number of the most recently committed transfer. */
    public long lastSeq() {
        return lastSeq;
    }

    /** The commit log, shared with the replica feed for catch-up. */
    public TransferLog log() {
        return log;
    }

    /** Registers an observer notified after each commit. */
    public void addCommitListener(CommitListener l) {
        listeners.add(l);
    }
}
