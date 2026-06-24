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
 * Replication divergence regression check for the sequence-inversion bug (not a
 * unit case; it opens a real socket, so {@link RunTests} does not run it).
 *
 * <p>On the leader, {@link Bank#transfer} performs the ledger movement and only
 * afterwards stamps the sequence number, and those two steps are not atomic. Two
 * concurrent transfers can therefore receive sequence numbers in the opposite
 * order to the order in which they actually moved money. A follower that replays
 * transfers in sequence order and re-gates each one on available funds will then
 * reject a transfer the leader had already authorised, drop it, and diverge from
 * the leader on those accounts forever, even though the global total stays
 * conserved.
 *
 * <p>{@link ReplConsistency} cannot catch this because its accounts start with a
 * balance so large relative to the transfer amounts that a follower never sees
 * an insufficient balance. This check uses deliberately tight balances and
 * amounts up to the full balance, so the inversion path is exercised, and then
 * asserts the PER-ACCOUNT balance leader == replica for EVERY account. It fails
 * against a follower that replays with the funds-checking {@code move} and passes
 * once the follower force-applies authorised transfers with {@code settle}.
 *
 * <pre>
 * mvn -q -DskipTests package
 * java -cp target/test-classes:target/tesoreria-distribuida-jar-with-dependencies.jar \
 *     mx.ipn.escom.tesoreria.tests.ReplInversion
 * </pre>
 *
 * It prints {@code REPL_INVERSION_OK} and exits 0 on success.
 */
public final class ReplInversion {

    // Defaults chosen to actually exercise the inversion: a small shared pool of
    // accounts with tight balances under heavy contention. With the funds-checking
    // path (the bug) this leaves the follower diverged; with force-apply it
    // converges. Override via args to sweep.
    private static final int PORT = 9098;
    private static int ACCOUNTS = 8;
    private static long START_CENTS = 50L;
    private static int THREADS = 16;
    private static int PER_THREAD = 12000;

    private ReplInversion() {
    }

    /**
     * Runs the tight-balance load and the per-account comparison. Optional args
     * override the defaults for sweeping: accounts startCents threads perThread.
     *
     * @param args optional {accounts, startCents, threads, perThread}
     * @throws Exception if the socket wiring or threads fail fatally
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            ACCOUNTS = Integer.parseInt(args[0]);
        }
        if (args.length > 1) {
            START_CENTS = Long.parseLong(args[1]);
        }
        if (args.length > 2) {
            THREADS = Integer.parseInt(args[2]);
        }
        if (args.length > 3) {
            PER_THREAD = Integer.parseInt(args[3]);
        }
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

        System.out.println("committed transfers:  " + ok.get());
        System.out.println("watermark reached:    " + reached + " (seq " + replica.lastSeq() + "/" + target + ")");
        System.out.println("applied equal:        " + appliedEq
                + " (leader=" + leader.appliedCount() + " replica=" + replica.appliedCount() + ")");
        System.out.println("per-account matches:  " + (mismatches == 0) + " (mismatches=" + mismatches + ")");
        System.out.println("totals conserved:     " + totalsOk);

        sync.stop();
        feed.stop();
        boolean pass = reached && appliedEq && mismatches == 0 && totalsOk;
        System.out.println(pass ? "REPL_INVERSION_OK" : "REPL_INVERSION_FAIL");
        System.exit(pass ? 0 : 1);
    }

    /** One writer thread: random transfers between distinct accounts, amounts up to the full balance. */
    private static void hammer(Bank leader, AtomicLong ok) {
        for (int n = 0; n < PER_THREAD; n++) {
            int from = ThreadLocalRandom.current().nextInt(1, ACCOUNTS + 1);
            int to = ThreadLocalRandom.current().nextInt(1, ACCOUNTS + 1);
            if (from == to) {
                continue;
            }
            try {
                leader.transfer(from, to, ThreadLocalRandom.current().nextLong(1, START_CENTS));
                ok.incrementAndGet();
            } catch (TransferException ignored) {
                // low_balance is expected under tight-balance load; not a failure
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
