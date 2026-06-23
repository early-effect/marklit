package marklit.mill

import marklit.compiler.CompilerFactory
import marklit.{MarklitRun, MarklitRunConfig, MarklitRunResult}
import zio.{Exit, Runtime, Scope, Unsafe}

/** Holds a single warm [[CompilerFactory]] + ZIO runtime for the life of the
  * Mill build server. This is the in-process replacement for the old marklit
  * daemon: `CompilerFactory.layer` extracts the shim jars and caches per-version
  * classloaders, so keeping one instance alive across `marklitGenerate` /
  * `marklitCheck` invocations keeps compilers warm.
  *
  * Mill caches this via a `Task.Worker`; when the worker is displaced Mill calls
  * [[close]], which releases the factory's scope (temp shim jars, classloaders).
  *
  * Execution is serialized on a single monitor because
  * [[marklit.compiler.ScalaCompiler]] redirects `System.out`/`System.err` at the
  * JVM level while a block runs — unsafe under concurrent Mill tasks.
  */
class MarklitWorker extends AutoCloseable {

  private val runtime: Runtime[Any] = Runtime.default

  private val scope: Scope.Closeable =
    Unsafe.unsafe { implicit u =>
      runtime.unsafe.run(Scope.make).getOrThrowFiberFailure()
    }

  private val factory: CompilerFactory =
    Unsafe.unsafe { implicit u =>
      runtime.unsafe
        .run(scope.extend[Any](CompilerFactory.layer.build))
        .getOrThrowFiberFailure()
        .get[CompilerFactory]
    }

  private val lock = new AnyRef

  /** Run marklit over `config` on the warm factory, returning the structured
    * result. Throws on file-read / parse / resolution errors (compile failures
    * are reported as data on the result).
    */
  def run(config: MarklitRunConfig): MarklitRunResult = lock.synchronized {
    Unsafe.unsafe { implicit u =>
      runtime.unsafe
        .run(
          MarklitRun
            .runWith(config, factory)
            .mapError(e => new RuntimeException(e.pretty))
        )
        .getOrThrowFiberFailure()
    }
  }

  override def close(): Unit =
    try
      Unsafe.unsafe { implicit u =>
        runtime.unsafe.run(scope.close(Exit.unit)).getOrThrowFiberFailure()
      }
    catch { case _: Throwable => () }
}
