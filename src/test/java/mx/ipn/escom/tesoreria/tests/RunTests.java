package mx.ipn.escom.tesoreria.tests;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point of the pure-Java test harness (no JUnit).
 *
 * <p>It instantiates every suite, runs each declared {@code test*} case,
 * collects pass/fail counts and prints a summary. The process exits with a
 * non-zero status when any case fails so it can gate the build.
 */
public final class RunTests {

    private RunTests() {
    }

    /**
     * A single named test case backed by a {@link Runnable} body.
     *
     * @param name human-readable case name (printed in the report)
     * @param body code that throws on failure (see {@link Assert})
     */
    public record Case(String name, Runnable body) {
    }

    /**
     * Runs every suite and reports the aggregate result.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        List<Case> cases = new ArrayList<>();
        cases.addAll(new LedgerTest().cases());
        cases.addAll(new MoneyTest().cases());
        cases.addAll(new RequestParserTest().cases());
        cases.addAll(new WireCodecTest().cases());
        cases.addAll(new AuthTest().cases());

        int passed = 0;
        int failed = 0;
        for (Case c : cases) {
            try {
                c.body().run();
                passed++;
                System.out.println("PASS " + c.name());
            } catch (Throwable t) {
                failed++;
                System.out.println("FAIL " + c.name() + " : " + t.getMessage());
            }
        }

        System.out.println("---");
        System.out.println("total=" + cases.size() + " passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
