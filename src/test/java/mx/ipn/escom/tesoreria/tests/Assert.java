package mx.ipn.escom.tesoreria.tests;

/**
 * Minimal assertion helper for the pure-Java test harness (no JUnit).
 *
 * <p>Each method throws {@link AssertionError} with a descriptive message when
 * the expectation fails, so {@link RunTests} can catch it and mark the test as
 * failed without stopping the rest of the suite.
 */
public final class Assert {

    private Assert() {
    }

    /**
     * Fails unless the condition is {@code true}.
     *
     * @param message description shown when the assertion fails
     * @param condition value expected to be {@code true}
     */
    public static void isTrue(String message, boolean condition) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * Fails unless the two longs are equal.
     *
     * @param message description shown when the assertion fails
     * @param expected expected value
     * @param actual   actual value
     */
    public static void equals(String message, long expected, long actual) {
        if (expected != actual) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    /**
     * Fails unless the two objects are equal (null-safe).
     *
     * @param message description shown when the assertion fails
     * @param expected expected value
     * @param actual   actual value
     */
    public static void equals(String message, Object expected, Object actual) {
        boolean same = (expected == null) ? (actual == null) : expected.equals(actual);
        if (!same) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    /**
     * Fails when the reference is {@code null}.
     *
     * @param message description shown when the assertion fails
     * @param value   reference expected to be non-null
     */
    public static void notNull(String message, Object value) {
        if (value == null) {
            throw new AssertionError(message);
        }
    }

    /**
     * Fails when the supplied action does NOT throw an exception.
     *
     * @param message description shown when the assertion fails
     * @param action  code expected to throw
     */
    public static void throwsException(String message, Runnable action) {
        boolean threw = false;
        try {
            action.run();
        } catch (Throwable t) {
            threw = true;
        }
        if (!threw) {
            throw new AssertionError(message);
        }
    }
}
