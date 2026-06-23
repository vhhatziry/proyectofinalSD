package mx.ipn.escom.tesoreria.tests;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import mx.ipn.escom.tesoreria.net.Request;
import mx.ipn.escom.tesoreria.net.RequestParser;
import mx.ipn.escom.tesoreria.security.Authenticator;
import mx.ipn.escom.tesoreria.security.CredentialStore;
import mx.ipn.escom.tesoreria.security.Tokens;
import mx.ipn.escom.tesoreria.tests.RunTests.Case;

/**
 * Suite for {@link mx.ipn.escom.tesoreria.security.Authenticator} authorization.
 *
 * <p>The Bearer auth-scheme is case-insensitive per RFC 7235 and surrounding or
 * internal whitespace must be tolerated, so {@code "bearer x"}, {@code "BEARER x"}
 * and {@code "Bearer  x"} all authenticate the same valid token, while a missing
 * header, the wrong scheme, an empty token or a bogus token are rejected.
 */
public final class AuthTest {

    /** Shared secret used to mint and validate the test token. */
    private final Tokens tokens = new Tokens("test-secret");

    /** Authenticator under test, wired to the same token service. */
    private final Authenticator auth = new Authenticator(new CredentialStore(), tokens);

    /** A valid token whose subject is "alice". */
    private final String token = tokens.issue("alice");

    /**
     * Builds the list of cases contributed by this suite.
     *
     * @return ordered list of named cases for {@link RunTests}
     */
    public List<Case> cases() {
        return List.of(
                new Case("Authenticator accepts canonical Bearer", this::acceptsCanonical),
                new Case("Authenticator accepts lower/upper case scheme", this::acceptsAnyCase),
                new Case("Authenticator tolerates extra whitespace", this::toleratesWhitespace),
                new Case("Authenticator rejects missing/wrong/empty/bogus", this::rejectsBad));
    }

    /** A valid canonical "Bearer <token>" authorizes to the subject. */
    private void acceptsCanonical() {
        Assert.equals("canonical", "alice", auth.authorize(get("Bearer " + token)));
    }

    /** The scheme is case-insensitive. */
    private void acceptsAnyCase() {
        Assert.equals("lowercase", "alice", auth.authorize(get("bearer " + token)));
        Assert.equals("uppercase", "alice", auth.authorize(get("BEARER " + token)));
    }

    /** Extra surrounding and internal whitespace is tolerated. */
    private void toleratesWhitespace() {
        Assert.equals("double space", "alice", auth.authorize(get("Bearer  " + token)));
        Assert.equals("surrounding", "alice", auth.authorize(get("  Bearer " + token + "  ")));
    }

    /** Missing header, wrong scheme, empty token and a bogus token are rejected. */
    private void rejectsBad() {
        Assert.isTrue("missing header", auth.authorize(get(null)) == null);
        Assert.isTrue("wrong scheme", auth.authorize(get("Basic " + token)) == null);
        Assert.isTrue("empty token", auth.authorize(get("Bearer")) == null);
        Assert.isTrue("bogus token", auth.authorize(get("Bearer not.a.valid.token")) == null);
    }

    /** Builds a parsed GET request carrying the given Authorization header (or none). */
    private Request get(String authHeader) {
        String raw = "GET /api/accounts/1 HTTP/1.1\r\nHost: x\r\n"
                + (authHeader == null ? "" : "Authorization: " + authHeader + "\r\n")
                + "Content-Length: 0\r\n\r\n";
        RequestParser parser = new RequestParser();
        parser.feed(ByteBuffer.wrap(raw.getBytes(StandardCharsets.US_ASCII)));
        return parser.take();
    }
}
