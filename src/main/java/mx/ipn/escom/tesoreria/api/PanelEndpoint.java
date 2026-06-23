package mx.ipn.escom.tesoreria.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

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

    /**
     * HTTP client used to pull each peer's /api/stats over the wire. A bounded
     * connect timeout is essential: a host that drops connection attempts (a
     * downed replica behind a firewall) would otherwise hang the panel well past
     * the per-request timeout, which only covers the response phase.
     */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(800))
            .build();

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
        if (!"GET".equals(req.method())) {
            return Reply.status(405);
        }
        // Fetch every peer concurrently so a downed replica adds at most one
        // timeout to the panel latency, not one timeout per peer.
        List<CompletableFuture<String>> pending = new ArrayList<>();
        for (String peer : peerBaseUrls()) {
            pending.add(fetchPeer(peer));
        }
        StringBuilder nodes = new StringBuilder();
        nodes.append("{\"nodes\":[").append(stats.toJson());
        for (CompletableFuture<String> peer : pending) {
            nodes.append(',').append(peer.join());
        }
        nodes.append("]}");
        return Reply.json(200, nodes.toString());
    }

    /** Splits TES_PEERS into trimmed, non-empty base URLs. */
    private String[] peerBaseUrls() {
        String raw = config.peers();
        if (raw == null || raw.isBlank()) {
            return new String[0];
        }
        String[] parts = raw.split(",");
        int count = 0;
        for (String part : parts) {
            if (!part.trim().isEmpty()) {
                parts[count++] = part.trim();
            }
        }
        String[] result = new String[count];
        System.arraycopy(parts, 0, result, 0, count);
        return result;
    }

    /**
     * Pulls a peer's own stats over HTTP. An unreachable or slow peer never
     * fails the panel: it is reported as a down node instead.
     */
    private CompletableFuture<String> fetchPeer(String base) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/stats"))
                    .timeout(Duration.ofMillis(1500))
                    .GET()
                    .build();
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .handle((response, error) ->
                            (error == null && response.statusCode() == 200)
                                    ? response.body()
                                    : downNode(base));
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(downNode(base));
        }
    }

    /** A placeholder snapshot for a node that did not answer. */
    private String downNode(String base) {
        JsonObject node = new JsonObject();
        node.addProperty("nodeId", base);
        node.addProperty("role", "replica");
        node.addProperty("status", "down");
        node.addProperty("accountCount", 0);
        node.addProperty("totalBalance", 0);
        node.addProperty("transferCount", 0);
        node.addProperty("lastTxId", 0);
        node.addProperty("cpuPercent", 0);
        node.addProperty("ramPercent", 0);
        node.addProperty("diskPercent", 0);
        node.addProperty("gcsCount", 0);
        return gson.toJson(node);
    }
}
