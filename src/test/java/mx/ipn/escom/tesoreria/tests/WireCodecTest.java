package mx.ipn.escom.tesoreria.tests;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import mx.ipn.escom.tesoreria.cluster.WireCodec;
import mx.ipn.escom.tesoreria.core.Transfer;
import mx.ipn.escom.tesoreria.tests.RunTests.Case;

/**
 * Suite for {@link mx.ipn.escom.tesoreria.cluster.WireCodec}.
 *
 * <p>Checks the JSON line format used to replicate commits between the leader
 * and its replicas: a transfer encodes to a single object line, and encoding
 * then decoding yields an equal transfer (the 64-bit sequence and the cents
 * amount must survive the round trip without precision loss).
 */
public final class WireCodecTest {

    /**
     * Builds the list of cases contributed by this suite.
     *
     * @return ordered list of named cases for {@link RunTests}
     */
    public List<Case> cases() {
        return List.of(
                new Case("WireCodec encodes a transfer to one JSON line",
                        this::encodesOneLine),
                new Case("WireCodec round-trips a transfer through encode then decode",
                        this::roundTrips));
    }

    /**
     * Encodes a transfer and asserts the result is a single-line JSON object
     * carrying the seq, from, to and cents fields.
     */
    private void encodesOneLine() {
        Transfer t = new Transfer(1L, 10, 20, 5000L);
        String line = WireCodec.encode(t);
        Assert.isTrue("output is a single line", line.indexOf('\n') < 0 && line.indexOf('\r') < 0);
        JsonObject object = JsonParser.parseString(line).getAsJsonObject();
        Assert.equals("seq field", 1L, object.get("seq").getAsLong());
        Assert.equals("from field", 10L, object.get("from").getAsLong());
        Assert.equals("to field", 20L, object.get("to").getAsLong());
        Assert.equals("cents field", 5000L, object.get("cents").getAsLong());
    }

    /**
     * Decodes the encoding of a transfer and asserts equality with the original,
     * including large seq and cents values.
     */
    private void roundTrips() {
        Transfer t = new Transfer(9_000_000_000L, 123, 456, 8_000_000_000L);
        Transfer back = WireCodec.decode(WireCodec.encode(t));
        Assert.equals("decode(encode(t)) equals t", t, back);
    }
}
