package marklit.testfixtures;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared in-JVM state observed by doc blocks via their printed output.
 *
 * When a run resource is configured, blocks execute against marklit's per-run
 * user loader {@code U}, so every block sees this one instance and successive
 * {@code incrementAndGet()} calls count up across blocks. Without a resource,
 * each block reloads the class fresh and always reads {@code 1}.
 *
 * Written in Java (not Scala) on purpose: a Java class file carries no TASTy, so
 * marklit's bundled compiler reads it regardless of the Scala version the test
 * project was built with. This also mirrors the real seam, which is JDK-typed.
 */
public final class SharedRunState {
    private SharedRunState() {}

    public static final AtomicInteger counter = new AtomicInteger(0);
}
