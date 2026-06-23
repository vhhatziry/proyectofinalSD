package mx.ipn.escom.tesoreria.api;

import com.google.gson.Gson;

import mx.ipn.escom.tesoreria.app.NodeStats;
import mx.ipn.escom.tesoreria.net.Endpoint;
import mx.ipn.escom.tesoreria.net.Reply;
import mx.ipn.escom.tesoreria.net.Request;

/**
 * Handles GET /api/stats: reports the metrics of THIS node.
 * Serializes the live {@link NodeStats} snapshot (account count, total balance,
 * transfer count, last transaction id, %CPU, %RAM, %Disk, and the number of
 * transactions stored in Cloud Storage) as JSON for the dashboard. Returns 405
 * on a wrong HTTP method.
 */
public final class StatsEndpoint implements Endpoint {

    /** Shared JSON codec for serializing the node snapshot. */
    private final Gson gson = new Gson();

    /** Source of this node's live metrics. */
    private final NodeStats stats;

    /**
     * Builds the endpoint over this node's metrics source.
     *
     * @param stats the live metrics for the local node
     */
    public StatsEndpoint(NodeStats stats) {
        this.stats = stats;
    }

    /**
     * Validates the method and serializes the current node snapshot.
     *
     * @param req the incoming request
     * @return a {@link Reply} with the node metrics as JSON
     */
    @Override
    public Reply serve(Request req) {
        // TODO: enforce GET (405 otherwise); read a fresh snapshot from NodeStats and
        // return 200 with its JSON serialization.
        throw new UnsupportedOperationException("TODO");
    }
}
