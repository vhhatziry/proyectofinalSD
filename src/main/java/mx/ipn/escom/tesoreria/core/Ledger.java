package mx.ipn.escom.tesoreria.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongSupplier;

/**
 * Holds the set of accounts and performs the raw mechanics of moving money
 * between two of them. The ledger deliberately knows nothing about transaction
 * sequencing, the commit log, or replication listeners; those concerns live in
 * {@link Bank}, which wraps the ledger. Keeping this class narrow makes the
 * money invariant easy to reason about: the only mutation it ever applies is a
 * debit on one balance matched by an equal credit on another.
 *
 * <p>Accounts are stored in a concurrent map keyed by id, so reads and the
 * registration of new accounts are lock free. A money movement, on the other
 * hand, must observe both balances atomically. To do that without risking a
 * deadlock between two transfers that touch the same pair of accounts in
 * opposite directions, {@link #move(int, int, long)} always acquires the two
 * account monitors in a fixed order determined by account id.
 *
 * <p>Failures are reported as {@link TransferException}s carrying a specific
 * code, never as a return value, so a caller cannot accidentally proceed after
 * a rejected move. Validation that is policy rather than mechanics (self
 * transfers, non-positive amounts) is left to {@link Bank}.
 */
public final class Ledger {

    private final ConcurrentHashMap<Integer, Account> accounts = new ConcurrentHashMap<>();

    /**
     * Read/write lock that makes {@link #totalCents()} a consistent snapshot.
     * Every money movement (the shared {@link #apply} path) holds the SHARED
     * (read) side while it does its debit+credit, so any number of transfers
     * still run in parallel; the scan in {@code totalCents()} holds the
     * EXCLUSIVE (write) side, so it can never run concurrently with a
     * half-applied transfer (debit done, credit pending) and therefore never
     * captures a torn read. Because transfers are zero-sum the true total is
     * invariant, so an atomic scan reports the exact same total on every node
     * and the dashboard consistency seal stays green even under load.
     *
     * <p>FAIR (true): a continuous stream of transfers (readers) must not
     * starve the scan (writer). With fairness the writer that is waiting is
     * served in arrival order, so the dashboard's {@code /panel} cannot hang
     * under sustained transfer load.
     *
     * <p>Lock-order invariant (the account monitor is always a SINK, never
     * taken before this lock, so the lock graph stays a DAG and is deadlock
     * free):
     * <ul>
     *   <li>transfer/settle: readLock -&gt; account monitors</li>
     *   <li>replica apply: bank monitor -&gt; readLock -&gt; account monitors</li>
     *   <li>scan: writeLock -&gt; account monitor (one at a time, read-only,
     *       uncontended because the writeLock excludes all writers)</li>
     * </ul>
     * NEVER acquire this lock while already holding an account monitor.
     */
    private final ReentrantReadWriteLock snapshotLock = new ReentrantReadWriteLock(true);

    /** Registers an account. A later registration with the same id replaces the earlier one. */
    public void add(Account a) {
        accounts.put(a.id(), a);
    }

    /** Returns the account with the given id, or {@code null} when none is registered. */
    public Account get(int id) {
        return accounts.get(id);
    }

    /** Number of accounts currently held. */
    public int size() {
        return accounts.size();
    }

    /**
     * Sum of every account balance in cents, captured as a CONSISTENT SNAPSHOT.
     * The scan runs under the EXCLUSIVE (write) side of {@link #snapshotLock},
     * which is mutually exclusive with the SHARED (read) side held by every
     * money movement in {@link #apply}. Therefore the scan never overlaps a
     * half-applied transfer (debit done, credit pending) and reads an EXACT
     * total even while transfers run concurrently. With no concurrent movement
     * this equals the value loaded at startup; observing it under load is how
     * the system checks that transfers conserve money, and because transfers
     * are zero-sum every node reports the identical total under an atomic scan.
     *
     * <p>Lock order while scanning: writeLock -&gt; account monitor (per
     * account, one at a time, via the synchronized {@link Account#balanceCents()};
     * these acquisitions are uncontended because the writeLock excludes all
     * writers). The scan is not a leaf lock, but the account monitor is always a
     * sink in the lock graph, so this stays deadlock free.
     */
    public long totalCents() {
        snapshotLock.writeLock().lock();
        try {
            long sum = 0L;
            for (Account a : accounts.values()) {
                sum += a.balanceCents();
            }
            return sum;
        } finally {
            snapshotLock.writeLock().unlock();
        }
    }

    /**
     * Debits {@code cents} from {@code from} and credits the same amount to
     * {@code to}, refusing the move when the source cannot cover it. This is the
     * client-facing path on the leader, where a transfer must be rejected on
     * insufficient funds. Both account monitors are held for the duration so the
     * pair of balances changes together; the monitors are taken lowest id first
     * to keep concurrent moves on the same pair from deadlocking.
     *
     * @throws TransferException with code {@code no_such_account} if either id is
     *     unknown, or {@code low_balance} if the source cannot cover the amount
     */
    public void move(int from, int to, long cents) throws TransferException {
        apply(from, to, cents, true, null);
    }

