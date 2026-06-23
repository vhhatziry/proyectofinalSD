package mx.ipn.escom.tesoreria.tests;

import java.util.List;

import mx.ipn.escom.tesoreria.tests.RunTests.Case;

/**
 * Suite (skeleton) for {@link mx.ipn.escom.tesoreria.net.RequestParser}.
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
     * terminal state exposing the parsed method, path and body. (Skeleton.)
     */
    private void parsesCompleteRequest() {
        // TODO: feed a full "POST /api/... HTTP/1.1" request with a body and
        // assert the parser completes and exposes method/path/body correctly.
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * Feeds the request-line and headers first, asserts the parser is not yet
     * complete, then feeds the body and asserts completion with the body intact.
     * (Skeleton.)
     */
    private void assemblesSplitRequest() {
        // TODO: feed head bytes, assert not done; feed remaining body bytes,
        // assert it completes and the reassembled body matches the original.
        throw new UnsupportedOperationException("TODO");
    }
}
