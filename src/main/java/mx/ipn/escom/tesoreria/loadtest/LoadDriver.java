package mx.ipn.escom.tesoreria.loadtest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Stand-alone load generator for the contest scenario.
 *
 * <p>Runs against a single node (the leader) for a fixed duration, issuing a
 * mix of 80% balance reads and 20% transfers using {@link HttpClient}. It
 * obtains a JWT via register+login, drives traffic with several client threads
 * for throughput, and at the end verifies the conservation invariant: the sum
 * of all account balances must equal the total observed before the run. On
 * mismatch it reports the offending account; otherwise it prints the number of
 * successful reads and the number of successful transfers.
 *
 * <p>This class is the only "client" in the project; it depends on nothing in
 * the server beyond the published REST contract.
 */
public final class LoadDriver {

    /** Default wall-clock duration of a run, in seconds, per the contest rules. */
    public static final int DEFAULT_SECONDS = 60;

    /** Default number of concurrent client threads used to drive load. */
    public static final int DEFAULT_CLIENTS = 8;

    /** Fraction of operations that are balance reads (the remainder are transfers). */
    public static final double READ_RATIO = 0.80;

    /** Bearer token obtained at startup and shared by every client thread. */
    private final String jwt;

    /** Base URL of the target node, e.g. {@code http://10.0.0.2:8080}. */
    private final String baseUrl;

    /** Inclusive lower bound of the account id range used for traffic. */
    private final int minAccountId;

    /** Inclusive upper bound of the account id range used for traffic. */
    private final int maxAccountId;

    /** JSON codec shared across the driver (gson is thread-safe for read/write). */
    private final Gson gson = new Gson();

    /**
     * ONE HttpClient shared by every worker thread. A per-worker client spawns its
     * own selector + executor threads, so at high client counts the generator
     * drowns in hundreds of its own threads (context-switch thrash) and caps far
     * below what the bank can serve. A single thread-safe client pools keep-alive
     * connections and keeps the generator's thread count flat.
     */
    private final HttpClient sharedClient = newClient();

    /** Count of balance reads that returned HTTP 200 with a parseable balance. */
    private final AtomicLong reads = new AtomicLong();

    /** Count of transfers accepted by the server (HTTP 200). */
    private final AtomicLong transfers = new AtomicLong();

    /**
     * Creates a driver bound to a target node and account-id range.
     *
     * @param baseUrl      scheme://host:port of the leader node
     * @param jwt          bearer token to send on every authorized request
     * @param minAccountId smallest account id to touch (inclusive)
     * @param maxAccountId largest account id to touch (inclusive)
     */
    public LoadDriver(String baseUrl, String jwt, int minAccountId, int maxAccountId) {
        this.baseUrl = baseUrl;
        this.jwt = jwt;
        this.minAccountId = minAccountId;
        this.maxAccountId = maxAccountId;
    }

