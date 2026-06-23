package mx.ipn.escom.tesoreria.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The HTTP front door. A Server owns exactly ONE ServerSocketChannel, ONE
 * IoLoop reactor thread (with a single Selector), and a fixed pool of worker
 * threads. The IoLoop handles all I/O; workers compute replies. Construction
 * fixes the listening port, the router, and the worker-pool size; start()
 * binds and spins up the loop, stop() tears everything down.
 */
public final class Server {

    private static final int READ_BUFFER_SIZE = 16 * 1024;

    private final int configuredPort;
    private final Routes routes;
    private final int workerCount;

    private ServerSocketChannel serverChannel;
    private Selector selector;
    private ExecutorService workers;
    private IoLoop ioLoop;
    private volatile boolean started;

    /**
     * Creates a server bound (on start) to the given port, dispatching matched
     * requests through the supplied routes, with workers worker threads.
     */
    public Server(int port, Routes routes, int workers) {
        this.configuredPort = port;
        this.routes = routes;
        this.workerCount = Math.max(1, workers);
    }

    /**
     * Opens and binds the server channel, creates the selector and worker
     * pool, and starts the IoLoop. Idempotent guarding via the started flag.
     */
    public void start() throws IOException {
        if (started) {
            return;
        }
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(configuredPort));
        selector = Selector.open();
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        workers = Executors.newFixedThreadPool(workerCount);
        ioLoop = new IoLoop(selector, serverChannel, routes, workers, READ_BUFFER_SIZE);
        ioLoop.start();
        started = true;
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

    /** Stops the IoLoop, shuts down the worker pool, and closes the channel. */
    public void stop() {
        if (ioLoop != null) {
            ioLoop.stop();
        }
        if (workers != null) {
            workers.shutdownNow();
        }
        closeQuietly(selector);
        closeQuietly(serverChannel);
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
