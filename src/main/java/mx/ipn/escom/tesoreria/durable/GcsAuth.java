package mx.ipn.escom.tesoreria.durable;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Mints OAuth2 access tokens for the Cloud Storage JSON API using the
 * service-account key-file flow (practica 28 style), with no Google SDK.
 *
 * It reads the service account JSON (client_email, private_key, token_uri),
 * builds and self-signs a JWT assertion with RS256 (SHA256withRSA over a
 * PKCS8 private key, Base64 URL encoded), POSTs it with the
 * urn:ietf:params:oauth:grant-type:jwt-bearer grant to the token URI, and
 * caches the returned access_token until shortly before it expires. The VM
 * metadata server is deliberately NOT used.
 */
public final class GcsAuth {

    /** OAuth2 scope granting read/write access to Cloud Storage objects. */
    private static final String SCOPE = "https://www.googleapis.com/auth/devstorage.read_write";

    /** Assertion grant type required by the OAuth2 token endpoint. */
    private static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer";

    /** Default token endpoint used when the key file omits token_uri. */
    private static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";

    /** Lifetime in seconds requested for each minted assertion (max 3600). */
    private static final long TOKEN_TTL_SECONDS = 3600L;

    /** Safety margin: refresh this many seconds before the cached token expires. */
    private static final long REFRESH_SKEW_SECONDS = 60L;

    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newHttpClient();

    /** Service account email used as the JWT issuer. */
    private final String clientEmail;

    /** OAuth2 token endpoint to POST the signed assertion to. */
    private final String tokenUri;

    /** RSA private key parsed from the service account PEM, used to sign assertions. */
    private final PrivateKey privateKey;

    /** Currently cached access token, or null if none has been minted yet. */
    private String cachedToken;

    /** Epoch second at which the cached token must be considered stale. */
    private long cachedExpiry;

    /**
     * Loads and parses a service account JSON key file from the given path.
     *
     * @param keyFile path to the TES_GCS_KEYFILE service account credentials
     */
    public GcsAuth(Path keyFile) {
        try {
            String json = Files.readString(keyFile, StandardCharsets.UTF_8);
            JsonObject sa = gson.fromJson(json, JsonObject.class);
            this.clientEmail = sa.get("client_email").getAsString();
            this.tokenUri = sa.has("token_uri")
                    ? sa.get("token_uri").getAsString()
                    : DEFAULT_TOKEN_URI;
            this.privateKey = parsePrivateKey(sa.get("private_key").getAsString());
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            throw new IllegalStateException("cannot load GCS service-account key from " + keyFile, e);
        }
    }

    /**
     * Returns a valid bearer access token, minting a fresh one when the cache
     * is empty or close to expiry.
     *
     * @return a non-null OAuth2 access token
     * @throws IOException          if the token endpoint cannot be reached
     * @throws InterruptedException if the HTTP exchange is interrupted
     */
    public synchronized String accessToken() throws IOException, InterruptedException {
        long now = Instant.now().getEpochSecond();
        if (cachedToken != null && now < cachedExpiry - REFRESH_SKEW_SECONDS) {
            return cachedToken;
        }
        refresh();
        return cachedToken;
    }

    /**
     * Builds a signed assertion, exchanges it for an access token and updates
     * the cache.
     *
     * @throws IOException          if the exchange fails or returns non-2xx
     * @throws InterruptedException if the HTTP exchange is interrupted
     */
    private void refresh() throws IOException, InterruptedException {
        String assertion = buildAssertion();
        String form = "grant_type=" + enc(GRANT_TYPE) + "&assertion=" + enc(assertion);
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUri))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("token endpoint returned " + response.statusCode()
                    + ": " + response.body());
        }
        JsonObject body = gson.fromJson(response.body(), JsonObject.class);
        cachedToken = body.get("access_token").getAsString();
        long expiresIn = body.has("expires_in")
                ? body.get("expires_in").getAsLong()
                : TOKEN_TTL_SECONDS;
        cachedExpiry = Instant.now().getEpochSecond() + expiresIn;
    }

    /**
     * Assembles and RS256-signs the JWT assertion (header.payload.signature).
     * The claim set is the canonical service-account jwt-bearer one: iss, scope,
     * aud, iat, exp. No sub is sent (that is only for domain-wide delegation and
     * would be rejected as unauthorized_client here).
     *
     * @return the compact serialized JWT assertion string
     */
    private String buildAssertion() {
        long now = Instant.now().getEpochSecond();
        JsonObject header = new JsonObject();
        header.addProperty("alg", "RS256");
        header.addProperty("typ", "JWT");
        JsonObject claims = new JsonObject();
        claims.addProperty("iss", clientEmail);
        claims.addProperty("scope", SCOPE);
        claims.addProperty("aud", tokenUri);
        claims.addProperty("iat", now);
        claims.addProperty("exp", now + TOKEN_TTL_SECONDS);
        String signingInput = base64UrlNoPad(header.toString().getBytes(StandardCharsets.UTF_8))
                + "." + base64UrlNoPad(claims.toString().getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + sign(signingInput);
    }

    /**
     * Produces the RS256 signature of the JWT signing input.
     *
     * @param signingInput the "header.payload" segment to sign
     * @return the Base64 URL (no padding) encoded signature
     */
    private String sign(String signingInput) {
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return base64UrlNoPad(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot sign JWT assertion", e);
        }
    }

    /**
     * Parses an unencrypted PKCS8 RSA private key from PEM text.
     *
     * @param pem the private_key field including BEGIN/END markers
     * @return the decoded RSA {@link PrivateKey}
     * @throws GeneralSecurityException if the key cannot be reconstructed
     */
    private static PrivateKey parsePrivateKey(String pem) throws GeneralSecurityException {
        String body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(body);
        KeyFactory factory = KeyFactory.getInstance("RSA");
        return factory.generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /**
     * Base64 URL encodes without padding, as required by JWT.
     *
     * @param data raw bytes to encode
     * @return the URL-safe, unpadded Base64 string
     */
    private static String base64UrlNoPad(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    /**
     * Percent-encodes a value for an x-www-form-urlencoded body.
     *
     * @param value the raw value to encode
     * @return the URL-encoded value
     */
    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
