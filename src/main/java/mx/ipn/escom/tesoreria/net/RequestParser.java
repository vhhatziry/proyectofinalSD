package mx.ipn.escom.tesoreria.net;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stateful, incremental HTTP/1.1 request parser. There is ONE instance per
 * connection (held by its Channel). It is fed bytes as they arrive and drives
 * an explicit state machine; it never re-scans a buffer from scratch. When a
 * full request has been consumed it exposes it via take() and resets so the
 * same instance can parse the next pipelined request on a kept-alive socket.
 */
public final class RequestParser {

    /** Phases of consuming one request, in order. */
    public enum State {
        READ_LINE,
        READ_HEADERS,
        READ_BODY,
        DONE
    }

    private State state = State.READ_LINE;

    // Accumulators carried across feed() calls until each phase completes.
    private final ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
    private final ByteArrayOutputStream bodyBuf = new ByteArrayOutputStream();

    // Request-line fields, set when READ_LINE completes.
    private String method;
    private String path;
    private String query;
    private String version;

    // Headers, keyed by lowercase name, accumulated during READ_HEADERS.
    private final Map<String, List<String>> headers = new HashMap<>();

    // Remaining body bytes to read, derived from Content-Length.
    private int remainingBody;

    // Filled when the request becomes complete; cleared by take().
    private Request completed;

    /** Current parser phase. */
    public State state() {
        return state;
    }

    /**
     * Feeds freshly read bytes into the machine, advancing through the states
     * as line terminators and the body length allow. Returns true when a
     * complete request is now available via take(). Leftover bytes belonging
     * to a following pipelined request stay in the supplied buffer.
     */
    public boolean feed(ByteBuffer in) {
        // TODO: loop over the states:
        //   READ_LINE    -> accumulate until CRLF, then parse method/path/query/version.
        //   READ_HEADERS -> accumulate header lines until a blank CRLF line;
        //                   compute remainingBody from Content-Length (0 if absent).
        //   READ_BODY    -> drain up to remainingBody bytes into bodyBuf.
        //   DONE         -> build the Request, store it, return true.
        throw new UnsupportedOperationException("TODO");
    }

    /** True once feed() has assembled a full request not yet taken. */
    public boolean isComplete() {
        return state == State.DONE && completed != null;
    }

    /**
     * Hands off the completed request and resets the machine for the next one
     * on this connection. Returns null if no request is ready.
     */
    public Request take() {
        // TODO: return completed, then call reset() for the next pipelined request.
        throw new UnsupportedOperationException("TODO");
    }

    /** Clears all per-request state so the same instance can parse the next. */
    private void reset() {
        state = State.READ_LINE;
        lineBuf.reset();
        bodyBuf.reset();
        headers.clear();
        method = null;
        path = null;
        query = null;
        version = null;
        remainingBody = 0;
        completed = null;
    }

    /** Decides keep-alive from version and the Connection header. */
    private boolean computeKeepAlive() {
        // TODO: HTTP/1.1 defaults to keep-alive unless Connection: close;
        // HTTP/1.0 needs Connection: keep-alive.
        return true;
    }

    /** Placeholder for accumulating a parsed header value. */
    private void addHeader(String name, String value) {
        headers.computeIfAbsent(name.toLowerCase(), k -> new ArrayList<>()).add(value);
    }
}
