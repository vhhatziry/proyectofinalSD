package mx.ipn.escom.tesoreria.core;

import java.util.concurrent.ConcurrentHashMap;

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
     * Sum of every account balance in cents. With no concurrent movement this
     * equals the value loaded at startup; observing it under load is the way the
     * system checks that transfers conserve money.
     */
    public long totalCents() {
        long sum = 0L;
        for (Account a : accounts.values()) {
            sum += a.balanceCents();
        }
        return sum;
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
        apply(from, to, cents, true);
    }

    /**
     * Applies an already-authorised transfer unconditionally: it debits and
     * credits without re-checking the source balance. Used on the follower and
     * cold-recovery paths, which replay transfers the leader already accepted.
     *
     * <p>Re-gating a replayed transfer on funds would be a bug. The leader stamps
     * a transfer's sequence number after moving the money, and not atomically
     * with it, so two concurrent transfers can be sequenced in the opposite order
     * to the order in which they actually moved money. A follower that replays in
     * sequence order and re-checks funds would then reject a transfer the leader
     * had authorised, drop it, and diverge from the leader on those accounts
     * permanently (the global total stays conserved, which hides it). Applying the
     * debit and credit unconditionally is order independent, so the follower
     * always converges to the leader's per-account balances. A balance may go
     * transiently negative mid-catch-up, which is cosmetic on a follower and
     * resolves once it is fully caught up.
     *
     * @throws TransferException with code {@code no_such_account} if either id is
     *     unknown
     */
    public void settle(int from, int to, long cents) throws TransferException {
        apply(from, to, cents, false);
    }

    /**
     * Shared movement mechanics behind {@link #move} and {@link #settle}: looks up
     * both accounts, takes their monitors lowest id first, optionally enforces
     * that the source can cover the amount, then performs the debit and matching
     * credit as one atomic step.
     *
     * @param enforceFunds when true, reject with {@code low_balance} if the source
     *     cannot cover {@code cents}; when false, apply unconditionally
     */
    private void apply(int from, int to, long cents, boolean enforceFunds)
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
        synchronized (first) {
            synchronized (second) {
                if (enforceFunds && src.balanceCents() < cents) {
                    throw TransferException.lowBalance(from);
                }
                src.setBalanceCents(src.balanceCents() - cents);
                dst.setBalanceCents(dst.balanceCents() + cents);
            }
        }
    }
}
