package mx.ipn.escom.tesoreria.app;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import mx.ipn.escom.tesoreria.core.Bank;
import mx.ipn.escom.tesoreria.core.Ledger;
import mx.ipn.escom.tesoreria.core.Money;
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

    // Previous /proc/stat sample so CPU% is the busy fraction between two reads
    // (i.e. between two dashboard refreshes), guarded for concurrent requests.
    private final Object cpuLock = new Object();
    private long prevCpuTotal;
    private long prevCpuIdle;

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

    /** Liveness status of this node: a node answering its own stats is up. */
    public String status() {
        return "up";
    }

    /** Number of accounts loaded in the in-memory ledger. */
    public int accountCount() {
        return ledger.size();
    }

    /** Total balance across all accounts, in cents (conservation invariant target). */
    public long totalCents() {
        return ledger.totalCents();
    }

    /** Number of committed transfers on this node. */
    public long transferCount() {
        return bank.appliedCount();
    }

    /** Sequence id of the last applied transaction (0 if none yet). */
    public long lastTxId() {
        return bank.lastSeq();
    }

    /**
     * CPU usage of the host as a percentage in [0, 100], sampled from
     * {@code /proc/stat} (busy vs idle jiffies between two reads).
     *
     * @return percentage of CPU in use
     */
    public double cpuPercent() {
        try {
            String[] f = cpuFields();
            if (f.length < 5) {
                return 0.0;
            }
            long total = 0L;
            long idle = 0L;
            for (int i = 1; i < f.length; i++) {
                long value = Long.parseLong(f[i]);
                total += value;
                if (i == 4 || i == 5) {
                    idle += value; // idle + iowait
                }
            }
            synchronized (cpuLock) {
                long deltaTotal = total - prevCpuTotal;
                long deltaIdle = idle - prevCpuIdle;
                prevCpuTotal = total;
                prevCpuIdle = idle;
                if (deltaTotal <= 0L) {
                    return 0.0;
                }
                return clamp((1.0 - (double) deltaIdle / deltaTotal) * 100.0);
            }
        } catch (RuntimeException | IOException e) {
            return 0.0;
        }
    }

    /**
     * RAM usage of the host as a percentage in [0, 100], computed from
     * {@code /proc/meminfo} (MemTotal vs MemAvailable).
     *
     * @return percentage of RAM in use
     */
    public double ramPercent() {
        try {
            long total = readMeminfo("MemTotal");
            long available = readMeminfo("MemAvailable");
            if (total <= 0L) {
                return 0.0;
            }
            return clamp((double) (total - available) / total * 100.0);
        } catch (RuntimeException | IOException e) {
            return 0.0;
        }
    }

    /**
     * Disk usage of the working filesystem as a percentage in [0, 100], computed
     * from {@link java.io.File} total/usable space.
     *
     * @return percentage of disk in use
     */
    public double diskPercent() {
        File root = new File("/");
        long total = root.getTotalSpace();
        long usable = root.getUsableSpace();
        if (total <= 0L) {
            return 0.0;
        }
        return clamp((double) (total - usable) / total * 100.0);
    }

    /**
     * Number of transactions durably stored in Cloud Storage, taken from the
     * journal. Replica nodes (without a journal) report 0.
     *
     * @return count of journal objects in GCS
     */
    public long gcsCount() {
        return (journal != null) ? journal.stored() : 0L;
    }

    /**
     * Serializes this node's metrics to the JSON shape consumed by the panel.
     * The keys match the {@code data-field} names in dashboard.html exactly.
     *
     * @return JSON object string with status, counts, balances and host usage
     */
    public String toJson() {
        JsonObject node = new JsonObject();
        node.addProperty("nodeId", nodeId());
        node.addProperty("role", role());
        node.addProperty("status", status());
        node.addProperty("accountCount", accountCount());
        node.addProperty("totalBalance", new BigDecimal(Money.toDecimal(totalCents())));
        node.addProperty("transferCount", transferCount());
        node.addProperty("lastTxId", lastTxId());
        node.addProperty("cpuPercent", round1(cpuPercent()));
        node.addProperty("ramPercent", round1(ramPercent()));
        node.addProperty("diskPercent", round1(diskPercent()));
        node.addProperty("gcsCount", gcsCount());
        return new Gson().toJson(node);
    }

    /** Reads the aggregate "cpu" line of /proc/stat split into fields. */
    private static String[] cpuFields() throws IOException {
        for (String line : Files.readAllLines(Path.of("/proc/stat"))) {
            if (line.startsWith("cpu ")) {
                return line.trim().split("\\s+");
            }
        }
        return new String[0];
    }

    /** Reads a kB value from /proc/meminfo by key (e.g. "MemTotal"). */
    private static long readMeminfo(String key) throws IOException {
        for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
            if (line.startsWith(key + ":")) {
                String[] parts = line.trim().split("\\s+");
                return Long.parseLong(parts[1]);
            }
        }
        return 0L;
    }

    /** Constrains a percentage to the [0, 100] range. */
    private static double clamp(double value) {
        return Math.max(0.0, Math.min(100.0, value));
    }

    /** Rounds a percentage to a single decimal place. */
    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
