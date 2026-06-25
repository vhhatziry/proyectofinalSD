package mx.ipn.escom.tesoreria.tests;

import java.util.List;

import mx.ipn.escom.tesoreria.core.Account;
import mx.ipn.escom.tesoreria.core.Bank;
import mx.ipn.escom.tesoreria.core.Ledger;
import mx.ipn.escom.tesoreria.core.Transfer;
import mx.ipn.escom.tesoreria.core.TransferLog;
import mx.ipn.escom.tesoreria.tests.RunTests.Case;

/**
 * Suite for the exact catch-up-by-sequence mechanism (rubric item 4): a replica
 * that stopped at watermark X resumes from X+1, never from zero. It pins two
 * properties the feature rides on, which had no regression guard:
 *
 * <ul>
 *   <li>the commit log's {@code since(X)} is precisely the {@code X+1..} tail the
 *       leader streams back after a {@code CATCHUP X} greeting;</li>
 *   <li>a bank seeded at watermark X (as a restored checkpoint does) drops every
 *       replay at or below X and only applies from X+1, so a reconnect overlap is
 *       harmless and the watermark advances exactly.</li>
 * </ul>
 */
public final class CatchupTest {

    /**
     * Builds the list of cases contributed by this suite.
     *
     * @return ordered list of named cases for {@link RunTests}
     */
    public List<Case> cases() {
        return List.of(
                new Case("commit log since(X) is exactly the X+1.. tail", this::sinceIsTailFromXPlus1),
                new Case("bank seeded at X drops <=X and applies from X+1", this::resumesFromWatermarkPlus1));
    }

    /** With seqs 1..10 logged, since(6) is exactly 7..10 and since(10) is empty. */
    private void sinceIsTailFromXPlus1() {
        TransferLog log = new TransferLog();
        for (long seq = 1L; seq <= 10L; seq++) {
            log.append(new Transfer(seq, 1, 2, 1L));
        }
        List<Transfer> tail = log.since(6L);
        Assert.equals("tail size from X+1", 4L, tail.size());
        Assert.equals("first resumed seq is X+1", 7L, tail.get(0).seq());
        Assert.equals("last resumed seq", 10L, tail.get(tail.size() - 1).seq());
        Assert.equals("since at the tip is empty", 0L, log.since(10L).size());
    }

    /**
     * A bank seeded to watermark 5 (the restored-checkpoint state) ignores a
     * resent overlap at seqs 4 and 5, then applies 6 and 7, moving balances and
     * advancing the watermark to 7 while the total stays conserved.
     */
    private void resumesFromWatermarkPlus1() {
        Ledger ledger = new Ledger();
        for (int id = 1; id <= 4; id++) {
            ledger.add(new Account(id, "acct-" + id, 1000L));
        }
        Bank replica = new Bank(ledger);
        replica.seedLastSeq(5L);
        Assert.equals("seeded to watermark", 5L, replica.lastSeq());

        // Reconnect overlap: the leader resends 4 and 5 (<= X). Both must be dropped.
        replica.applyReplicated(new Transfer(4L, 1, 2, 100L));
        replica.applyReplicated(new Transfer(5L, 3, 4, 100L));
        Assert.equals("overlap at/below X leaves balances untouched", 1000L, ledger.get(1).balanceCents());
        Assert.equals("overlap does not move the watermark", 5L, replica.lastSeq());

        // From X+1 onward the transfers apply and the watermark advances.
        replica.applyReplicated(new Transfer(6L, 1, 2, 100L));
        replica.applyReplicated(new Transfer(7L, 2, 3, 50L));
        Assert.equals("acct 1 after seq 6", 900L, ledger.get(1).balanceCents());
        Assert.equals("acct 2 after seq 6 and 7", 1050L, ledger.get(2).balanceCents());
        Assert.equals("watermark advanced to 7", 7L, replica.lastSeq());
        Assert.equals("total conserved", 4000L, ledger.totalCents());
    }
}
