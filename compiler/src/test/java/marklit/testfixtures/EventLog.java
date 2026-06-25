package marklit.testfixtures;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Append-only lifecycle event channel keyed by the {@code marklit.test.events}
 * system property. Because {@code System} is a platform class shared by every
 * classloader, the test and marklit's per-run user loader {@code U} agree on the
 * file path, letting the test observe acquire/teardown events recorded from
 * {@code U} across the classloader boundary.
 */
public final class EventLog {
    private EventLog() {}

    public static void record(String event) {
        String path = System.getProperty("marklit.test.events");
        if (path == null) return;
        try {
            Files.write(
                Paths.get(path),
                (event + "\n").getBytes("UTF-8"),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (Exception e) {
            // best-effort; a test will fail on the missing event instead.
        }
    }
}
