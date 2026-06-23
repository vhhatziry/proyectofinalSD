package mx.ipn.escom.tesoreria.tests;

import java.util.List;

import mx.ipn.escom.tesoreria.tests.RunTests.Case;

/**
 * Suite (skeleton) for {@link mx.ipn.escom.tesoreria.core.Ledger}.
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
     * grand total stayed constant. (Skeleton.)
     */
    private void transferMovesExactAmount() {
        // TODO: build a Ledger with two accounts, call move(from, to, cents)
        // (returns normally; throws TransferException on failure), then assert both
        // balances changed by exactly cents and that totalCents() is unchanged.
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * Spawns many threads performing random transfers among a pool of accounts
     * and asserts that {@code totalCents()} equals the initial total once all
     * threads complete (conservation invariant). (Skeleton.)
     */
    private void invariantHoldsUnderConcurrency() {
        // TODO: seed N accounts, capture initialTotal = totalCents();
        // run T threads x K transfers each (random from/to/amount),
        // join all threads, assert totalCents() == initialTotal.
        throw new UnsupportedOperationException("TODO");
    }
}
