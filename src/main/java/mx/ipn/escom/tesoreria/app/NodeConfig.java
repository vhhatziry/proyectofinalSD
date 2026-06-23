package mx.ipn.escom.tesoreria.app;

/**
 * Reads the node runtime configuration from TES_* environment variables.
 *
 * <p>Every setting of "Tesoreria Distribuida" (Equipo 18) is supplied through
 * environment variables prefixed with {@code TES_}. The role of the node
 * (leader or replica) is inferred from {@code TES_LEADER_HOST}: an empty or
 * absent value means this process is the leader (nodo-1); any other value names
 * the leader host that this replica must follow.
 *
 * <p>The JWT secret ({@code TES_JWT_SECRET}) holds the SAME value on the three
 * nodes so that tokens issued anywhere validate everywhere.
 */
public final class NodeConfig {

    /** Default TCP port of the leader replication feed when TES_REPL_PORT is unset. */
    public static final int DEFAULT_REPL_PORT = 9090;

    /** Default worker pool size when TES_WORKERS is unset. */
    public static final int DEFAULT_WORKERS = 16;

    private final String datasetPath;
    private final String jwtSecret;
    private final String nodeId;
    private final String peers;
    private final String leaderHost;
    private final int replPort;
    private final String bucket;
    private final String gcsKeyfile;
    private final int workers;

    private NodeConfig(String datasetPath, String jwtSecret, String nodeId,
                       String peers, String leaderHost, int replPort,
                       String bucket, String gcsKeyfile, int workers) {
        this.datasetPath = datasetPath;
        this.jwtSecret = jwtSecret;
        this.nodeId = nodeId;
        this.peers = peers;
        this.leaderHost = leaderHost;
        this.replPort = replPort;
        this.bucket = bucket;
        this.gcsKeyfile = gcsKeyfile;
        this.workers = workers;
    }

    /**
     * Builds a configuration by reading every TES_* variable from the process
     * environment, applying defaults where appropriate.
     *
     * @return a populated configuration instance
     */
    public static NodeConfig fromEnv() {
        // TODO: read each TES_* variable via System.getenv and apply defaults.
        throw new UnsupportedOperationException("TODO");
    }

    /** Path to the accounts CSV dataset (TES_DATASET). */
    public String datasetPath() {
        return datasetPath;
    }

    /** Shared HMAC secret used to sign and validate JWTs (TES_JWT_SECRET). */
    public String jwtSecret() {
        return jwtSecret;
    }

    /** Human-readable identifier of this node, e.g. "nodo-1" (TES_NODE_ID). */
    public String nodeId() {
        return nodeId;
    }

    /** Comma-separated peer base URLs used by the panel aggregation (TES_PEERS). */
    public String peers() {
        return peers;
    }

    /** Host of the leader to follow; empty/absent means this node IS the leader (TES_LEADER_HOST). */
    public String leaderHost() {
        return leaderHost;
    }

    /** TCP port of the leader replication feed (TES_REPL_PORT, default 9090). */
    public int replPort() {
        return replPort;
    }

    /** Cloud Storage bucket name for the durable journal (TES_BUCKET). */
    public String bucket() {
        return bucket;
    }

    /** Filesystem path to the service-account key file used for GCS auth (TES_GCS_KEYFILE). */
    public String gcsKeyfile() {
        return gcsKeyfile;
    }

    /** Size of the request worker thread pool (TES_WORKERS, default 16). */
    public int workers() {
        return workers;
    }

    /**
     * Reports whether this node is the leader.
     *
     * @return {@code true} when {@code TES_LEADER_HOST} is null, empty or blank
     */
    public boolean isLeader() {
        return leaderHost == null || leaderHost.isBlank();
    }
}