    /**
     * Same debit and credit as {@link #move(int, int, long)}, but assigns the
     * transfer's sequence number by invoking {@code sequencer} WHILE both account
     * monitors are held, and returns it. Stamping the sequence inside the lock makes
     * the sequence order match the money-movement order for any shared account, so a
     * follower replaying in sequence order never observes a balance go transiently
     * negative: the credit that funds a later debit always carries the lower
     * sequence. {@code sequencer} must be cheap and lock free (an {@code AtomicLong}
     * increment); doing real work inside it would extend the critical section.
     *
     * @param sequencer supplies the committed transfer's sequence number; invoked
     *     exactly once, inside the lock, only on success
     * @return the sequence number produced by {@code sequencer}
     * @throws TransferException with code {@code no_such_account} if either id is
     *     unknown, or {@code low_balance} if the source cannot cover the amount
     */
    public long move(int from, int to, long cents, LongSupplier sequencer)
            throws TransferException {
        return apply(from, to, cents, true, sequencer);
    }

    /**
     * Applies an already-authorised transfer unconditionally: it debits and
     * credits without re-checking the source balance. Used on the follower and
     * cold-recovery paths, which replay transfers the leader already accepted.
     *
     * <p>Re-gating a replayed transfer on funds would be a bug. {@link #move} can
     * reject on {@code low_balance}; on the follower path that rejection is caught
     * and the transfer is dropped, yet the commit counters still advance, so the
     * money never moves while the watermark passes it and the follower diverges from
     * the leader on those accounts permanently (the global total stays conserved,
     * which hides it). That bites whenever the follower's state differs from the
     * leader's at apply time, for example a reconnect overlap or a checkpoint-restored
     * intermediate state. Applying the debit and credit unconditionally never drops
     * and is order independent, so the follower always converges to the leader's
     * per-account balances.
     *
     * <p>The leader now stamps a transfer's sequence number inside the same lock that
     * moves the money (see {@link #move(int, int, long, java.util.function.LongSupplier)}),
     * so sequence order matches money-movement order and a follower replaying in
     * sequence order no longer goes transiently negative; {@code settle} is kept
     * regardless, as the convergence guarantee above.
     *
     * @throws TransferException with code {@code no_such_account} if either id is
     *     unknown
     */
    public void settle(int from, int to, long cents) throws TransferException {
        apply(from, to, cents, false, null);
    }

    /**
     * Shared movement mechanics behind {@link #move} and {@link #settle}: looks up
     * both accounts, takes their monitors lowest id first, optionally enforces that
     * the source can cover the amount, performs the debit and matching credit as one
     * atomic step, and (when a {@code sequencer} is supplied) assigns the sequence
     * number inside that same step so commit order matches money-movement order.
     *
     * @param enforceFunds when true, reject with {@code low_balance} if the source
     *     cannot cover {@code cents}; when false, apply unconditionally
     * @param sequencer when non-null, invoked once inside the lock on success to
     *     produce the sequence number; when null, nothing is stamped and 0 is returned
     * @return the sequence number from {@code sequencer}, or 0 when none was supplied
     */
    private long apply(int from, int to, long cents, boolean enforceFunds, LongSupplier sequencer)
            throws TransferException {
        Account src = accounts.get(from);
        if (src == null) {
            throw TransferException.noSuchAccount(from);
        }
        Account dst = accounts.get(to);
        if (dst == null) {
            throw TransferException.noSuchAccount(to);
        }

        // Acquire both monitors lowest-id first so two opposite transfers on the
        // same pair cannot deadlock; the balances then change as one atomic step.
        Account first = (from < to) ? src : dst;
        Account second = (from < to) ? dst : src;
        // Hold the SHARED side of snapshotLock across the debit+credit so that a
        // consistent scan in totalCents() (which holds the EXCLUSIVE side) can
        // never observe this transfer half applied. The readLock is taken BEFORE
        // the account monitors (the account monitor stays a sink in the lock
        // graph), and is released in finally so a rejected move (low_balance) or
        // a missing account never leaks the lock and stalls the scan. The
        // accounts.get lookups and null-checks above are deliberately outside the
        // lock so the shared critical section covers only the money movement.
        snapshotLock.readLock().lock();
        try {
            synchronized (first) {
                synchronized (second) {
                    if (enforceFunds && src.balanceCents() < cents) {
                        throw TransferException.lowBalance(from);
                    }
                    src.setBalanceCents(src.balanceCents() - cents);
                    dst.setBalanceCents(dst.balanceCents() + cents);
                    // Stamp the sequence inside the lock (when one was requested) so the
                    // commit order matches the money-movement order; see the move overload.
                    return (sequencer != null) ? sequencer.getAsLong() : 0L;
                }
            }
        } finally {
            snapshotLock.readLock().unlock();
        }
    }
}
