package mx.ipn.escom.tesoreria.net;

import java.nio.charset.StandardCharsets;

/**
 * The response an Endpoint produces for a Request. A worker thread builds a
 * Reply; the IoLoop later serializes it to bytes and flushes it. Static
 * factories cover the content types this service emits (JSON, plain text,
 * HTML, bare status). close() marks the connection to be dropped after write.
 */
public final class Reply {

    private final int status;
    private final String contentType;
    private final byte[] body;
    private boolean close;

    private Reply(int status, String contentType, byte[] body) {
        this.status = status;
        this.contentType = contentType;
        this.body = body != null ? body : new byte[0];
        this.close = false;
    }

    /** JSON reply with the given status code and already-serialized body. */
    public static Reply json(int status, String json) {
        return new Reply(status, "application/json; charset=utf-8",
                json.getBytes(StandardCharsets.UTF_8));
    }

    /** Plain-text reply. */
    public static Reply text(int status, String text) {
        return new Reply(status, "text/plain; charset=utf-8",
                text.getBytes(StandardCharsets.UTF_8));
    }

    /** HTML reply from raw bytes (for example a page read from resources). */
    public static Reply html(int status, byte[] html) {
        return new Reply(status, "text/html; charset=utf-8", html);
    }

    /** Status-only reply with an empty body. */
    public static Reply status(int status) {
        return new Reply(status, "text/plain; charset=utf-8", new byte[0]);
    }

    /** HTTP status code. */
    public int status() {
        return status;
    }

    /** Value for the Content-Type response header. */
    public String contentType() {
        return contentType;
    }

    /** Response body bytes (never null). */
    public byte[] body() {
        return body;
    }

    /** Requests that the connection be closed once this reply is sent. */
    public Reply close() {
        this.close = true;
        return this;
    }

    /** True when the connection should be closed after this reply. */
    public boolean isClose() {
        return close;
    }
}
