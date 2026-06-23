package mx.ipn.escom.tesoreria.core;

/**
 * A single bank account in the ledger.
 *
 * <p>The id and owner are immutable. The balance is held in cents and is the
 * only mutable state; it must be read and written ONLY while holding this
 * account's intrinsic monitor (i.e. inside {@code synchronized (account)}).
 * There is no per-field lock object: the {@link Ledger} acquires the intrinsic
 * monitors of the two accounts involved in a transfer, ordered by id, to keep
 * the protocol deadlock free.
 */
public final class Account {

    /** Stable account identifier. */
    private final int id;

    /** Account owner display name. */
    private final String owner;

    /** Current balance in cents; mutate only under this account's monitor. */
    private long balanceCents;

    /**
     * Creates an account with an opening balance.
     *
     * @param id           the account id
     * @param owner        the owner name
     * @param balanceCents the opening balance in cents
     */
    public Account(int id, String owner, long balanceCents) {
        this.id = id;
        this.owner = owner;
        this.balanceCents = balanceCents;
    }

    /**
     * @return the immutable account id
     */
    public int id() {
        return id;
    }

    /**
     * @return the immutable owner name
     */
    public String owner() {
        return owner;
    }

    /**
     * Reads the balance. Callers that need consistency across the two accounts
     * of a transfer must already hold the relevant monitors.
     *
     * @return the current balance in cents
     */
    public synchronized long balanceCents() {
        return balanceCents;
    }

    /**
     * Replaces the balance. Must be called while holding this account's
     * monitor (the {@code synchronized} keyword here secures that invariant).
     *
     * @param balanceCents the new balance in cents
     */
    public synchronized void setBalanceCents(long balanceCents) {
        this.balanceCents = balanceCents;
    }
}
