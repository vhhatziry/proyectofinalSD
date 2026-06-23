package mx.ipn.escom.tesoreria.tests;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import mx.ipn.escom.tesoreria.net.Request;
import mx.ipn.escom.tesoreria.net.RequestParser;
import mx.ipn.escom.tesoreria.tests.RunTests.Case;

/**
 * Suite for {@link mx.ipn.escom.tesoreria.net.RequestParser}.
 *
 * <p>Exercises the stateful HTTP/1.1 parser: a complete request delivered in a
 * single buffer is parsed into method, path and body, and a request that arrives
 * in two pieces (request-line plus headers first, body afterwards) is assembled
 * across feeds without losing or duplicating bytes.
 */
public final class RequestParserTest {

    /**
     * Builds the list of cases contributed by this suite.
     *
     * @return ordered list of named cases for {@link RunTests}
     */
    public List<Case> cases() {
        return List.of(
                new Case("RequestParser parses a complete request in one feed",
                        this::parsesCompleteRequest),
                new Case("RequestParser assembles a request split across feeds",
                        this::assemblesSplitRequest));
    }

    /**
     * Feeds the bytes of one complete request and asserts the parser reaches its
     * terminal state exposing the parsed method, path and body.
     */
    private void parsesCompleteRequest() {
        String body = "hello, world!";
        String raw = "POST /api/transactions/transfer HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Length: " + body.length() + "\r\n"
                + "Connection: close\r\n\r\n"
                + body;
        RequestParser parser = new RequestParser();
        boolean done = parser.feed(ByteBuffer.wrap(raw.getBytes(StandardCharsets.US_ASCII)));
        Assert.isTrue("parser reached DONE in one feed", done);
        Request request = parser.take();
        Assert.notNull("a request is available", request);
        Assert.equals("method", "POST", request.method());
        Assert.equals("path", "/api/transactions/transfer", request.path());
        Assert.equals("body", body, request.bodyText());
    }

    /**
     * Feeds the request-line and headers first, asserts the parser is not yet
     * complete, then feeds the body and asserts completion with the body intact.
     */
    private void assemblesSplitRequest() {
        String body = "abcde";
        String head = "POST /x HTTP/1.1\r\nContent-Length: " + body.length() + "\r\n\r\n";
        RequestParser parser = new RequestParser();
        boolean doneAfterHead = parser.feed(ByteBuffer.wrap(head.getBytes(StandardCharsets.US_ASCII)));
        Assert.isTrue("not complete with only head fed", !doneAfterHead);
        boolean doneAfterBody = parser.feed(ByteBuffer.wrap(body.getBytes(StandardCharsets.US_ASCII)));
        Assert.isTrue("complete once body is fed", doneAfterBody);
        Request request = parser.take();
        Assert.notNull("a request is available", request);
        Assert.equals("reassembled body", body, request.bodyText());
    }
}
