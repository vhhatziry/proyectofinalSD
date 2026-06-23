package mx.ipn.escom.tesoreria.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import mx.ipn.escom.tesoreria.core.Account;
import mx.ipn.escom.tesoreria.core.Ledger;
import mx.ipn.escom.tesoreria.core.TransferException;
import mx.ipn.escom.tesoreria.tests.RunTests.Case;

/**
 * Suite for {@link mx.ipn.escom.tesoreria.core.Ledger}.
 *
 * <p>Covers the two behaviours that matter most for the project: a single
 * transfer moves the exact amount between two accounts, and the conservation
 * invariant (constant total balance) holds under heavy concurrency. The
 * concurrency case fires many threads doing ordered nested-monitor transfers and
 * checks {@code totalCents()} is unchanged afterwards.
 */
public final class LedgerTest {

    /**
     * Builds the list of cases contributed by this suite.
     *
     * @return ordered list of named cases for {@link RunTests}
     */
    public List<Case> cases() {
        return List.of(
                new Case("Ledger.move transfers exact cents between two accounts",
                        this::transferMovesExactAmount),
                new Case("Ledger.move keeps total constant under concurrency",
                        this::invariantHoldsUnderConcurrency));
    }

    /**
     * Builds a fresh ledger, transfers a known amount from one account to
     * another and asserts both balances changed by exactly that amount while the
     * grand total stayed constant.
     */
    private void transferMovesExactAmount() {
        Ledger ledger = new Ledger();
        ledger.add(new Account(1, "A", 10000L));
        ledger.add(new Account(2, "B", 5000L));
        long initialTotal = ledger.totalCents();
        try {
            ledger.move(1, 2, 3000L);
        } catch (TransferException e) {
            throw new AssertionError("move unexpectedly threw: " + e.code());
        }
        Assert.equals("source debited", 7000L, ledger.get(1).balanceCents());
        Assert.equals("target credited", 8000L, ledger.get(2).balanceCents());
        Assert.equals("total conserved", initialTotal, ledger.totalCents());
    }

    /**
     * Spawns many threads performing random transfers among a pool of accounts
     * and asserts that {@code totalCents()} equals the initial total once all
     * threads complete (conservation invariant).
     */
    private void invariantHoldsUnderConcurrency() {
        final Ledger ledger = new Ledger();
        final int accounts = 20;
        for (int id = 1; id <= accounts; id++) {
            ledger.add(new Account(id, "acct-" + id, 100000L));
        }
        final long initialTotal = ledger.totalCents();
        final int threadCount = 8;
        final int perThread = 500;

        List<Thread> pool = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            Thread worker = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    int from = ThreadLocalRandom.current().nextInt(1, accounts + 1);
                    int to = ThreadLocalRandom.current().nextInt(1, accounts + 1);
                    if (from == to) {
                        continue;
                    }
                    long cents = ThreadLocalRandom.current().nextLong(1, 1001);
                    try {
                        ledger.move(from, to, cents);
                    } catch (TransferException ignored) {
                        // low_balance is acceptable; the invariant must still hold.
                    }
                }
            });
            pool.add(worker);
            worker.start();
        }
        for (Thread worker : pool) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while joining workers");
            }
        }
        Assert.equals("total conserved under concurrency", initialTotal, ledger.totalCents());
    }
}
