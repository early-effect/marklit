package marklit.testfixtures;

import java.util.function.Supplier;

/**
 * A run resource whose setup throws — exercises the contract that a setup
 * failure is surfaced as a notice and lets the run proceed.
 */
public final class ThrowingSetupResource implements Supplier<AutoCloseable> {
    @Override
    public AutoCloseable get() {
        throw new RuntimeException("boom-setup");
    }
}