    /**
     * Entry point. Usage: {@code LoadDriver <host> <port> [seconds] [clients]
     * [minAccountId] [maxAccountId] [scenario]}.
     *
     * @param args command-line arguments described above
     * @throws Exception if startup networking fails fatally
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: LoadDriver <host> <port> [seconds] [clients] [minId] [maxId] [scenario]");
            System.exit(2);
            return;
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_SECONDS;
        int clients = args.length > 3 ? Integer.parseInt(args[3]) : DEFAULT_CLIENTS;
        int minId = args.length > 4 ? Integer.parseInt(args[4]) : 1;
        int maxId = args.length > 5 ? Integer.parseInt(args[5]) : 1000;
        String scenario = args.length > 6 ? args[6] : "(unlabeled)";

        String baseUrl = "http://" + host + ":" + port;

        // TODO: register a fresh user and log in to obtain the bearer token.
        String token = bootstrapToken(baseUrl);

        LoadDriver driver = new LoadDriver(baseUrl, token, minId, maxId);

        // TODO: snapshot the total before the run so the invariant can be checked.
        long initialTotalCents = driver.captureTotalCents();

        driver.run(seconds, clients);

        driver.verifyAndReport(initialTotalCents, scenario);
    }

    /**
     * Registers a throwaway user and logs in, returning the JWT to use.
     *
     * @param baseUrl scheme://host:port of the leader node
     * @return a valid bearer token (without the {@code Bearer } prefix)
     * @throws Exception if the handshake fails
     */
    static String bootstrapToken(String baseUrl) throws Exception {
        HttpClient http = newClient();
        String creds = "{\"username\":\"loaddriver\",\"password\":\"loadpass\"}";
        HttpRequest register = HttpRequest.newBuilder(URI.create(baseUrl + "/api/register"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(creds))
                .build();
        // 201 created or 409 already-registered are both fine: we only need a login.
        http.send(register, HttpResponse.BodyHandlers.ofString());
        HttpRequest login = HttpRequest.newBuilder(URI.create(baseUrl + "/api/login"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(creds))
                .build();
        HttpResponse<String> response = http.send(login, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("login failed: " + response.statusCode()
                    + " " + response.body());
        }
        return new Gson().fromJson(response.body(), JsonObject.class).get("token").getAsString();
    }

    /**
     * Launches {@code clients} worker threads for {@code seconds} seconds and
     * waits for them to drain.
     *
     * @param seconds wall-clock duration of the load phase
     * @param clients number of concurrent client threads
     * @throws InterruptedException if the main thread is interrupted while waiting
     */
    public void run(int seconds, int clients) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        CountDownLatch done = new CountDownLatch(clients);
        List<Thread> threads = new ArrayList<>(clients);
        for (int i = 0; i < clients; i++) {
            Thread t = new Thread(new Worker(deadline, done), "load-" + i);
            threads.add(t);
            t.start();
        }
        done.await();
    }

    /**
     * Issues a single balance read for a random account id and, on success,
     * increments the read counter.
     *
     * @param http the per-thread HTTP client
     * @return {@code true} if the server returned HTTP 200 with a balance
     */
    boolean doRead(HttpClient http) {
        HttpRequest request = authorized("/api/accounts/" + randomAccountId()).GET().build();
        String body = send(http, request, 200);
        if (body == null) {
            return false;
        }
        reads.incrementAndGet();
        return true;
    }

    /**
     * Issues a single transfer between two distinct random accounts and, on
     * success, increments the transfer counter.
     *
     * @param http the per-thread HTTP client
     * @return {@code true} if the server accepted the transfer (HTTP 200)
     */
    boolean doTransfer(HttpClient http) {
        int from = randomAccountId();
        int to = randomAccountId();
        if (from == to) {
            to = (to < maxAccountId) ? to + 1 : minAccountId;
            if (from == to) {
                return false;
            }
        }
        HttpRequest request = authorized("/api/transactions/transfer")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(transferBody(from, to, randomAmount())))
                .build();
        String body = send(http, request, 200);
        if (body == null) {
            return false;
        }
        transfers.incrementAndGet();
        return true;
    }

    /** A small two-decimal amount in [0.01, 100.00], low so transfers rarely exhaust an account. */
    String randomAmount() {
        long cents = ThreadLocalRandom.current().nextLong(1, 10001);
        return BigDecimal.valueOf(cents).movePointLeft(2).setScale(2).toPlainString();
    }

