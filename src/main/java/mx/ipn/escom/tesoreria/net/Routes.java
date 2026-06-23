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
        routes.add(new Route(method.toUpperCase(), splitSegments(pattern), endpoint));
    }

    /**
     * Resolves a method and concrete path against the registered routes,
     * extracting any {name} variables on success. See class doc for the
     * 200/404/405 semantics.
     */
    public Match match(String method, String path) {
        String wanted = method.toUpperCase();
        String[] segments = splitSegments(path);
        boolean pathMatchedButMethod = false;

        for (Route route : routes) {
            if (route.segments.length != segments.length) {
                continue;
            }
            Map<String, String> params = new HashMap<>();
            boolean shapeMatches = true;
            for (int i = 0; i < segments.length; i++) {
                String pat = route.segments[i];
                if (isVariable(pat)) {
                    params.put(pat.substring(1, pat.length() - 1), segments[i]);
                } else if (!pat.equals(segments[i])) {
                    shapeMatches = false;
                    break;
                }
            }
            if (!shapeMatches) {
                continue;
            }
            if (route.method.equals(wanted)) {
                return new Match(200, route.endpoint, params);
            }
            pathMatchedButMethod = true;
        }
        return pathMatchedButMethod ? methodNotAllowed() : notFound();
    }

    /** True when a pattern segment is a {name} route variable. */
    private static boolean isVariable(String segment) {
        return segment.length() > 1
                && segment.charAt(0) == '{'
                && segment.charAt(segment.length() - 1) == '}';
    }

    /** Splits a path or pattern into its non-empty '/'-separated segments. */
    private static String[] splitSegments(String path) {
        List<String> segments = new ArrayList<>();
        for (String part : path.split("/")) {
            if (!part.isEmpty()) {
                segments.add(part);
            }
        }
        return segments.toArray(new String[0]);
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
