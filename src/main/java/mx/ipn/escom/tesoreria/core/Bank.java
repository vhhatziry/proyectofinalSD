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
    private final AtomicLong lastSeq = new AtomicLong(0L);
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

        ledger.move(from, to, cents);
        long seq = sequence.incrementAndGet();
        Transfer committed = new Transfer(seq, from, to, cents);
        log.append(committed);
        applied.incrementAndGet();
        lastSeq.accumulateAndGet(seq, Math::max);
        for (CommitListener listener : listeners) {
            listener.onCommit(committed);
        }
        return seq;
    }

    /**
     * Replays a transfer that the leader already committed. Used on two paths:
     * by a replica applying the leader's stream, and by the leader's own cold
     * recovery replaying the durable journal. Both share the same exactly-once
     * contract.
     *
     * <p>The call is <b>idempotent</b>: a transfer whose sequence is at or below
     * the highest already applied is ignored, so an overlap resent after a
     * reconnect never double-applies and corrupts balances. A fresh transfer is
     * <b>settled</b> (force-applied) against the local ledger rather than moved:
     * it is a transfer the leader already authorised, so it must not be re-gated
     * on funds. The leader assigns sequence numbers after moving money and not
     * atomically with it, so two concurrent transfers can be sequenced opposite to
     * the order they moved; a follower that re-checked funds in sequence order
     * could reject an authorised transfer and diverge from the leader on those
     * accounts forever. {@link Ledger#settle} applies the debit/credit
     * unconditionally, which is order independent and therefore convergent (a
     * missing account is still tolerated, since a follower may not have
     * materialized every account). The entry is recorded in the log so it mirrors
     * the leader, and counted. Both the applied watermark ({@code lastSeq}) and the
     * sequence counter are then raised to this entry's sequence: raising
     * {@code sequence} is what lets the leader resume numbering after cold recovery
     * from the highest journaled sequence instead of from 0, so the next client
     * transfer never reuses a sequence already in the journal.
     */
    public synchronized void applyReplicated(Transfer t) {
        if (t.seq() <= lastSeq.get()) {
            return; // already applied: a duplicate from a reconnect or replay
        }
        try {
            ledger.settle(t.from(), t.to(), t.cents());
        } catch (TransferException e) {
            // settle force-applies, so the only failure it can raise is a missing
            // account; a follower may not have materialized every account, so
            // tolerate it and let catch-up proceed without stalling the stream.
        }
        log.append(t);
        applied.incrementAndGet();
        lastSeq.accumulateAndGet(t.seq(), Math::max);
        sequence.accumulateAndGet(t.seq(), Math::max);
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
        return lastSeq.get();
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
