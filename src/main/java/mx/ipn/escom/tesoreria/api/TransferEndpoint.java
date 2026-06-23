package mx.ipn.escom.tesoreria.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import mx.ipn.escom.tesoreria.core.Bank;
import mx.ipn.escom.tesoreria.core.Money;
import mx.ipn.escom.tesoreria.core.TransferException;
import mx.ipn.escom.tesoreria.net.Endpoint;
import mx.ipn.escom.tesoreria.net.Reply;
import mx.ipn.escom.tesoreria.net.Request;
import mx.ipn.escom.tesoreria.security.Authenticator;

/**
 * Handles POST /api/transactions/transfer: moves money between two accounts.
 * Requires a valid JWT. The request body uses the mandatory contract shape
 * {"sourceAccountId":"123","targetAccountId":"456","amount":200.00} where the
 * ids are STRINGs and amount is a decimal. The decimal amount is converted to
 * cents through {@link Money} and the transaction is orchestrated by
 * {@link Bank#transfer}, which validates the request, applies the atomic move,
 * stamps a fresh sequence number and preserves the conservation invariant. A
 * successful call returns 200 with {"status":"ok","seq":<seq>} carrying the
 * assigned sequence. A rejection surfaces as a {@link TransferException}: its
 * {@code code()} drives the status, where "no_such_account" maps to 404 and the
 * remaining codes ("self_transfer", "bad_amount", "low_balance") map to 400, and
 * the body echoes {"error":<code>}. Returns 401 without a valid token and 405 on
 * a wrong HTTP method.
 */
public final class TransferEndpoint implements Endpoint {

    /** Mandatory JSON key for the debited account id (a string). */
    private static final String KEY_SOURCE = "sourceAccountId";

    /** Mandatory JSON key for the credited account id (a string). */
    private static final String KEY_TARGET = "targetAccountId";

    /** Mandatory JSON key for the decimal amount. */
    private static final String KEY_AMOUNT = "amount";

    /** Shared JSON codec for parsing the request body. */
    private final Gson gson = new Gson();

    /** Authentication facade used to authorize the Bearer token. */
    private final Authenticator auth;

    /** Transaction orchestrator that validates, applies and sequences moves. */
    private final Bank bank;

    /**
     * Builds the endpoint over the node-wide authenticator and bank.
     *
     * @param auth the authenticator that validates the Bearer token
     * @param bank the bank that orchestrates the transfer atomically
     */
    public TransferEndpoint(Authenticator auth, Bank bank) {
        this.auth = auth;
        this.bank = bank;
    }

    /**
     * Authorizes the caller, parses the mandatory transfer body and hands the
     * move to the bank, mapping a rejection to a REST status.
     *
     * @param req the incoming request
     * @return a {@link Reply} describing the transfer result
     */
    @Override
    public Reply serve(Request req) {
        if (!"POST".equals(req.method())) {
            return Reply.status(405);
        }
        if (auth.authorize(req) == null) {
            return Reply.status(401);
        }
        int from;
        int to;
        long cents;
        try {
            JsonObject body = gson.fromJson(req.bodyText(), JsonObject.class);
            from = Integer.parseInt(body.get(KEY_SOURCE).getAsString().trim());
            to = Integer.parseInt(body.get(KEY_TARGET).getAsString().trim());
            cents = Money.toCents(body.get(KEY_AMOUNT).getAsString());
        } catch (RuntimeException e) {
            return Reply.json(400, "{\"error\":\"bad_request\"}");
        }
        try {
            long seq = bank.transfer(from, to, cents);
            JsonObject ok = new JsonObject();
            ok.addProperty("status", "ok");
            ok.addProperty("seq", Long.valueOf(seq));
            return Reply.json(200, gson.toJson(ok));
        } catch (TransferException e) {
            int status = "no_such_account".equals(e.code()) ? 404 : 400;
            JsonObject err = new JsonObject();
            err.addProperty("error", e.code());
            return Reply.json(status, gson.toJson(err));
        }
    }
}
