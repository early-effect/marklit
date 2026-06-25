package marklit.testfixtures;

import java.util.function.Supplier;

/**
 * A run resource whose teardown throws — exercises the contract that a teardown
 * failure is logged as a notice and swallowed, never failing the run.
 */
public final class ThrowingTeardownResource implements Supplier<AutoCloseable> {
    @Override
    public AutoCloseable get() {
        return () -> { throw new RuntimeException("boom-teardown"); };
    }
}
