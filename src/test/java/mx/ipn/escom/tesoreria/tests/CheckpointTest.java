package mx.ipn.escom.tesoreria.tests;

import java.util.Comparator;
import java.util.List;

import mx.ipn.escom.tesoreria.core.Account;
import mx.ipn.escom.tesoreria.core.Bank;
import mx.ipn.escom.tesoreria.core.Ledger;
import mx.ipn.escom.tesoreria.core.Transfer;
import mx.ipn.escom.tesoreria.core.TransferException;
import mx.ipn.escom.tesoreria.tests.RunTests.Case;

/**
 * Suite for the replica checkpoint primitives that let a follower resume from its
 * exact sequence after a full restart: {@link Bank#snapshotInto} (atomic capture
 * of watermark and balances), {@link Bank#seedLastSeq} (seed the watermark from a
 * restored checkpoint), and the convergence guarantee that a restored snapshot
 * plus the leader's delta lands exactly on the leader's per-account balances.
 *
 * <p>These are pure, deterministic, in-process checks (no socket, no Cloud
 * Storage). The Cloud Storage serialization and the cold restart are covered by
 * {@code pruebas/gcs-recovery.sh} against a real bucket.
 */
public final class CheckpointTest {

    /**
     * Builds the list of cases contributed by this suite.
     *
     * @return ordered list of named cases for {@link RunTests}
     */
    public List<Case> cases() {
        return List.of(
                new Case("snapshotInto captures balances and watermark together",
                        this::snapshotCaptures),
                new Case("restored checkpoint plus delta converges to the leader",
                        this::restorePlusDeltaConverges));
    }

    /**
     * Applies two replicated transfers and asserts the positional snapshot holds
     * each account's balance in id order and returns the current watermark.
     */
    private void snapshotCaptures() {
        Ledger ledger = ledgerOf(4, 1000L);
        Bank bank = new Bank(ledger);
        bank.applyReplicated(new Transfer(1L, 1, 2, 100L));
        bank.applyReplicated(new Transfer(2L, 3, 4, 50L));

        long[] snap = new long[4];
        long watermark = bank.snapshotInto(snap, 1);

        Assert.equals("watermark", 2L, watermark);
        Assert.equals("slot 0 (account 1)", 900L, snap[0]);
        Assert.equals("slot 1 (account 2)", 1100L, snap[1]);
        Assert.equals("slot 2 (account 3)", 950L, snap[2]);
        Assert.equals("slot 3 (account 4)", 1050L, snap[3]);
    }

    /**
     * Reaches a watermark on a leader, captures a snapshot at that point, restores
     * it into a fresh replica seeded to the same watermark, then replays the
     * leader's delta in sequence order and asserts the replica converges to the
     * leader on every account (a slightly stale checkpoint plus the delta is still
     * exactly consistent).
     */
    private void restorePlusDeltaConverges() {
        int n = 6;
        long base = 1000L;
        int idBase = 1;

        Ledger leaderL = ledgerOf(n, base);
        Bank leader = new Bank(leaderL);
        for (int i = 0; i < 40; i++) {
            drive(leader, 1 + (i % n), 1 + ((i + 3) % n), 10L + (i % 7));
        }
        long watermark = leader.lastSeq();

        long[] snap = new long[n];
        long captured = leader.snapshotInto(snap, idBase);
        Assert.equals("captured equals leader watermark", watermark, captured);

        Ledger repL = ledgerOf(n, base);
        Bank replica = new Bank(repL);
        for (int k = 0; k < snap.length; k++) {
            repL.get(idBase + k).setBalanceCents(snap[k]);
        }
        replica.seedLastSeq(captured);
        Assert.equals("replica seeded to watermark", watermark, replica.lastSeq());

        // Delta: more leader transfers after the checkpoint, replayed in seq order
        // exactly as the reorder buffer would deliver them.
        for (int i = 0; i < 25; i++) {
            drive(leader, 1 + ((i + 2) % n), 1 + ((i + 5) % n), 5L + (i % 9));
        }
        List<Transfer> delta = leader.log().since(watermark);
        delta.sort(Comparator.comparingLong(Transfer::seq));
        for (Transfer t : delta) {
            replica.applyReplicated(t);
        }

        for (int id = idBase; id < idBase + n; id++) {
            Assert.equals("account " + id + " converged",
                    leaderL.get(id).balanceCents(), repL.get(id).balanceCents());
        }
        Assert.equals("watermarks equal", leader.lastSeq(), replica.lastSeq());
        Assert.equals("total conserved", (long) n * base, repL.totalCents());
    }

    /** Commits one leader transfer, ignoring a self transfer or an insufficient balance. */
    private static void drive(Bank leader, int from, int to, long cents) {
        if (from == to) {
            return;
        }
        try {
            leader.transfer(from, to, cents);
        } catch (TransferException ignored) {
            // self transfer or low_balance is fine; it simply does not commit
        }
    }

    /** Builds a ledger of {@code n} accounts, ids 1..n, each holding {@code cents}. */
    private static Ledger ledgerOf(int n, long cents) {
        Ledger ledger = new Ledger();
        for (int id = 1; id <= n; id++) {
            ledger.add(new Account(id, "acct-" + id, cents));
        }
        return ledger;
    }
}
