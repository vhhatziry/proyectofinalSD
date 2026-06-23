package mx.ipn.escom.tesoreria.api;

import java.io.IOException;
import java.io.InputStream;

import mx.ipn.escom.tesoreria.net.Endpoint;
import mx.ipn.escom.tesoreria.net.Reply;
import mx.ipn.escom.tesoreria.net.Request;

/**
 * Handles GET /: serves the single-page dashboard.
 * Streams dashboard.html from the classpath resources as an HTML response. The
 * page itself polls /panel (and offers a Refresh control) to render every
 * node's status, account count, total balance, transfer count, last
 * transaction id, %CPU, %RAM, %Disk and the Cloud Storage transaction count.
 * Returns 405 on a wrong HTTP method and 404 if the resource is missing.
 */
public final class DashboardEndpoint implements Endpoint {

    /** Classpath location of the single-page dashboard document. */
    private static final String RESOURCE = "/dashboard.html";

    /**
     * Builds the endpoint. The HTML is loaded from resources on each request.
     */
    public DashboardEndpoint() {
        // No collaborators: the page is a static classpath resource.
    }

    /**
     * Validates the method and writes the dashboard HTML to the response.
     *
     * @param req the incoming request
     * @return a {@link Reply} carrying the dashboard page as HTML
     */
    @Override
    public Reply serve(Request req) {
        if (!"GET".equals(req.method())) {
            return Reply.status(405);
        }
        try (InputStream in = getClass().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return Reply.status(404);
            }
            return Reply.html(200, in.readAllBytes());
        } catch (IOException e) {
            return Reply.status(500);
        }
    }
}
