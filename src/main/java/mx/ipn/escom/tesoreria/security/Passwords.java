package mx.ipn.escom.tesoreria.security;

import org.mindrot.jbcrypt.BCrypt;

/**
 * Stateless password hashing helper backed by jBCrypt.
 * Hides the salt generation and constant-time comparison behind two static methods.
 */
public final class Passwords {

    private Passwords() {
    }

    /**
     * Hashes a raw password with a freshly generated BCrypt salt.
     *
     * @param raw the plaintext password
     * @return the BCrypt hash string (salt embedded)
     */
    public static String encode(String raw) {
        return BCrypt.hashpw(raw, BCrypt.gensalt());
    }

    /**
     * Verifies a raw password against a previously stored BCrypt hash.
     *
     * @param raw  the plaintext password to check
     * @param hash the stored BCrypt hash
     * @return true if the password matches the hash
     */
    public static boolean matches(String raw, String hash) {
        return BCrypt.checkpw(raw, hash);
    }
}
