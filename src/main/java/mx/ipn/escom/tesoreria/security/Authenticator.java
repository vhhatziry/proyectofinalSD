package mx.ipn.escom.tesoreria.security;

import mx.ipn.escom.tesoreria.net.Request;

/**
 * Coordinates signup, login, and request authorization.
 * Wires a CredentialStore (who exists) to a Tokens service (proof of identity).
 */
public final class Authenticator {

    private static final String BEARER_PREFIX = "Bearer ";

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
        // TODO: hash with Passwords.encode and delegate to store.register
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * Authenticates a username/password pair and mints a token on success.
     *
     * @param username the username
     * @param password the raw password
     * @return a signed JWT if the credentials match, otherwise null
     */
    public String login(String username, String password) {
        // TODO: find credential, verify with Passwords.matches, issue token via tokens.issue
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * Authorizes a request by validating its Bearer token.
     *
     * @param req the incoming request
     * @return the authenticated subject, or null if the token is missing or invalid
     */
    public String authorize(Request req) {
        // TODO: read "Authorization" header, strip BEARER_PREFIX, tokens.validate; return null on failure
        throw new UnsupportedOperationException("TODO");
    }
}
