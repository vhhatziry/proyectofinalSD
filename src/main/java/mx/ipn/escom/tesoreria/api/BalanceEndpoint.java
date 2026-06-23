package mx.ipn.escom.tesoreria.api;

import java.math.BigDecimal;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import mx.ipn.escom.tesoreria.core.Account;
import mx.ipn.escom.tesoreria.core.Ledger;
import mx.ipn.escom.tesoreria.core.Money;
import mx.ipn.escom.tesoreria.net.Endpoint;
import mx.ipn.escom.tesoreria.net.Reply;
import mx.ipn.escom.tesoreria.net.Request;
import mx.ipn.escom.tesoreria.security.Authenticator;

/**
 * Handles GET /api/accounts/{id}: returns one account snapshot.
 * Requires a valid JWT. The response body uses the mandatory contract shape
 * {"id":125,"propietario":"JUAN MOLINAR HERNANDEZ","balance":15750.25} where
 * the keys are exactly id, propietario and balance. Per the contract, id and
 * balance serialize as JSON NUMBERS (unquoted); since {@link Money#toDecimal}
 * returns a String, the balance must be wrapped in a {@link BigDecimal} so gson
 * emits it as a number rather than a quoted string. Returns 401 when
 * the token is missing or invalid, 404 when the account does not exist, 400 on
 * a non-numeric path id and 405 on a wrong HTTP method.
 */
public final class BalanceEndpoint implements Endpoint {

    /** Mandatory JSON key for the account id. */
    private static final String KEY_ID = "id";

    /** Mandatory JSON key for the account owner's full name. */
    private static final String KEY_OWNER = "propietario";

    /** Mandatory JSON key for the decimal balance. */
    private static final String KEY_BALANCE = "balance";

    /** Shared JSON codec for building the reply body. */
    private final Gson gson = new Gson();

    /** Authentication facade used to authorize the Bearer token. */
    private final Authenticator auth;

    /** In-memory ledger that owns every account. */
    private final Ledger ledger;

    /**
     * Builds the endpoint over the node-wide authenticator and ledger.
     *
     * @param auth   the authenticator that validates the Bearer token
     * @param ledger the ledger holding the accounts to query
     */
    public BalanceEndpoint(Authenticator auth, Ledger ledger) {
        this.auth = auth;
        this.ledger = ledger;
    }

    /**
     * Authorizes the caller, resolves the {id} path variable and serializes the
     * account using the mandatory {id, propietario, balance} shape.
     *
     * @param req the incoming request
     * @return a {@link Reply} with the account JSON or an error status
     */
    @Override
    public Reply serve(Request req) {
        // TODO: enforce GET (405 otherwise); call auth.authorize(req) and return 401
        // when it fails; parse req.pathParam("id") to int (400 if not numeric);
        // look the Account up in the ledger (404 if absent); build a JsonObject with
        // keys id (number), propietario (string) and balance (number). Read
        // balanceCents under the Account monitor, then add balance via the Number
        // overload, e.g. body.addProperty("balance", new BigDecimal(
        // Money.toDecimal(cents))), so gson emits 15750.25 unquoted (NOT "15750.25").
        // Add id with addProperty(String, Number) too. Return 200 with the body.
        throw new UnsupportedOperationException("TODO");
    }
}
