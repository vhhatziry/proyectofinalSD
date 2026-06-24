package mx.ipn.escom.tesoreria.tests;

import java.util.List;

import mx.ipn.escom.tesoreria.core.Account;
import mx.ipn.escom.tesoreria.core.Bank;
import mx.ipn.escom.tesoreria.core.Ledger;
import mx.ipn.escom.tesoreria.core.Transfer;
import mx.ipn.escom.tesoreria.tests.RunTests.Case;

/**
 * Suite for the follower/recovery replay contract of
 * {@link Bank#applyReplicated(Transfer)}.
 *
 * <p>The leader assigns a transfer's sequence number after moving the money and
 * not atomically with it, so two concurrent transfers can be sequenced in the
 * opposite order to the order they actually moved money. A follower that replays
 * in sequence order must therefore <b>force-apply</b> each transfer (it was
 * already authorised by the leader) rather than re-check funds; re-checking would
 * reject an authorised transfer and leave the follower permanently diverged on
 * those accounts even though the global total stays conserved.
 *
 * <p>This suite pins that contract deterministically, with no threads or sockets:
 * it feeds a replica bank a sequence whose seq order contradicts causal order and
 * asserts the per-account balances converge to the leader's. It fails against a
 * follower that replays with the funds-checking {@code move} and passes once the
 * follower uses {@code settle}.
 */
public final class ReplayTest {

    /**
     * Builds the list of cases contributed by this suite.
     *
     * @return ordered list of named cases for {@link RunTests}
     */
    public List<Case> cases() {
        return List.of(
                new Case("applyReplicated converges despite a sequence inversion",
                        this::convergesDespiteInversion),
                new Case("applyReplicated ignores a resent overlap (idempotent)",
                        this::idempotentOnOverlap));
    }

    /**
     * Replays an inverted pair against a follower. Causally A->B happened and then
     * B->C, but the two were sequenced in the opposite order, so seq 1 is the B->C
     * debit (against a B that has not been funded yet at seq 1) and seq 2 is the
     * A->B credit. Replaying in seq order from the base must still land on the
     * leader's state (A=0, B=0, C=100): a funds-checking replay would drop seq 1
     * and leave B=100, C=0.
     */
    private void convergesDespiteInversion() {
        Ledger ledger = new Ledger();
        ledger.add(new Account(1, "A", 100L));
        ledger.add(new Account(2, "B", 0L));
        ledger.add(new Account(3, "C", 0L));
        Bank replica = new Bank(ledger);

        replica.applyReplicated(new Transfer(1L, 2, 3, 100L)); // B->C, B not yet funded
        replica.applyReplicated(new Transfer(2L, 1, 2, 100L)); // A->B

        Assert.equals("A debited", 0L, ledger.get(1).balanceCents());
        Assert.equals("B net zero", 0L, ledger.get(2).balanceCents());
        Assert.equals("C credited", 100L, ledger.get(3).balanceCents());
        Assert.equals("total conserved", 100L, ledger.totalCents());
        Assert.equals("watermark advanced", 2L, replica.lastSeq());
        Assert.equals("both applied", 2L, replica.appliedCount());
    }

    /**
     * Applies a short run and then re-applies an already-seen sequence: the
     * overlap (seq at or below the watermark) must be a no-op, so balances, the
     * watermark and the applied count are all unchanged by the resend.
     */
    private void idempotentOnOverlap() {
        Ledger ledger = new Ledger();
        ledger.add(new Account(1, "A", 100L));
        ledger.add(new Account(2, "B", 100L));
        Bank replica = new Bank(ledger);

        replica.applyReplicated(new Transfer(1L, 1, 2, 30L));
        replica.applyReplicated(new Transfer(2L, 2, 1, 10L));
        long appliedBefore = replica.appliedCount();
        long b2 = ledger.get(2).balanceCents();

        replica.applyReplicated(new Transfer(2L, 2, 1, 10L)); // resent overlap

        Assert.equals("watermark unchanged", 2L, replica.lastSeq());
        Assert.equals("applied unchanged", appliedBefore, replica.appliedCount());
        Assert.equals("balance unchanged by resend", b2, ledger.get(2).balanceCents());
        Assert.equals("total conserved", 200L, ledger.totalCents());
    }
}
