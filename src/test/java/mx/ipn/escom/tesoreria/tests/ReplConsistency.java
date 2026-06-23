package mx.ipn.escom.tesoreria.tests;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import mx.ipn.escom.tesoreria.cluster.ReplicaFeed;
import mx.ipn.escom.tesoreria.cluster.ReplicaSync;
import mx.ipn.escom.tesoreria.core.Account;
import mx.ipn.escom.tesoreria.core.Bank;
import mx.ipn.escom.tesoreria.core.Ledger;
import mx.ipn.escom.tesoreria.core.TransferException;

/**
 * Multi-writer replication consistency integration check (not a unit case, so it
 * is not run by {@link RunTests}; it opens a real socket). It wires a leader
 * {@link Bank} to a replica {@link Bank} over the real {@link ReplicaFeed} and
 * {@link ReplicaSync}, hammers the leader with several writer threads doing
 * thousands of concurrent transfers over a shared pool of accounts, drains the
 * feed, then asserts the PER-ACCOUNT balance leader == replica for EVERY account.
 *
 * <p>The global sum is conserved even when a single transfer is lost or
 * duplicated on the replica, so only a per-account comparison catches a
 * replication bug. Run it with the fat jar on the classpath:
 *
 * <pre>
 * mvn -q -DskipTests package
 * java -cp target/test-classes:target/tesoreria-distribuida-jar-with-dependencies.jar \
 *     mx.ipn.escom.tesoreria.tests.ReplConsistency
 * </pre>
 *
 * It prints {@code REPL_CONSISTENCY_OK} and exits 0 on success.
 */
public final class ReplConsistency {

    private static final int PORT = 9099;
    private static final int ACCOUNTS = 200;
    private static final long START_CENTS = 1_000_000L;
    private static final int THREADS = 8;
    private static final int PER_THREAD = 3000;

    private ReplConsistency() {
    }

    /**
     * Runs the load and the per-account comparison.
     *
     * @param args ignored
     * @throws Exception if the socket wiring or threads fail fatally
     */
    public static void main(String[] args) throws Exception {
        Ledger leaderL = freshLedger();
        Bank leader = new Bank(leaderL);
        ReplicaFeed feed = new ReplicaFeed(PORT, leader.log());
        feed.start();
        leader.addCommitListener(feed);

        Ledger replicaL = freshLedger();
        Bank replica = new Bank(replicaL);
        ReplicaSync sync = new ReplicaSync("127.0.0.1", PORT, replica);
        sync.start();
        Thread.sleep(400); // let the replica connect and issue CATCHUP

        AtomicLong ok = new AtomicLong();
        Thread[] writers = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            writers[i] = new Thread(() -> hammer(leader, ok), "writer-" + i);
        }
        for (Thread t : writers) {
            t.start();
        }
        for (Thread t : writers) {
            t.join();
        }

        long target = leader.lastSeq();
        long deadline = System.currentTimeMillis() + 30_000L;
        while (replica.lastSeq() < target && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        boolean reached = replica.lastSeq() == target;
        boolean appliedEq = leader.appliedCount() == replica.appliedCount();
        int mismatches = 0;
        for (int id = 1; id <= ACCOUNTS; id++) {
            if (leaderL.get(id).balanceCents() != replicaL.get(id).balanceCents()) {
                mismatches++;
            }
        }
        long expectTotal = (long) ACCOUNTS * START_CENTS;
        boolean totalsOk = leaderL.totalCents() == expectTotal && replicaL.totalCents() == expectTotal;

        System.out.println("successful transfers: " + ok.get());
        System.out.println("watermark reached:    " + reached + " (seq " + replica.lastSeq() + "/" + target + ")");
        System.out.println("applied equal:        " + appliedEq
                + " (leader=" + leader.appliedCount() + " replica=" + replica.appliedCount() + ")");
        System.out.println("per-account matches:  " + (mismatches == 0) + " (mismatches=" + mismatches + ")");
        System.out.println("totals conserved:     " + totalsOk);

        sync.stop();
        feed.stop();
        boolean pass = reached && appliedEq && mismatches == 0 && totalsOk;
        System.out.println(pass ? "REPL_CONSISTENCY_OK" : "REPL_CONSISTENCY_FAIL");
        System.exit(pass ? 0 : 1);
    }

    /** One writer thread: random transfers between distinct accounts. */
    private static void hammer(Bank leader, AtomicLong ok) {
        for (int n = 0; n < PER_THREAD; n++) {
            int from = ThreadLocalRandom.current().nextInt(1, ACCOUNTS + 1);
            int to = ThreadLocalRandom.current().nextInt(1, ACCOUNTS + 1);
            if (from == to) {
                continue;
            }
            try {
                leader.transfer(from, to, ThreadLocalRandom.current().nextLong(1, 101));
                ok.incrementAndGet();
            } catch (TransferException ignored) {
                // low_balance is expected under random load; not a failure
            }
        }
    }

    /** Builds a ledger of {@link #ACCOUNTS} accounts each holding {@link #START_CENTS}. */
    private static Ledger freshLedger() {
        Ledger ledger = new Ledger();
        for (int id = 1; id <= ACCOUNTS; id++) {
            ledger.add(new Account(id, "Owner " + id, START_CENTS));
        }
        return ledger;
    }
}
