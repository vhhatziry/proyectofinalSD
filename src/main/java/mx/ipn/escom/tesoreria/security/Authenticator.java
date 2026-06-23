package mx.ipn.escom.tesoreria.security;

import mx.ipn.escom.tesoreria.net.Request;

/**
 * Coordinates signup, login, and request authorization.
 * Wires a CredentialStore (who exists) to a Tokens service (proof of identity).
 */
public final class Authenticator {

    private static final String SCHEME = "Bearer";

    private final CredentialStore store;
    private final Tokens tokens;

    /**
     * Builds an authenticator over an injected credential store and token service.
     *
     * @param store  the credential registry
     * @param tokens the JWT service
     */
    public Authenticator(CredentialStore store, Tokens tokens) {
        this.store = store;
        this.tokens = tokens;
    }

    /**
     * Registers a new account by hashing the password and storing the credential.
     *
     * @param username the desired username
     * @param password the raw password
     * @return true if registered, false if the username was taken
     */
    public boolean signup(String username, String password) {
        String hash = Passwords.encode(password);
        return store.register(username, hash);
    }

    /**
     * Authenticates a username/password pair and mints a token on success.
     *
     * @param username the username
     * @param password the raw password
     * @return a signed JWT if the credentials match, otherwise null
     */
    public String login(String username, String password) {
        Credential credential = store.find(username);
        if (credential == null) {
            return null;
        }
        if (!Passwords.matches(password, credential.passwordHash())) {
            return null;
        }
        return tokens.issue(username);
    }

    /**
     * Authorizes a request by validating its Bearer token. Per RFC 7235 the
     * auth-scheme is case-insensitive, so {@code "bearer"}, {@code "BEARER"} and
     * {@code "Bearer"} are all accepted, and any extra whitespace around or
     * between the scheme and the token is tolerated.
     *
     * @param req the incoming request
     * @return the authenticated subject, or null if the token is missing or invalid
     */
    public String authorize(Request req) {
        String header = req.header("Authorization");
        if (header == null) {
            return null;
        }
        String[] parts = header.trim().split("\\s+", 2);
        if (parts.length < 2 || !SCHEME.equalsIgnoreCase(parts[0])) {
            return null;
        }
        String token = parts[1].trim();
        if (token.isEmpty()) {
            return null;
        }
        try {
            return tokens.validate(token);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
