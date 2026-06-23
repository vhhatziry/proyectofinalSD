package mx.ipn.escom.tesoreria.tests;

import java.util.List;

import mx.ipn.escom.tesoreria.core.Money;
import mx.ipn.escom.tesoreria.tests.RunTests.Case;

/**
 * Suite for {@link mx.ipn.escom.tesoreria.core.Money}.
 *
 * <p>Verifies the round-trip between decimal strings and {@code long} cents:
 * {@code toCents} must use {@code BigDecimal.movePointRight(2)} with HALF_UP
 * rounding (never {@code double}) and {@code toDecimal} must render exactly two
 * decimal places.
 */
public final class MoneyTest {

    /**
     * Builds the list of cases contributed by this suite.
     *
     * @return ordered list of named cases for {@link RunTests}
     */
    public List<Case> cases() {
        return List.of(
                new Case("Money.toCents parses decimal strings to cents (HALF_UP)",
                        this::toCentsParsesDecimals),
                new Case("Money.toDecimal renders cents with two decimals",
                        this::toDecimalRendersTwoPlaces),
                new Case("Money round-trips decimal <-> cents",
                        this::roundTrips));
    }

    /**
     * Asserts that representative decimal strings convert to the expected cent
     * amounts, including HALF_UP rounding at the third decimal.
     */
    private void toCentsParsesDecimals() {
        Assert.equals("toCents(200.00)", 20000L, Money.toCents("200.00"));
        Assert.equals("toCents(15750.25)", 1575025L, Money.toCents("15750.25"));
        Assert.equals("toCents(0.005) rounds HALF_UP", 1L, Money.toCents("0.005"));
    }

    /**
     * Asserts that cent amounts render with exactly two decimal places.
     */
    private void toDecimalRendersTwoPlaces() {
        Assert.equals("toDecimal(20000)", "200.00", Money.toDecimal(20000L));
        Assert.equals("toDecimal(5)", "0.05", Money.toDecimal(5L));
        Assert.equals("toDecimal(0)", "0.00", Money.toDecimal(0L));
    }

    /**
     * Asserts toDecimal(toCents(x)) is stable for valid two-decimal inputs.
     */
    private void roundTrips() {
        for (String decimal : new String[] {"0.00", "1.00", "15750.25", "999999.99"}) {
            Assert.equals("round-trip " + decimal, decimal, Money.toDecimal(Money.toCents(decimal)));
        }
    }
}
