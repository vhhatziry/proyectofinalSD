package mx.ipn.escom.tesoreria.durable;

import com.google.gson.Gson;

import mx.ipn.escom.tesoreria.core.Account;
import mx.ipn.escom.tesoreria.core.Bank;
import mx.ipn.escom.tesoreria.core.Ledger;

import java.io.IOException;

/**
 * Replica-side durability: persists and restores a follower's catch-up point so
 * that a full process restart resumes from the exact sequence it had already
 * applied, instead of reloading the dataset and replaying the leader's whole log
 * from zero. This is the difference between "I lost the connection, resend from
 * my sequence" (which already worked, the watermark surviving in memory) and "my
 * process died and revived, resend from my sequence" (which needs the watermark
 * and the balances to survive on durable storage).
 *
 * <p>A checkpoint is a single Cloud Storage object, overwritten in place, holding
 * {@code {v, watermark, idBase, count, balances}}. The watermark is the highest
 * applied sequence; {@code balances} is a positional snapshot where slot k is the
 * balance of account {@code idBase + k} (ids are contiguous from {@code idBase},
 * so position encodes the id and no per-account key is stored). The watermark and
 * the balances are captured together under the bank monitor via
 * {@link Bank#snapshotInto}, so the pair is internally consistent even while the
 * sync thread is applying transfers.
 *
 * <p>Cadence: a graceful stop writes the exact last-applied state (the live demo
 * stops the VM, which is graceful), and a periodic daemon writes a floor so a
 * hard crash loses at most one interval; a save whose watermark is unchanged
 * since the last one is skipped, so an idle replica costs nothing. On startup
 * {@link #load()} restores the balances onto the already CSV-loaded ledger and
 * seeds the bank watermark, then replication issues {@code CATCHUP <watermark>}
 * and the leader streams only the delta. A missing, unreadable, or mismatched
 * checkpoint is ignored so startup falls back to a full catch-up; {@link #load()}
 * never throws.
 */
public final class ReplicaCheckpoint {

    /** Current checkpoint format version; a different version is rejected on load. */
    private static final int VERSION = 1;

    /** Object store the checkpoint is written to and read from. */
    private final GcsStore store;

    /** Bank whose watermark is seeded on load and captured on save. */
    private final Bank bank;

    /** Ledger whose balances are restored on load. */
    private final Ledger ledger;

    /** Full Cloud Storage object name for this replica's checkpoint. */
    private final String objectName;

    /** Id of the first account, mapped to balances slot 0. */
    private final int idBase;

    /** Seconds the periodic flusher sleeps between saves. */
    private final int intervalSecs;

    private final Gson gson = new Gson();

    /** Lifecycle flag for the periodic flusher. */
    private volatile boolean running;

    /** Background daemon writing periodic checkpoints; null until started. */
    private volatile Thread flusher;

    /** Watermark of the last successful save, so an unchanged state is not rewritten. */
    private long lastSavedWatermark = -1L;

    /**
     * Creates a checkpoint bound to this replica's components.
     *
     * @param store        object store for the checkpoint object
     * @param bank         bank to seed on load and snapshot on save
     * @param ledger       ledger to restore on load
     * @param objectName   full GCS object name, e.g. {@code checkpoint/nodo-2.json}
     * @param idBase       id of the account mapped to balances slot 0
     * @param intervalSecs seconds between periodic flushes
     */
    public ReplicaCheckpoint(GcsStore store, Bank bank, Ledger ledger,
                             String objectName, int idBase, int intervalSecs) {
        this.store = store;
        this.bank = bank;
        this.ledger = ledger;
        this.objectName = objectName;
        this.idBase = idBase;
        this.intervalSecs = intervalSecs;
    }

    /** Serialized checkpoint payload; gson maps it to and from the object body. */
    private static final class Snap {
        int v;
        long watermark;
        int idBase;
        int count;
        long[] balances;
    }

    /**
     * Restores the checkpoint before replication starts: loads the balances onto
     * the ledger and seeds the bank watermark so {@code CATCHUP} asks only for the
     * delta. Safe to call when no checkpoint exists yet, when the object is
     * corrupt, or when it was written for a different dataset (a mismatch of
     * version, id base, or account count): all of those are logged and skipped so
     * the replica simply falls back to a full catch-up. This method never throws.
     */
    public void load() {
        try {
            String body = store.getCheckpoint(objectName);
            if (body == null) {
                return; // first boot: no checkpoint yet, full catch-up
            }
            Snap snap = gson.fromJson(body, Snap.class);
            if (snap == null || snap.v != VERSION || snap.balances == null
                    || snap.idBase != idBase || snap.count != ledger.size()
                    || snap.balances.length != snap.count) {
                System.err.println("[checkpoint] ignoring incompatible " + objectName
                        + " (version/idBase/count mismatch); full catch-up");
                return;
            }
            for (int k = 0; k < snap.balances.length; k++) {
                Account account = ledger.get(idBase + k);
                if (account != null) {
                    account.setBalanceCents(snap.balances[k]);
                }
            }
            bank.seedLastSeq(snap.watermark);
            lastSavedWatermark = snap.watermark;
            System.out.println("[checkpoint] restored " + objectName
                    + " at watermark " + snap.watermark);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[checkpoint] load interrupted; full catch-up");
        } catch (IOException | RuntimeException e) {
            // Includes gson's JsonSyntaxException (a RuntimeException): a corrupt
            // or unexpected body is swallowed so startup degrades to a full
            // catch-up rather than failing.
            System.err.println("[checkpoint] could not load " + objectName
                    + "; full catch-up: " + e.getMessage());
        }
    }

    /**
     * Atomically captures {watermark, balances} and uploads it, unless nothing
     * has been applied since the last successful save. The capture holds the bank
     * monitor only long enough to copy the balances; serialization and the upload
     * happen outside it. A failure is logged but not thrown, so neither the
     * periodic flusher nor a shutdown hook is derailed by a transient GCS error.
     */
    public synchronized void save() {
        int count = ledger.size();
        long[] balances = new long[count];
        long watermark = bank.snapshotInto(balances, idBase);
        if (watermark == lastSavedWatermark) {
            return; // nothing new applied since the last save
        }
        Snap snap = new Snap();
        snap.v = VERSION;
        snap.watermark = watermark;
        snap.idBase = idBase;
        snap.count = count;
        snap.balances = balances;
        try {
            store.putCheckpoint(objectName, gson.toJson(snap));
            lastSavedWatermark = watermark;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            System.err.println("[checkpoint] could not persist " + objectName
                    + " at watermark " + watermark + ": " + e.getMessage());
        }
    }

    /** Starts the periodic flusher daemon that writes a checkpoint floor. */
    public void start() {
        running = true;
        flusher = new Thread(this::flushLoop, "replica-checkpoint");
        flusher.setDaemon(true);
        flusher.start();
    }

    /** Periodic loop: sleeps the interval, then saves, until stopped. */
    private void flushLoop() {
        while (running) {
            try {
                Thread.sleep(intervalSecs * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            save();
        }
    }

    /** Stops the periodic flusher (the final exact save is done by the caller). */
    public void stop() {
        running = false;
        Thread current = flusher;
        if (current != null) {
            current.interrupt();
        }
    }
}
