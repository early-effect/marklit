package example

import java.nio.file.{Files, Paths, StandardOpenOption}
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

/** Run-scoped shared state for the scripted test, mirroring the real-world
  * pattern (e.g. a database handle): a single instance that doc blocks mutate.
  *
  * Because the build sets `marklitRunResourceClass`, every block in a run
  * executes against marklit's per-run user loader, so they all see this one
  * `value`.
  */
object Counter:
  val value = new AtomicInteger(0)

/** Append-only lifecycle log, keyed by a system property so the build's
  * `checkLifecycle` task and the resource (which marklit loads on its own
  * classloader) agree on the file. `System` is a platform class shared by every
  * loader, so the path crosses the classloader boundary.
  */
object Events:
  // `import` order intentionally avoids shadowing; this is plain java.lang.System.
  def record(event: String): Unit =
    val path = java.lang.System.getProperty("marklit.scripted.events")
    if path != null then
      Files.write(
        Paths.get(path),
        (event + "\n").getBytes("UTF-8"),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )

/** The build-provided run resource. `get()` is setup (run once before any doc),
  * and the returned `AutoCloseable` is teardown (run once after the last doc,
  * even on failure). Implements the JDK `Supplier[AutoCloseable]` seam, so this
  * class needs no dependency on marklit.
  */
final class CounterResource extends Supplier[AutoCloseable]:
  def get(): AutoCloseable =
    Counter.value.set(0)
    Events.record("acquire")
    () =>
      Counter.value.set(0)
      Events.record("close")
