package mx.ipn.escom.tesoreria.app;

import mx.ipn.escom.tesoreria.core.Bank;
import mx.ipn.escom.tesoreria.core.Ledger;
import mx.ipn.escom.tesoreria.durable.Journal;

/**
 * Snapshot of the live metrics of a single node, as required by the dashboard.
 *
 * <p>For each node the panel must show: status, number of accounts, total
 * balance, number of transfers, id of the last transaction, CPU/RAM/Disk usage,
 * and the number of transactions stored in Cloud Storage. The host metrics are
 * read with raw Java (no external libraries): CPU and RAM come from
 * {@code /proc} and disk usage from the filesystem; the GCS count is taken from
 * the {@link Journal} (only meaningful on the leader).
 *
 * <p>The figures are split across two collaborators: account count and total
 * balance come from the {@link Ledger}, while the transfer count, current
 * sequence and last transaction id come from the {@link Bank} that owns the
 * commit pipeline.
 */
public final class NodeStats {

    private final Ledger ledger;
    private final Bank bank;
    private final Journal journal;
    private final NodeConfig config;

    /**
     * Creates a stats collector bound to this node's live components.
     *
     * @param ledger  in-memory ledger of this node (accounts and balances)
     * @param bank    transaction orchestrator (transfer count, sequence, last tx)
     * @param journal durable journal; may be {@code null} on replica nodes
     * @param config  node configuration (used for node id and role)
     */
    public NodeStats(Ledger ledger, Bank bank, Journal journal, NodeConfig config) {
        this.ledger = ledger;
        this.bank = bank;
        this.journal = journal;
        this.config = config;
    }

    /** Identifier of this node (from configuration), e.g. "nodo-1". */
    public String nodeId() {
        return config.nodeId();
    }

    /** Role label: "leader" or "replica", derived from configuration. */
    public String role() {
        return config.isLeader() ? "leader" : "replica";
    }

    /** Liveness status of this node, e.g. "UP". */
    public String status() {
        // TODO: report runtime status; constant "UP" while serving.
        throw new UnsupportedOperationException("TODO");
    }

    /** Number of accounts loaded in the in-memory ledger. */
    public int accountCount() {
        // TODO: delegate to ledger account map size.
        throw new UnsupportedOperationException("TODO");
    }

    /** Total balance across all accounts, in cents (conservation invariant target). */
    public long totalCents() {
        // TODO: delegate to ledger.totalCents().
        throw new UnsupportedOperationException("TODO");
    }

    /** Number of committed transfers on this node. */
    public long transferCount() {
        // TODO: delegate to bank.appliedCount().
        throw new UnsupportedOperationException("TODO");
    }

    /** Sequence id of the last applied transaction (0 if none yet). */
    public long lastTxId() {
        // TODO: delegate to bank.lastSeq().
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * CPU usage of the host as a fraction in the range [0, 100], sampled from
     * {@code /proc/stat} (busy vs idle jiffies between two reads).
     *
     * @return percentage of CPU in use
     */
    public double cpuPercent() {
        // TODO: parse /proc/stat with raw java.io, no libraries.
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * RAM usage of the host as a percentage in [0, 100], computed from
     * {@code /proc/meminfo} (MemTotal vs MemAvailable).
     *
     * @return percentage of RAM in use
     */
    public double ramPercent() {
        // TODO: parse /proc/meminfo with raw java.io, no libraries.
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * Disk usage of the working filesystem as a percentage in [0, 100], computed
     * from {@link java.io.File} total/usable space.
     *
     * @return percentage of disk in use
     */
    public double diskPercent() {
        // TODO: use File.getTotalSpace / File.getUsableSpace.
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * Number of transactions durably stored in Cloud Storage, taken from the
     * journal. Replica nodes (without a journal) report 0.
     *
     * @return count of journal objects in GCS
     */
    public long gcsCount() {
        // TODO: return journal.stored() when journal != null, else 0.
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * Serializes this node's metrics to the JSON shape consumed by the panel.
     *
     * @return JSON object string with status, counts, balances and host usage
     */
    public String toJson() {
        // TODO: build JSON with gson from the fields above.
        throw new UnsupportedOperationException("TODO");
    }
}
