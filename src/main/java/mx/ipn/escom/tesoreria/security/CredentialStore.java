package mx.ipn.escom.tesoreria.security;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory registry of credentials keyed by username.
 * Registration is atomic: a username can only be claimed once.
 */
public final class CredentialStore {

    private final ConcurrentHashMap<String, Credential> byUsername = new ConcurrentHashMap<>();

    /**
     * Registers a new credential if the username is free.
     *
     * @param username     the unique username
     * @param passwordHash the BCrypt hash for that username
     * @return true if registered, false if the username already existed
     */
    public boolean register(String username, String passwordHash) {
        Credential created = new Credential(username, passwordHash);
        return byUsername.putIfAbsent(username, created) == null;
    }

    /**
     * Looks up a stored credential by username.
     *
     * @param username the username to find
     * @return the credential, or null if none is registered
     */
    public Credential find(String username) {
        return byUsername.get(username);
    }
}
