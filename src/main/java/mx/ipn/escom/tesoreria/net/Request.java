package mx.ipn.escom.tesoreria.net;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Immutable view of a single parsed HTTP request handed from the parser to an
 * Endpoint. Headers are stored as supplied; lookups by name are
 * case-insensitive. Path parameters (such as {id}) are filled in by the router
 * after a successful match.
 */
public final class Request {

    private final String method;
    private final String path;
    private final String query;
    private final Map<String, List<String>> headers;
    private final byte[] body;
    private final boolean keepAlive;
    private Map<String, String> pathParams;

    /**
     * Builds a request. The headers map is expected to be keyed by lowercase
     * header name so that header(name) can resolve case-insensitively.
     */
    public Request(String method,
                   String path,
                   String query,
                   Map<String, List<String>> headers,
                   byte[] body,
                   boolean keepAlive) {
        this.method = method;
        this.path = path;
        this.query = query;
        this.headers = headers;
        this.body = body != null ? body : new byte[0];
        this.keepAlive = keepAlive;
        this.pathParams = Map.of();
    }

    /** Request method, upper-cased (GET, POST, ...). */
    public String method() {
        return method;
    }

    /** Request path without the query string (for example /api/balance/7). */
    public String path() {
        return path;
    }

    /** Raw query string after '?', or empty when there is none. */
    public String query() {
        return query;
    }

    /** First value of the named header, case-insensitive, or null if absent. */
    public String header(String name) {
        // TODO: lookup by name.toLowerCase() in the headers map.
        throw new UnsupportedOperationException("TODO");
    }

    /** Raw request body bytes (never null; empty array when no body). */
    public byte[] body() {
        return body;
    }

    /** Body decoded as UTF-8 text. */
    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /** Value of a route variable captured during matching (for example id). */
    public String pathParam(String name) {
        return pathParams.get(name);
    }

    /**
     * Installs the path parameters extracted by the router. Called once by
     * Routes immediately after a successful match, before the Endpoint runs.
     */
    void bindPathParams(Map<String, String> params) {
        this.pathParams = params != null ? params : Map.of();
    }

    /**
     * True when the connection should be closed after replying (HTTP/1.0
     * without keep-alive, or an explicit Connection: close header).
     */
    public boolean wantsClose() {
        return !keepAlive;
    }
}