    /**
     * Returns the full-bank total balance in cents, read in one request from
     * {@code GET /api/stats} (the server sums all 820k accounts in memory). This
     * is the true conservation total the contract verifies, and it is O(1) on the
     * client instead of summing hundreds of thousands of balances over HTTP.
     *
     * @return total balance across every account, in cents
     */
    long captureTotalCents() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/stats"))
                .timeout(Duration.ofSeconds(10)).GET().build();
        String body = send(sharedClient, request, 200);
        if (body == null) {
            throw new IllegalStateException("could not read /api/stats for the invariant snapshot");
        }
        return toCents(gson.fromJson(body, JsonObject.class).get("totalBalance").getAsString());
    }

    /**
     * Re-reads all balances and compares against the baseline. If the totals
     * differ it scans for and reports the offending account; otherwise it
     * prints the two success counters.
     *
     * @param initialTotalCents total balance captured before the load phase
     * @param scenario          label printed with the results (e.g. "nodes-1-2-3")
     */
    void verifyAndReport(long initialTotalCents, String scenario) {
        long finalTotalCents = captureTotalCents();
        if (finalTotalCents != initialTotalCents) {
            // TODO: locate and print the account(s) whose balance is impossible.
            System.out.println("INCONSISTENT: expected " + initialTotalCents
                    + " cents, found " + finalTotalCents + " cents");
            reportCulprit(initialTotalCents, finalTotalCents);
            return;
        }
        // Contest score per the PDF: a transfer is worth four times a read.
        long score = transfers.get() * 4 + reads.get();
        System.out.println("CONSISTENT");
        System.out.println("scenario:                    " + scenario);
        System.out.println("successful reads:             " + reads.get());
        System.out.println("successful transfers:         " + transfers.get());
        System.out.println("score (transfers*4 + reads): " + score);
    }

    /**
     * Best-effort identification of the account responsible for an invariant
     * violation (e.g. a negative balance), for the diagnostic output.
     *
     * @param expectedCents the conserved total that should have held
     * @param actualCents   the total actually observed
     */
    void reportCulprit(long expectedCents, long actualCents) {
        HttpClient http = newClient();
        for (int id = minAccountId; id <= maxAccountId; id++) {
            HttpRequest request = authorized("/api/accounts/" + id).GET().build();
            String body = send(http, request, 200);
            if (body != null && parseBalanceCents(body) < 0L) {
                System.out.println("culprit: account " + id + " has a negative balance");
                return;
            }
        }
        System.out.println("no single negative account found; the "
                + (actualCents - expectedCents) + " cent gap is spread across the range");
    }

    /** A uniformly random account id in {@code [minAccountId, maxAccountId]}. */
    int randomAccountId() {
        return ThreadLocalRandom.current().nextInt(minAccountId, maxAccountId + 1);
    }

    /** A fresh per-thread HTTP/1.1 client (own client so pools are not a shared bottleneck). */
    static HttpClient newClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** Converts a decimal money string such as {@code "15750.25"} to cents (1575025). */
    static long toCents(String decimal) {
        return new BigDecimal(decimal).movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** Extracts the {@code balance} field (in cents) from an account JSON document. */
    long parseBalanceCents(String json) {
        JsonObject obj = gson.fromJson(json, JsonObject.class);
        return toCents(obj.get("balance").getAsString());
    }

    /**
     * Sends a request and returns the response body as text, or {@code null}
     * if the status code is not in the expected-success set.
     *
     * @param http     the HTTP client to use
     * @param request  the request to send
     * @param okStatus the status code that denotes success
     * @return the response body on success, otherwise {@code null}
     */
    static String send(HttpClient http, HttpRequest request, int okStatus) {
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == okStatus ? resp.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Builds an authorized request to a path under the base URL.
     *
     * @param path absolute API path beginning with {@code /}
     * @return a request builder pre-loaded with the Bearer header
     */
    HttpRequest.Builder authorized(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + jwt);
    }

    /**
     * Serializes a transfer body to the mandatory wire shape.
     *
     * @param sourceId source account id (sent as a string)
     * @param targetId target account id (sent as a string)
     * @param amount   decimal amount, e.g. {@code "200.00"}
     * @return a JSON object string ready to POST
     */
    String transferBody(int sourceId, int targetId, String amount) {
        Map<String, Object> body = new ConcurrentHashMap<>();
        body.put("sourceAccountId", String.valueOf(sourceId));
        body.put("targetAccountId", String.valueOf(targetId));
        body.put("amount", new BigDecimal(amount));
        return gson.toJson(body);
    }

    /**
     * Per-thread load loop: until the shared deadline, it rolls the read/write
     * mix and dispatches each operation, counting the deadline-aware latch down
     * when finished.
     */
    private final class Worker implements Runnable {

        /** Absolute {@link System#nanoTime()} value at which to stop. */
        private final long deadlineNanos;

        /** Latch counted down once this worker exits its loop. */
        private final CountDownLatch done;

        /**
         * @param deadlineNanos absolute nano-time stop point shared by all workers
         * @param done          completion latch for the driver to await
         */
        Worker(long deadlineNanos, CountDownLatch done) {
            this.deadlineNanos = deadlineNanos;
            this.done = done;
        }

        @Override
        public void run() {
            HttpClient http = sharedClient;
            try {
                while (System.nanoTime() < deadlineNanos) {
                    if (ThreadLocalRandom.current().nextDouble() < READ_RATIO) {
                        doRead(http);
                    } else {
                        doTransfer(http);
                    }
                }
            } finally {
                done.countDown();
            }
        }
    }
}
