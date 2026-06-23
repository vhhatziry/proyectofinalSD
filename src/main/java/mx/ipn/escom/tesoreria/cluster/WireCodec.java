package mx.ipn.escom.tesoreria.cluster;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import mx.ipn.escom.tesoreria.core.Transfer;

/**
 * Serialization bridge between the in-memory {@link Transfer} and the bytes that
 * travel across the replication link of "Tesoreria Distribuida" (Equipo 18).
 *
 * <p>The chosen representation is a compact JSON object carrying the four fields
 * a commit needs to be replayed on a peer: the sequence number, the source and
 * target account ids and the moved amount in cents. Every transfer becomes a
 * single self-contained JSON line, which lets the leader push both its catch-up
 * history and its live commit stream as a newline-delimited sequence of objects.
 * Keeping the format textual and one-object-per-line means the replica can read
 * the stream with an ordinary buffered line reader.
 *
 * <p>This codec is a stateless helper, so it exposes only static operations and
 * cannot be instantiated. The JSON shape is {@code {"seq":..,"from":..,"to":..,
 * "cents":..}}; numeric fields are parsed back with their exact widths so the
 * 64-bit sequence and cents values survive the round trip without losing
 * precision.
 */
public final class WireCodec {

    private WireCodec() {
        // Stateless helper: instantiation is intentionally forbidden.
    }

    /**
     * Renders a committed transfer as one JSON line ready to be written to the
     * replication socket.
     *
     * @param t the committed transfer to serialize
     * @return a single-line JSON object (no trailing newline) of the form
     *         {@code {"seq":..,"from":..,"to":..,"cents":..}}
     */
    public static String encode(Transfer t) {
        JsonObject object = new JsonObject();
        object.addProperty("seq", t.seq());
        object.addProperty("from", t.from());
        object.addProperty("to", t.to());
        object.addProperty("cents", t.cents());
        return object.toString();
    }

    /**
     * Rebuilds a {@link Transfer} from one JSON line produced by
     * {@link #encode(Transfer)}.
     *
     * <p>Each numeric field is read with its native width so that the 64-bit
     * {@code seq} and {@code cents} values are reconstructed exactly.
     *
     * @param line one wire line (without trailing newline)
     * @return the reconstructed transfer
     * @throws IllegalArgumentException if the line is not a valid transfer object
     */
    public static Transfer decode(String line) {
        try {
            JsonObject object = JsonParser.parseString(line).getAsJsonObject();
            long seq = object.get("seq").getAsLong();
            int from = object.get("from").getAsInt();
            int to = object.get("to").getAsInt();
            long cents = object.get("cents").getAsLong();
            return new Transfer(seq, from, to, cents);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("malformed transfer line: " + line, ex);
        }
    }
}
