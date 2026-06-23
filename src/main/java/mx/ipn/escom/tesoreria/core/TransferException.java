package mx.ipn.escom.tesoreria.core;

/**
 * Signals that a money movement could not be carried out. Rather than returning
 * a status enum that callers might forget to inspect, the ledger and the bank
 * raise this checked exception so a rejected transfer is impossible to ignore.
 *
 * <p>Every instance carries a short machine-readable {@link #code()} drawn from
 * a small fixed vocabulary. The HTTP layer maps those codes to status codes and
 * to the JSON error body, while the human-readable message is meant for logs.
 * Instances are obtained through the static factory methods, never the
 * constructor, which keeps the set of valid codes closed and consistent.
 */
public final class TransferException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String code;

    private TransferException(String code, String msg) {
        super(msg);
        this.code = code;
    }

    /** Stable identifier of the failure category, suitable for clients. */
    public String code() {
        return code;
    }

    /** A transfer whose source and destination are the same account. */
    public static TransferException selfTransfer() {
        return new TransferException("self_transfer", "source and destination are the same account");
    }

    /** A transfer whose amount is zero or negative. */
    public static TransferException badAmount() {
        return new TransferException("bad_amount", "transfer amount must be a positive number of cents");
    }

    /** One of the two accounts named by the transfer does not exist. */
    public static TransferException noSuchAccount(int id) {
        return new TransferException("no_such_account", "account " + id + " does not exist");
    }

    /** The source account does not hold enough funds to cover the transfer. */
    public static TransferException lowBalance(int id) {
        return new TransferException("low_balance", "account " + id + " has insufficient funds");
    }
}
