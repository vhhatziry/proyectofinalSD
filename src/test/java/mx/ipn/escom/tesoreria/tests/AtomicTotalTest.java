package mx.ipn.escom.tesoreria.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import mx.ipn.escom.tesoreria.core.Account;
import mx.ipn.escom.tesoreria.core.Ledger;
import mx.ipn.escom.tesoreria.core.TransferException;
import mx.ipn.escom.tesoreria.tests.RunTests.Case;

/**
 * Regression suite for the torn-read defect that made the dashboard seal flash
 * "Discrepancia" under load even though money was conserved.
 *
 * <p>Before the fix, {@link Ledger#totalCents()} summed every balance with no
 * lock while concurrent transfers mutated accounts, so the scan could observe a
 * transfer half applied (debit done, credit pending) and report a total that
 * differed from the conserved seed. After the fix the scan holds the EXCLUSIVE
 * side of the ledger's snapshot lock and movements hold the SHARED side, so the
 * scan is a consistent snapshot and always reads exactly the seed total.
 *
 * <p>The harness only sees assertions thrown on the main thread, so the reader
 * threads do not assert directly: the first bad total they observe is recorded
 * and the main thread asserts on it after the join.
 */
public final class AtomicTotalTest {

    /** Number of accounts in the pool. */
    private static final int ACCOUNTS = 400;
    /** Opening balance per account, in cents. */
    private static final long OPENING_CENTS = 1_000_00L;
    /** Number of concurrent writer threads. */
    private static final int WRITERS = 8;
    /** Number of zero-sum settles each writer applies. */
    private static final int MOVES_PER_WRITER = 40_000;
    /** Number of concurrent reader (scan) threads. */
    private static final int READERS = 2;

    /**
     * Builds the list of cases contributed by this suite.
     *
     * @return ordered list of named cases for {@link RunTests}
     */
    public List<Case> cases() {
        return List.of(
                new Case("Ledger.totalCents is an atomic snapshot under concurrent settle",
                        this::scanNeverSeesTornTotal));
    }

    /**
     * Hammers a ledger with eight writer threads issuing zero-sum settles while
     * two reader threads continuously call {@code totalCents()}. Because every
     * settle is zero-sum the true total is invariant; a non-atomic scan would
     * still observe a half-applied transfer and report a wrong total. Asserts no
     * reader ever saw a total other than the seed, and the final total matches.
     */
    private void scanNeverSeesTornTotal() {
        final Ledger ledger = new Ledger();
        for (int id = 0; id < ACCOUNTS; id++) {
            ledger.add(new Account(id, "acct-" + id, OPENING_CENTS));
        }
        final long seedTotal = (long) ACCOUNTS * OPENING_CENTS;

        // First torn total a reader observed (sentinel = seedTotal means "none"),
        // and how many distinct bad observations happened in total.
        final AtomicLong firstBadTotal = new AtomicLong(seedTotal);
        final AtomicLong badObservations = new AtomicLong(0L);
        final AtomicBoolean writersDone = new AtomicBoolean(false);

        List<Thread> pool = new ArrayList<>();

        for (int w = 0; w < WRITERS; w++) {
            // Deterministic per-thread seed: no Math.random, no clock.
            final Random rnd = new Random(0x5DEECE66DL ^ (long) w);
            Thread writer = new Thread(() -> {
                for (int i = 0; i < MOVES_PER_WRITER; i++) {
                    int from = rnd.nextInt(ACCOUNTS);
                    int to = rnd.nextInt(ACCOUNTS);
                    if (from == to) {
                        continue;
                    }
                    long amount = 1L + rnd.nextInt(5_000);
                    try {
                        // settle is a force-apply zero-sum move; with existing
                        // accounts it never throws.
                        ledger.settle(from, to, amount);
                    } catch (TransferException e) {
                        // Not expected with existing accounts; record as a fault.
                        badObservations.incrementAndGet();
                    }
                }
            }, "writer-" + w);
            pool.add(writer);
        }

        for (int r = 0; r < READERS; r++) {
            Thread reader = new Thread(() -> {
                while (!writersDone.get()) {
                    long seen = ledger.totalCents();
                    if (seen != seedTotal) {
                        badObservations.incrementAndGet();
                        firstBadTotal.compareAndSet(seedTotal, seen);
                    }
                }
            }, "reader-" + r);
            pool.add(reader);
        }

        // Start readers first so they are already scanning while writers churn.
        for (Thread t : pool) {
            t.start();
        }
        // Wait only for writers; then signal readers to stop.
        for (Thread t : pool) {
            if (t.getName().startsWith("writer-")) {
                joinQuietly(t);
            }
        }
        writersDone.set(true);
        for (Thread t : pool) {
            if (t.getName().startsWith("reader-")) {
                joinQuietly(t);
            }
        }

        Assert.equals("no reader observed a torn total (first bad = sentinel seed)",
                seedTotal, firstBadTotal.get());
        Assert.equals("no torn observations recorded", 0L, badObservations.get());
        Assert.equals("final total conserved", seedTotal, ledger.totalCents());
    }

    /**
     * Joins a thread, converting an interruption into an assertion failure.
     *
     * @param t the thread to join
     */
    private static void joinQuietly(Thread t) {
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while joining " + t.getName());
        }
    }
}
