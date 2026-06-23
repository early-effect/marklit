package marklit.sbt

import marklit.compiler.CompilerFactory
import zio.{Exit, Runtime, Scope, Unsafe, ZIO}

/** Session-scoped holder for a single [[CompilerFactory]] and a ZIO runtime.
  *
  * This is the in-process replacement for the old out-of-process marklit
  * daemon. `CompilerFactory.layer` is a *scoped* layer — it extracts the two
  * shim jars to temp files and maintains a per-Scala-version cache of
  * classloaders + reflective dotc invokers. By building it once and keeping its
  * Scope open for the whole sbt session, every marklit task reuses the same
  * warm per-version compilers; the second `marklitGenerate` skips the
  * cold-start classloader/Coursier work entirely.
  *
  * `shutdown()` is wired to the build's `Global / onUnload` so the scope (and
  * its temp files) is released on sbt exit / reload.
  */
object MarklitSession {

  private final class Live(
      val factory: CompilerFactory,
      val runtime: Runtime[Any],
      val scope: Scope.Closeable
  )

  // Guarded by `this`. Null until first use.
  private var live: Live = null

  private def ensure(): Live = synchronized {
    if (live == null) {
      val runtime = Runtime.default
      val scope = Unsafe.unsafe { implicit u =>
        runtime.unsafe.run(Scope.make).getOrThrowFiberFailure()
      }
      // Build the factory inside the session scope so its acquireRelease
      // resources (shim temp jars) stay alive until shutdown() closes the scope.
      val factory = Unsafe.unsafe { implicit u =>
        runtime.unsafe
          .run(scope.extend[Any](CompilerFactory.layer.build))
          .getOrThrowFiberFailure()
          .get[CompilerFactory]
      }
      live = new Live(factory, runtime, scope)
    }
    live
  }

  /** The shared, warm CompilerFactory for this sbt session. */
  def factory(): CompilerFactory = ensure().factory

  /** The runtime marklit effects are executed on. */
  def runtime(): Runtime[Any] = ensure().runtime

  /** Release the session's CompilerFactory scope (temp shim jars, cached
    * classloaders). Safe to call when nothing was ever initialized.
    */
  def shutdown(): Unit = synchronized {
    if (live != null) {
      val l = live
      live = null
      try
        Unsafe.unsafe { implicit u =>
          l.runtime.unsafe
            .run(l.scope.close(Exit.unit))
            .getOrThrowFiberFailure()
        }
      catch { case _: Throwable => () }
    }
  }
}
