package marklit.sbt

import marklit.{MarklitRun, MarklitRunConfig, MarklitRunResult}
import sbt.{Logger, MessageOnlyException}
import zio.Unsafe

/** Bridges the sbt plugin's task values to the in-process marklit orchestrator
  * ([[marklit.MarklitRun]]) and translates the structured result back into sbt
  * logging + failure semantics.
  *
  * Execution is serialized on a single monitor: [[marklit.compiler.ScalaCompiler]]
  * redirects `System.out`/`System.err` at the JVM level while a block executes,
  * which is unsafe if two marklit tasks run concurrently inside the same sbt
  * JVM. The old daemon serialized via its RPC lock; this preserves that.
  */
object MarklitInProcess {

  // Process-wide lock: only one marklit run touches System.out at a time.
  private val executionLock = new AnyRef

  /** Run marklit over `config` on the warm session factory. Logs the facade's
    * notices and per-file summaries, and throws [[MessageOnlyException]] (the
    * sbt idiom for a clean task failure) when any file failed.
    *
    * @param taskLabel
    *   "compilation" / "generation" — used in the failure message.
    */
  def run(
      config: MarklitRunConfig,
      taskLabel: String,
      log: Logger
  ): MarklitRunResult = executionLock.synchronized {
    val runtime = MarklitSession.runtime()
    val factory = MarklitSession.factory()

    // MarklitRun fails with MarklitError (file-read / parse / resolution
    // failures); compile failures are data on the result, handled below. Map
    // the typed error to a MessageOnlyException so the task fails cleanly.
    val result =
      Unsafe.unsafe { implicit u =>
        runtime.unsafe
          .run(
            MarklitRun
              .runWith(config, factory)
              .mapError(e => new MessageOnlyException(e.pretty))
          )
          .getOrThrowFiberFailure()
      }

    // Surface informational notices (the facade already applied the verbose
    // gate when choosing which lines to include).
    result.notices.foreach(n => log.info(s"[marklit] $n"))

    // Per-file summary + (verbose) diagnostic detail.
    result.files.foreach { file =>
      log.info(s"[marklit] ${file.summary}")
      if (config.verbose) {
        file.blockErrors.foreach { case (loc, msg) =>
          log.error(s"[marklit]   $loc: $msg")
        }
        file.failedCompiles.foreach { case (loc, msg) =>
          log.error(s"[marklit]   $loc: $msg")
        }
      }
    }

    if (!result.success) {
      throw new MessageOnlyException(
        s"marklit $taskLabel failed: ${result.failedCount} file(s) failed"
      )
    }

    result
  }
}
