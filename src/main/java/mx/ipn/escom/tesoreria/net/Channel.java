package mx.ipn.escom.tesoreria.net;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Per-connection state attached to a SelectionKey. Holds the read buffer, the
 * single stateful RequestParser for this socket, the queue of outbound byte
 * buffers waiting to be flushed, and the lifecycle flags shared between the
 * IoLoop thread and the worker that fills in replies. The outbound queue is
 * the hand-off point: workers enqueue, the IoLoop drains it on write events.
 */
public final class Channel {

    private final SocketChannel socket;
    private final RequestParser parser = new RequestParser();
    private final ByteBuffer readBuffer;
    private final Deque<ByteBuffer> outbound = new ArrayDeque<>();

    // Flags coordinated across threads; see accessors for their meaning.
    private volatile boolean closePending;
    private volatile boolean processing;

    /** Wraps a connected socket with a read buffer of the given capacity. */
    public Channel(SocketChannel socket, int readBufferSize) {
        this.socket = socket;
        this.readBuffer = ByteBuffer.allocate(readBufferSize);
    }

    /** The underlying non-blocking socket. */
    public SocketChannel socket() {
        return socket;
    }

    /** This connection's stateful parser (one per Channel). */
    public RequestParser parser() {
        return parser;
    }

    /** Buffer the IoLoop reads incoming bytes into. */
    public ByteBuffer readBuffer() {
        return readBuffer;
    }

    /**
     * Queues a fully serialized reply for the IoLoop to flush. Called from a
     * worker thread; synchronized because the IoLoop drains the same queue.
     */
    public void enqueue(ByteBuffer out) {
        synchronized (outbound) {
            outbound.addLast(out);
        }
    }

    /** The head pending buffer without removing it, or null when empty. */
    public ByteBuffer peekOutbound() {
        synchronized (outbound) {
            return outbound.peekFirst();
        }
    }

    /** Removes the head buffer once it has been fully written. */
    public void popOutbound() {
        synchronized (outbound) {
            outbound.pollFirst();
        }
    }

    /** True while there is still data waiting to be written. */
    public boolean hasOutbound() {
        synchronized (outbound) {
            return !outbound.isEmpty();
        }
    }

    /** Marks that the socket must be closed once the outbound queue drains. */
    public void markClose() {
        this.closePending = true;
    }

    /** Whether a close has been requested for after the final flush. */
    public boolean isClosePending() {
        return closePending;
    }

    /** Guards against dispatching a second worker while one is in flight. */
    public boolean isProcessing() {
        return processing;
    }

    /** Sets the in-flight worker flag. */
    public void setProcessing(boolean processing) {
        this.processing = processing;
    }
}
