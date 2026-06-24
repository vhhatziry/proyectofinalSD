package mx.ipn.escom.tesoreria.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The HTTP front door. A Server owns one ServerSocketChannel, a dedicated
 * acceptor thread, a pool of {@link IoLoop} reactors (one Selector each) and a
 * shared pool of worker threads. The acceptor blocks on accept() and round-robins
 * each new connection to a reactor, so socket reads and writes are spread across
 * the cores instead of funnelling through a single reactor thread (which
 * saturates one core and caps throughput while the rest sit idle). The reactors
 * do all I/O; workers compute replies.
 *
 * <p>Construction fixes the port, the router, the worker-pool size and the reactor
 * count; start() binds and spins everything up, stop() tears it down. One reactor
 * ({@code reactors == 1}) is the classic single-loop behaviour and a safe
 * fallback.
 */
public final class Server {

    private static final int READ_BUFFER_SIZE = 16 * 1024;

    private final int configuredPort;
    private final Routes routes;
    private final int workerCount;
    private final int reactorCount;

    private ServerSocketChannel serverChannel;
    private ExecutorService workers;
    private IoLoop[] loops;
    private Thread acceptorThread;
    private volatile boolean running;
    private volatile boolean started;

    /**
     * Creates a server bound (on start) to the given port, dispatching matched
     * requests through the supplied routes, with the given worker-pool size and
     * number of reactor loops.
     */
    public Server(int port, Routes routes, int workers, int reactors) {
        this.configuredPort = port;
        this.routes = routes;
        this.workerCount = Math.max(1, workers);
        this.reactorCount = Math.max(1, reactors);
    }

    /**
     * Opens and binds the server channel, creates the worker pool and the reactor
     * loops, and starts the acceptor. Idempotent guarding via the started flag.
     */
    public void start() throws IOException {
        if (started) {
            return;
        }
        serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(configuredPort));
        workers = Executors.newFixedThreadPool(workerCount);
        loops = new IoLoop[reactorCount];
        for (int i = 0; i < reactorCount; i++) {
            loops[i] = new IoLoop(Selector.open(), routes, workers, READ_BUFFER_SIZE);
            loops[i].start();
        }
        running = true;
        acceptorThread = new Thread(this::acceptLoop, "tesoreria-acceptor");
        acceptorThread.setDaemon(true);
        acceptorThread.start();
        started = true;
    }

    /**
     * Blocking accept loop: hands each new connection to the next reactor in
     * round-robin so the I/O load is balanced across the loops.
     */
    private void acceptLoop() {
        int next = 0;
        while (running) {
            try {
                SocketChannel socket = serverChannel.accept();
                if (socket == null) {
                    continue;
                }
                socket.configureBlocking(false);
                loops[next].register(socket);
                next = (next + 1) % loops.length;
            } catch (IOException e) {
                if (running) {
                    continue; // a transient accept error; keep serving
                }
                return; // channel closed during shutdown
            }
        }
    }

    /**
     * The actual bound port. Useful when constructed with port 0 to let the OS
     * pick an ephemeral port (handy for tests).
     */
    public int port() {
        if (serverChannel == null) {
            return configuredPort;
        }
        try {
            return ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
        } catch (IOException e) {
            return configuredPort;
        }
    }

    /** Stops the acceptor and reactors, shuts down the workers, closes the channel. */
    public void stop() {
        running = false;
        closeQuietly(serverChannel); // unblocks the acceptor's accept()
        if (loops != null) {
            for (IoLoop loop : loops) {
                loop.stop();
            }
        }
        if (workers != null) {
            workers.shutdownNow();
        }
        started = false;
    }

    /** Whether the server is currently running. */
    public boolean isStarted() {
        return started;
    }

    /** Closes a resource, swallowing any IOException raised during teardown. */
    private static void closeQuietly(AutoCloseable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception ignored) {
                // Teardown is best effort.
            }
        }
    }
}
