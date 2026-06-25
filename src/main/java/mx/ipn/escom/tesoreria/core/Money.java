package mx.ipn.escom.tesoreria.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money conversion helper for the Tesoreria Distribuida domain.
 *
 * <p>Centralizes the single source of truth for translating between a
 * human-facing decimal string (two fractional digits) and the internal
 * integer representation in cents. Extracted on purpose so that endpoints,
 * the dataset loader and the cluster codec never embed ad-hoc BigDecimal
 * arithmetic inline.
 */
public final class Money {

    private Money() {
        // Static helper; no instances.
    }

    /**
     * Parses a decimal amount (e.g. "1234.56") into an integer number of cents
     * using BigDecimal with HALF_UP rounding.
     *
     * @param decimal the amount as a decimal string
     * @return the equivalent value in cents
     */
    public static long toCents(String decimal) {
        return new BigDecimal(decimal)
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /**
     * Renders an integer number of cents back into a decimal string with
     * exactly two fractional digits.
     *
     * @param cents the value in cents
     * @return the amount as a decimal string
     */
    public static String toDecimal(long cents) {
        return BigDecimal.valueOf(cents)
                .movePointLeft(2)
                .setScale(2, RoundingMode.UNNECESSARY)
                .toPlainString();
    }
}
