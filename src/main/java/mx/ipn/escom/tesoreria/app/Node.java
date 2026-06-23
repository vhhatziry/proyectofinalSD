package mx.ipn.escom.tesoreria.app;

import java.nio.file.Path;

import mx.ipn.escom.tesoreria.api.BalanceEndpoint;
import mx.ipn.escom.tesoreria.api.DashboardEndpoint;
import mx.ipn.escom.tesoreria.api.LoginEndpoint;
import mx.ipn.escom.tesoreria.api.PanelEndpoint;
import mx.ipn.escom.tesoreria.api.RegisterEndpoint;
import mx.ipn.escom.tesoreria.api.StatsEndpoint;
import mx.ipn.escom.tesoreria.api.TransferEndpoint;
import mx.ipn.escom.tesoreria.cluster.ReplicaFeed;
import mx.ipn.escom.tesoreria.cluster.ReplicaSync;
import mx.ipn.escom.tesoreria.core.Bank;
import mx.ipn.escom.tesoreria.core.Dataset;
import mx.ipn.escom.tesoreria.core.Ledger;
import mx.ipn.escom.tesoreria.durable.GcsAuth;
import mx.ipn.escom.tesoreria.durable.GcsStore;
import mx.ipn.escom.tesoreria.durable.Journal;
import mx.ipn.escom.tesoreria.net.Routes;
import mx.ipn.escom.tesoreria.net.Server;
import mx.ipn.escom.tesoreria.security.Authenticator;
import mx.ipn.escom.tesoreria.security.CredentialStore;
import mx.ipn.escom.tesoreria.security.Tokens;

/**
 * Process entry point of a single Tesoreria Distribuida node.
 *
 * <p>On startup it reads {@link NodeConfig}, loads the shared dataset into a
 * fresh {@link Ledger} and wraps it in a {@link Bank}, which owns the commit
 * sequence and the {@link mx.ipn.escom.tesoreria.core.CommitListener} fan-out.
 * It then branches on the node role:
 * <ul>
 *   <li><b>Leader</b> (TES_LEADER_HOST empty): runs the {@link Journal} cold
 *       recovery, then registers two listeners on the bank: the journal, which
 *       durably stores each new transfer, and a {@link ReplicaFeed} TCP server,
 *       which streams live commits out to the connected replicas.</li>
 *   <li><b>Replica</b> (TES_LEADER_HOST set): starts a {@link ReplicaSync} that
 *       catches up from the leader by sequence number and then applies live
 *       commits through the bank.</li>
 * </ul>
 * Finally it wires {@link Routes} + {@link Authenticator} + the API endpoints,
 * starts the {@link Server} and blocks the main thread.
 */
public final class Node {

    private Node() {
    }

    /**
     * Boots a node.
     *
     * @param args optional HTTP port as {@code args[0]}; defaults to 8080
     * @throws Exception if startup fails (config, dataset or network)
     */
    public static void main(String[] args) throws Exception {
        int port = (args.length > 0) ? Integer.parseInt(args[0]) : 8080;

        NodeConfig config = NodeConfig.fromEnv();

        // Load the shared accounts dataset into a brand-new in-memory ledger,
        // then wrap it in the bank that orchestrates transfers and commits.
        Ledger ledger = new Ledger();
        Dataset.loadInto(ledger, Path.of(config.datasetPath()));
        Bank bank = new Bank(ledger);

        Journal journal = null;
        if (config.isLeader()) {
            // Leader path: durable recovery + live replication feed.
            GcsAuth auth = new GcsAuth(Path.of(config.gcsKeyfile()));
            GcsStore store = new GcsStore(auth, config.bucket());
            journal = new Journal(store);
            int recovered = journal.recover(bank::applyReplicated);
            journal.start(recovered);

            bank.addCommitListener(journal);

            ReplicaFeed feed = new ReplicaFeed(config.replPort(), bank.log());
            feed.start();
            // Fan-out: every live commit is pushed to the connected replicas.
            bank.addCommitListener(feed);
        } else {
            // Replica path: catch up from the leader, then apply live commits.
            ReplicaSync sync = new ReplicaSync(config.leaderHost(), config.replPort(), bank);
            sync.start();
        }

        // Security stack shared by the authenticated endpoints.
        Tokens tokens = new Tokens(config.jwtSecret());
        CredentialStore credentials = new CredentialStore();
        Authenticator authenticator = new Authenticator(credentials, tokens);

        // Per-node metrics provider for the stats and panel endpoints.
        NodeStats stats = new NodeStats(ledger, bank, journal, config);

        // Route table: exact contract paths shared by every team.
        Routes routes = new Routes();
        routes.register("POST", "/api/register", new RegisterEndpoint(authenticator));
        routes.register("POST", "/api/login", new LoginEndpoint(authenticator));
        routes.register("GET", "/api/accounts/{id}", new BalanceEndpoint(authenticator, ledger));
        routes.register("POST", "/api/transactions/transfer", new TransferEndpoint(authenticator, bank));
        routes.register("GET", "/api/stats", new StatsEndpoint(stats));
        routes.register("GET", "/panel", new PanelEndpoint(stats, config));
        routes.register("GET", "/", new DashboardEndpoint());

        Server server = new Server(port, routes, config.workers());
        server.start();

        // Block the main thread while the IoLoop and workers serve requests.
        Thread.currentThread().join();
    }
}
