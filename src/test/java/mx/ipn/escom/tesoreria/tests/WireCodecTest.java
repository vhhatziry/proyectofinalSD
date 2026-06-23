package mx.ipn.escom.tesoreria.tests;

import java.util.List;

import mx.ipn.escom.tesoreria.tests.RunTests.Case;

/**
 * Suite (skeleton) for {@link mx.ipn.escom.tesoreria.cluster.WireCodec}.
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
     * carrying the seq, from, to and cents fields. (Skeleton.)
     */
    private void encodesOneLine() {
        // TODO: encode a Transfer and assert the output is one line and a valid
        // JSON object exposing seq, from, to and cents.
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * Decodes the encoding of a transfer and asserts equality with the original,
     * including large seq and cents values. (Skeleton.)
     */
    private void roundTrips() {
        // TODO: assert decode(encode(t)) equals t for a transfer with a large
        // sequence number and cents amount.
        throw new UnsupportedOperationException("TODO");
    }
}
