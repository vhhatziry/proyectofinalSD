package mx.ipn.escom.tesoreria.core;

/**
 * Callback invoked by the {@link Ledger} after a transfer is durably applied.
 *
 * <p>Listeners are registered on the leader's ledger and notified, in
 * registration order, on every successful commit. Concrete listeners include
 * the GCS journal (durability) and the replica feed (live replication).
 */
@FunctionalInterface
public interface CommitListener {

    /**
     * Invoked once per committed transfer.
     *
     * @param t the transfer that was just committed
     */
    void onCommit(Transfer t);
}
