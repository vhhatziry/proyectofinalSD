package mx.ipn.escom.tesoreria.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

/**
 * Instance-based JWT minter and verifier for the "tesoreria" issuer.
 * Constructed with the shared HMAC secret (TES_JWT_SECRET); uses HMAC256.
 */
public final class Tokens {

    private static final String ISSUER = "tesoreria";

    private final Algorithm algorithm;
    private final JWTVerifier verifier;

    /**
     * Builds a token service bound to the given HMAC secret.
     *
     * @param secret the shared JWT signing secret
     */
    public Tokens(String secret) {
        this.algorithm = Algorithm.HMAC256(secret);
        this.verifier = JWT.require(algorithm).withIssuer(ISSUER).build();
    }

    /**
     * Issues a signed token whose subject is the given identity.
     *
     * @param subject the token subject (e.g. the username)
     * @return the signed compact JWT string
     */
    public String issue(String subject) {
        return JWT.create()
                .withIssuer(ISSUER)
                .withSubject(subject)
                .sign(algorithm);
    }

    /**
     * Validates a token and returns its subject.
     *
     * @param token the compact JWT string
     * @return the subject carried by the token
     * @throws JWTVerificationException if the signature, issuer, or claims are invalid
     */
    public String validate(String token) {
        DecodedJWT decoded = verifier.verify(token);
        return decoded.getSubject();
    }
}
