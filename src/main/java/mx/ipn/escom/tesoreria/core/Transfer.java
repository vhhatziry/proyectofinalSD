package mx.ipn.escom.tesoreria.core;

/**
 * Immutable record of a single committed transfer.
 *
 * <p>This is the unit of replication and durability: it carries a monotonic
 * sequence number assigned by the leader's ledger, the source and target
 * account ids, and the moved amount in cents. The cluster wire codec and the
 * GCS journal both serialize this exact shape.
 *
 * @param seq   monotonic sequence number assigned at commit time
 * @param from  source account id
 * @param to    target account id
 * @param cents amount moved, in cents
 */
public record Transfer(long seq, int from, int to, long cents) {
}
