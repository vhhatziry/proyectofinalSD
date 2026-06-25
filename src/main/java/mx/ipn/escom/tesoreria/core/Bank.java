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
 * never valid as policy, then asks the ledger to perform the movement and stamp
 * the next sequence number atomically with it, records the entry, and fans it
 * out to listeners. {@link #applyReplicated} is the follower path: it replays a
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
     * credit, and atomically with them the next sequence number, are delegated to
     * the ledger. The committed transfer is then appended to the log, advances the
     * applied counters, and is delivered to every listener.
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

        // Move the money and assign the sequence number atomically: the sequencer
        // runs inside the ledger's per-account lock, so sequence order matches the
        // money-movement order and a follower never observes a transient negative.
        long seq = ledger.move(from, to, cents, sequence::incrementAndGet);
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
     * on funds: re-gating with {@link Ledger#move} could reject it on
     * {@code low_balance}, and that rejection would be caught and the transfer
     * dropped while the counters still advance, so the money would never move and
     * the follower would diverge from the leader on those accounts permanently. That
     * bites whenever the follower's state differs from the leader's at apply time (a
     * reconnect overlap, a checkpoint-restored intermediate state). {@link
     * Ledger#settle} applies the debit/credit unconditionally and never drops, which
     * is order independent and therefore convergent (a missing account is still
     * tolerated, since a follower may not have materialized every account). The
     * leader now stamps the sequence inside the move's lock, so sequence order
     * matches money-movement order and a follower replaying in order no longer goes
     * transiently negative. The entry is recorded in the log so it mirrors
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

    /**
     * Seeds the applied watermark and the sequence counter from a restored
     * follower checkpoint, before replication starts. Both are raised (never
     * lowered) to {@code watermark} so the subsequent CATCHUP asks the leader
     * only for transfers past the checkpoint instead of replaying from the start.
     *
     * @param watermark highest sequence the loaded checkpoint had already applied
     */
    public synchronized void seedLastSeq(long watermark) {
        lastSeq.accumulateAndGet(watermark, Math::max);
        sequence.accumulateAndGet(watermark, Math::max);
    }

    /**
     * Atomically captures the applied watermark together with a positional
     * snapshot of account balances, for a follower checkpoint. The balances of
     * accounts {@code idBase .. idBase + balances.length - 1} are written into
     * {@code balances} and the current {@link #lastSeq()} is returned, all while
     * holding this bank's monitor. Because a follower mutates state only through
     * {@link #applyReplicated} (also synchronized on this bank), the pair is
     * internally consistent: no replicated transfer can land between reading the
     * watermark and copying the balances. A missing account reads as 0.
     *
     * @param balances destination, one slot per account starting at {@code idBase}
     * @param idBase   id of the account mapped to slot 0
     * @return the watermark (highest applied sequence) at capture time
     */
    public synchronized long snapshotInto(long[] balances, int idBase) {
        for (int k = 0; k < balances.length; k++) {
            Account account = ledger.get(idBase + k);
            balances[k] = (account != null) ? account.balanceCents() : 0L;
        }
        return lastSeq.get();
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
