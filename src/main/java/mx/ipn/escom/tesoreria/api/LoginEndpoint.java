package mx.ipn.escom.tesoreria.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import mx.ipn.escom.tesoreria.net.Endpoint;
import mx.ipn.escom.tesoreria.net.Reply;
import mx.ipn.escom.tesoreria.net.Request;
import mx.ipn.escom.tesoreria.security.Authenticator;

/**
 * Handles POST /api/login: verifies a credential and mints a JWT.
 * Accepts a JSON body shaped like {"username":"...","password":"..."} and, on
 * success, returns 200 with {"token":"<jwt>"}. Returns 401 when the credential
 * does not match, 400 on malformed input and 405 on a wrong HTTP method. The
 * issued token travels back to the client in the Authorization: Bearer header.
 */
public final class LoginEndpoint implements Endpoint {

    /** Shared JSON codec for parsing the request body and building the reply. */
    private final Gson gson = new Gson();

    /** Authentication facade that verifies credentials and issues tokens. */
    private final Authenticator auth;

    /**
     * Builds the endpoint over the node-wide authenticator.
     *
     * @param auth the authenticator that validates credentials and mints tokens
     */
    public LoginEndpoint(Authenticator auth) {
        this.auth = auth;
    }

    /**
     * Validates the method, parses the credential body and logs the user in.
     *
     * @param req the incoming request
     * @return a {@link Reply} with the JWT on success or 401 on failure
     */
    @Override
    public Reply serve(Request req) {
        if (!"POST".equals(req.method())) {
            return Reply.status(405);
        }
        String username;
        String password;
        try {
            JsonObject body = gson.fromJson(req.bodyText(), JsonObject.class);
            username = body.get("username").getAsString();
            password = body.get("password").getAsString();
        } catch (RuntimeException e) {
            return Reply.json(400, "{\"error\":\"bad_request\"}");
        }
        String token = auth.login(username, password);
        if (token == null) {
            return Reply.json(401, "{\"error\":\"invalid_credentials\"}");
        }
        JsonObject out = new JsonObject();
        out.addProperty("token", token);
        return Reply.json(200, gson.toJson(out));
    }
}
