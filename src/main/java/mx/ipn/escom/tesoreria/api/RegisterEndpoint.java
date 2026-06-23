package mx.ipn.escom.tesoreria.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import mx.ipn.escom.tesoreria.net.Endpoint;
import mx.ipn.escom.tesoreria.net.Reply;
import mx.ipn.escom.tesoreria.net.Request;
import mx.ipn.escom.tesoreria.security.Authenticator;

/**
 * Handles POST /api/register: creates a new user credential.
 * Accepts a JSON body shaped like {"username":"...","password":"..."} and
 * delegates persistence to the {@link Authenticator}. Returns 201 on success,
 * 409 if the username is already taken, 400 on malformed input and 405 on a
 * wrong HTTP method.
 */
public final class RegisterEndpoint implements Endpoint {

    /** Shared JSON codec for parsing the request body. */
    private final Gson gson = new Gson();

    /** Authentication facade that owns the credential store. */
    private final Authenticator auth;

    /**
     * Builds the endpoint over the node-wide authenticator.
     *
     * @param auth the authenticator that registers and verifies credentials
     */
    public RegisterEndpoint(Authenticator auth) {
        this.auth = auth;
    }

    /**
     * Validates the method, parses the credential body and signs the user up.
     *
     * @param req the incoming request
     * @return a {@link Reply} carrying the appropriate HTTP status
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
        if (username.isBlank() || password.isBlank()) {
            return Reply.json(400, "{\"error\":\"bad_request\"}");
        }
        if (auth.signup(username, password)) {
            return Reply.json(201, "{\"status\":\"created\"}");
        }
        return Reply.json(409, "{\"error\":\"username_taken\"}");
    }
}
