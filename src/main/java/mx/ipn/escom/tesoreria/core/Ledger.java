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
     * {@code to}. Both account monitors are held for the duration so the pair of
     * balances changes together; the monitors are taken lowest id first to keep
     * concurrent moves on the same pair from deadlocking.
     *
     * @throws TransferException with code {@code no_such_account} if either id is
     *     unknown, or {@code low_balance} if the source cannot cover the amount
     */
    public void move(int from, int to, long cents) throws TransferException {
        Account src = accounts.get(from);
        if (src == null) {
            throw TransferException.noSuchAccount(from);
        }
        Account dst = accounts.get(to);
        if (dst == null) {
            throw TransferException.noSuchAccount(to);
        }

        // TODO: take both intrinsic monitors ordered by id (lower first) and,
        // while holding them, verify src.balanceCents() >= cents (else throw
        // lowBalance(from)) before applying the matched debit and credit via
        // setBalanceCents. See the locking note in the class javadoc.
        throw new UnsupportedOperationException("TODO");
    }
}
