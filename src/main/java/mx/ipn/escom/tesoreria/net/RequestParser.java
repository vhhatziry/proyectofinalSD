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
        while (state != State.DONE && in.hasRemaining()) {
            if (state == State.READ_BODY) {
                int n = Math.min(remainingBody, in.remaining());
                for (int i = 0; i < n; i++) {
                    bodyBuf.write(in.get());
                }
                remainingBody -= n;
                if (remainingBody == 0) {
                    buildRequest();
                }
                continue;
            }
            // READ_LINE / READ_HEADERS both accumulate a CRLF-terminated line.
            byte b = in.get();
            if (b == '\n') {
                String line = takeLine();
                if (state == State.READ_LINE) {
                    parseRequestLine(line);
                    state = State.READ_HEADERS;
                } else if (line.isEmpty()) {
                    remainingBody = contentLength();
                    if (remainingBody > 0) {
                        state = State.READ_BODY;
                    } else {
                        buildRequest();
                    }
                } else {
                    parseHeader(line);
                }
            } else {
                lineBuf.write(b);
            }
        }
        return state == State.DONE;
    }

    /** Returns the accumulated line (without the trailing CR) and clears the buffer. */
    private String takeLine() {
        String raw = lineBuf.toString(java.nio.charset.StandardCharsets.US_ASCII);
        lineBuf.reset();
        if (raw.endsWith("\r")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        return raw;
    }

    /** Parses "METHOD target HTTP/x.y", splitting the target into path and query. */
    private void parseRequestLine(String line) {
        String[] parts = line.split(" ");
        method = (parts.length > 0) ? parts[0].toUpperCase() : "GET";
        String target = (parts.length > 1) ? parts[1] : "/";
        version = (parts.length > 2) ? parts[2] : "HTTP/1.0";
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            query = target.substring(q + 1);
        } else {
            path = target;
            query = "";
        }
    }

    /** Splits a header line at the first colon and records the lowercased name. */
    private void parseHeader(String line) {
        int colon = line.indexOf(':');
        if (colon > 0) {
            addHeader(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
    }

    /** Content-Length as an int, or 0 when absent or malformed. */
    private int contentLength() {
        List<String> values = headers.get("content-length");
        if (values == null || values.isEmpty()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(values.get(0).trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Materializes the parsed pieces into an immutable Request and marks DONE. */
    private void buildRequest() {
        completed = new Request(method, path, query,
                new HashMap<>(headers), bodyBuf.toByteArray(), computeKeepAlive());
        state = State.DONE;
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
        if (completed == null) {
            return null;
        }
        Request ready = completed;
        reset();
        return ready;
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
        List<String> connection = headers.get("connection");
        String value = (connection == null || connection.isEmpty())
                ? null : connection.get(0).trim();
        if (value != null) {
            if (value.equalsIgnoreCase("close")) {
                return false;
            }
            if (value.equalsIgnoreCase("keep-alive")) {
                return true;
            }
        }
        // No explicit token: HTTP/1.1 keeps the connection alive, older does not.
        return "HTTP/1.1".equalsIgnoreCase(version);
    }

    /** Placeholder for accumulating a parsed header value. */
    private void addHeader(String name, String value) {
        headers.computeIfAbsent(name.toLowerCase(), k -> new ArrayList<>()).add(value);
    }
}
