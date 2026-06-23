package mx.ipn.escom.tesoreria.security;

/**
 * Immutable stored credential: a username paired with its BCrypt password hash.
 * Never holds a raw password.
 */
public record Credential(String username, String passwordHash) {
}
