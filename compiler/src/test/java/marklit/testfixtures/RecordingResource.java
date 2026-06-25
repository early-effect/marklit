package marklit.testfixtures;

import java.util.function.Supplier;

/**
 * A well-behaved run resource: records {@code acquire} on setup and {@code close}
 * on teardown, exactly once each per run. Implements the JDK seam
 * {@code Supplier<AutoCloseable>} that marklit instantiates by FQN on its per-run
 * user loader.
 */
public final class RecordingResource implements Supplier<AutoCloseable> {
    @Override
    public AutoCloseable get() {
        EventLog.record("acquire");
        return () -> EventLog.record("close");
    }
}
