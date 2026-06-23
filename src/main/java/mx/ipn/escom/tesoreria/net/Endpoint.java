package mx.ipn.escom.tesoreria.net;

/**
 * Functional contract for a request handler. An Endpoint receives a fully
 * parsed Request (with route parameters already bound) and returns the Reply
 * to send back. Implementations validate the method, enforce authorization
 * where required, and never touch the wire directly.
 */
@FunctionalInterface
public interface Endpoint {

    /** Handles one request and produces its reply. */
    Reply serve(Request req);
}
