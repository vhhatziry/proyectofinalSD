package mx.ipn.escom.tesoreria.api;

import java.net.http.HttpClient;

import com.google.gson.Gson;

import mx.ipn.escom.tesoreria.app.NodeConfig;
import mx.ipn.escom.tesoreria.app.NodeStats;
import mx.ipn.escom.tesoreria.net.Endpoint;
import mx.ipn.escom.tesoreria.net.Reply;
import mx.ipn.escom.tesoreria.net.Request;

/**
 * Handles GET /panel: aggregates metrics for the whole cluster.
 * Combines this node's {@link NodeStats} with the stats fetched over HTTP from
 * each peer listed in TES_PEERS ({@link NodeConfig}), producing a single JSON
 * document with one entry per node so the dashboard can render the cluster in a
 * single page. Unreachable peers are reported as down rather than failing the
 * whole response. Returns 405 on a wrong HTTP method.
 */
public final class PanelEndpoint implements Endpoint {

    /** Shared JSON codec for building the aggregated reply. */
    private final Gson gson = new Gson();

    /** HTTP client used to pull each peer's /api/stats over the wire. */
    private final HttpClient http = HttpClient.newHttpClient();

    /** This node's live metrics. */
    private final NodeStats stats;

    /** Node configuration providing the peer list (TES_PEERS). */
    private final NodeConfig config;

    /**
     * Builds the endpoint over this node's metrics and configuration.
     *
     * @param stats  the live metrics for the local node
     * @param config the node configuration carrying the peer addresses
     */
    public PanelEndpoint(NodeStats stats, NodeConfig config) {
        this.stats = stats;
        this.config = config;
    }

    /**
     * Validates the method, gathers the local snapshot and each peer's snapshot,
     * and serializes the cluster view.
     *
     * @param req the incoming request
     * @return a {@link Reply} with one stats entry per node as JSON
     */
    @Override
    public Reply serve(Request req) {
        // TODO: enforce GET (405 otherwise); build an array starting with the local
        // NodeStats snapshot, then for each peer in config.peers() GET /api/stats with
        // the HttpClient (marking unreachable peers as down); return 200 with the
        // aggregated JSON.
        throw new UnsupportedOperationException("TODO");
    }
}
