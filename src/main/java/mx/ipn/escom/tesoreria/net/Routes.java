package mx.ipn.escom.tesoreria.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Method-and-path router. Patterns are split into segments by '/'. A segment
 * written as {name} is a single-segment variable; every other segment must
 * match by exact string equality (this is exact-segment matching, NOT
 * longest-prefix). On match the captured variables are bound to the Request.
 *
 * Resolution semantics:
 *   - exact method + exact segment shape  -> the registered Endpoint
 *   - some pattern matches the path but no method matches -> 405
 *   - no pattern matches the path at all  -> 404
 */
public final class Routes {

    /** A single registered route: its method, its compiled segments, handler. */
    private static final class Route {
        final String method;
        final String[] segments;
        final Endpoint endpoint;

        Route(String method, String[] segments, Endpoint endpoint) {
            this.method = method;
            this.segments = segments;
            this.endpoint = endpoint;
        }
    }

    /**
     * Outcome of a match attempt. status is 200 when endpoint is non-null,
     * otherwise 404 or 405 with endpoint null and an empty params map.
     */
    public static final class Match {
        public final int status;
        public final Endpoint endpoint;
        public final Map<String, String> params;

        Match(int status, Endpoint endpoint, Map<String, String> params) {
            this.status = status;
            this.endpoint = endpoint;
            this.params = params;
        }
    }

    private final List<Route> routes = new ArrayList<>();

    /**
     * Registers a handler for a method and pattern. The pattern may contain at
     * most one {name} variable segment (for example /api/balance/{id}).
     */
    public void register(String method, String pattern, Endpoint endpoint) {
        // TODO: normalize method to upper-case, split pattern by '/', store Route.
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * Resolves a method and concrete path against the registered routes,
     * extracting any {name} variables on success. See class doc for the
     * 200/404/405 semantics.
     */
    public Match match(String method, String path) {
        // TODO: split path into segments; find routes whose segment shape
        // matches by equality (with {name} matching any single segment);
        // if a shape matches but the method differs -> 405; if a method+shape
        // matches -> 200 with captured params; otherwise -> 404.
        throw new UnsupportedOperationException("TODO");
    }

    /** Convenience holder for a not-found result. */
    private static Match notFound() {
        return new Match(404, null, new HashMap<>());
    }

    /** Convenience holder for a method-not-allowed result. */
    private static Match methodNotAllowed() {
        return new Match(405, null, new HashMap<>());
    }
}
