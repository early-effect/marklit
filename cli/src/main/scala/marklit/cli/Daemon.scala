package marklit.cli

import marklit.cli.DaemonProtocol.*
import zio.*
import zio.json.*

import java.nio.file.Paths

/** Long-lived stdin/stdout JSON-RPC server.
  *
  * Started by `marklit --daemon`. Reads one request per line from stdin,
  * dispatches to a handler, writes one response per line to stdout. The loop
  * ends on any of:
  *
  *   - stdin closes (parent process death) — primary shutdown trigger; the
  *     `Console.readLine` effect surfaces it via `IOException`/EOF.
  *   - idle timeout expires — defense in depth against hung-but-not-dead
  *     parents.
  *   - explicit `shutdown` RPC.
  */
object Daemon:

  /** 15 minutes — long enough for sbt to come back from a `~test` cycle, short
    * enough that an orphaned daemon doesn't sit forever.
    */
  val defaultIdleTimeout: Duration = 15.minutes

  /** The original stdout FileDescriptor, captured before we redirect
    * `System.out` to stderr. The JSON-RPC reply path writes through this
    * reference so user-pipeline `Console.printLine`/`println` calls — which now
    * go through the redirected `System.out` — can't corrupt the wire format no
    * matter what classpath or library does the printing.
    */
  private lazy val protocolOut: java.io.PrintStream =
    new java.io.PrintStream(
      new java.io.FileOutputStream(java.io.FileDescriptor.out),
      /* autoFlush = */ true,
      "UTF-8"
    )

  def run(verbose: Boolean, idleTimeout: Duration): ZIO[Any, Throwable, Unit] =
    val redirect = ZIO.succeed {
      // Capture the original stdout via FileDescriptor.out before reassigning
      // `System.out`. After this, anything that writes to `System.out`
      // (including `Console.printLine` via the default ZIO runtime, third-
      // party libs, scala-compiler diagnostics, etc.) lands on stderr and
      // can't corrupt the JSON-RPC channel.
      val _ =
        protocolOut // force initialization while FD.out still maps to real stdout
      java.lang.System.setOut(java.lang.System.err)
    }
    val banner =
      ZIO.when(verbose)(
        Console.printLineError(
          s"marklit daemon ready (idle-timeout=${idleTimeout.render})"
        )
      )
    val loop =
      serveOneRequest(idleTimeout)
        .repeatWhile(_ == LoopOutcome.Continue)
        .unit

    redirect *>
      banner *>
      loop
        .catchSome {
          // EOF on stdin — parent process closed our pipe.
          case _: java.io.EOFException => ZIO.unit
        }
        // Force JVM exit. The reader fiber's `Console.readLine` blocks on a
        // non-daemon JVM thread that ZIO can't unstick from a kernel-level
        // syscall, and zio-cli installs shutdown hooks that try to drain the
        // runtime — `halt` bypasses both and ends the process immediately,
        // which is correct: by here, the daemon's main fiber has already
        // resolved cleanly and we just need the JVM to follow.
        .ensuring(ZIO.succeed(java.lang.Runtime.getRuntime.halt(0)))

  /** Race [[serveOnce]] against an idle timer. Whichever fiber lands first
    * publishes its outcome to a Promise; the loser is fire-and-forget
    * interrupted.
    *
    * Why a Promise instead of `serveOnce.race(timer)`: the reader fiber's
    * `Console.readLine` ultimately blocks in `System.in.read()` on a non-daemon
    * JVM thread that the kernel can't be interrupted out of. `race` (and
    * `raceFirst`, and `join.race(join)`) all want to either await the loser's
    * interruption or observe both completions in lockstep — empirically that
    * hangs when the loser is wedged in a syscall, even if the winner has
    * already produced a result. Going through `await` (which yields an `Exit`
    * and never throws) and feeding the first landing into a Promise
    * short-circuits that.
    */
  private def serveOneRequest(
      idleTimeout: Duration
  ): ZIO[Any, Throwable, LoopOutcome] =
    for
      readerFiber <- serveOnce.fork
      timerFiber <- ZIO.sleep(idleTimeout).as(LoopOutcome.Stop).fork
      promise <- Promise.make[Throwable, LoopOutcome]
      _ <- publishExit(readerFiber, promise).fork
      _ <- publishExit(timerFiber, promise).fork
      result <- promise.await
      _ <- readerFiber.interrupt.fork
      _ <- timerFiber.interrupt.fork
    yield result

  private def publishExit(
      fiber: Fiber[Throwable, LoopOutcome],
      promise: Promise[Throwable, LoopOutcome]
  ): UIO[Boolean] =
    fiber.await.flatMap {
      case Exit.Success(v)     => promise.succeed(v)
      case Exit.Failure(cause) => promise.failCause(cause)
    }

  private enum LoopOutcome:
    case Continue, Stop

  /** Read one request, handle it, write one response. Returns
    * [[LoopOutcome.Continue]] for normal requests, [[LoopOutcome.Stop]] for
    * `shutdown`. EOF on stdin (parent closed our pipe) surfaces as an
    * `EOFException` that the outer `run` catches.
    */
  private def serveOnce: ZIO[Any, Throwable, LoopOutcome] =
    Console.readLine.flatMap { line =>
      val trimmed = line.trim
      if trimmed.isEmpty then ZIO.succeed(LoopOutcome.Continue)
      else
        trimmed.fromJson[Request] match
          case Left(err) =>
            writeResponse(Response.Err("unknown", s"parse error: $err"))
              .as(LoopOutcome.Continue)
          case Right(Request.Shutdown) =>
            writeResponse(Response.Ok("shutdown")).as(LoopOutcome.Stop)
          case Right(Request.ClearCache(params)) =>
            handleClearCache(params).as(LoopOutcome.Continue)
          case Right(Request.CompileDocument(params)) =>
            handleCompile(params).as(LoopOutcome.Continue)
    }

  private def handleClearCache(
      params: ClearCacheParams
  ): ZIO[Any, Throwable, Unit] =
    ZIO
      .attemptBlocking {
        val root = Paths.get(params.cacheDir)
        if java.nio.file.Files.isDirectory(root) then
          val it = java.nio.file.Files
            .walk(root)
            .sorted(java.util.Comparator.reverseOrder())
          try
            it.forEach { p =>
              if p != root then java.nio.file.Files.deleteIfExists(p): Unit
            }
          finally it.close()
      }
      .either
      .flatMap {
        case Right(_) => writeResponse(Response.Ok("clear-cache"))
        case Left(t)  =>
          val msg = Option(t.getMessage).getOrElse(t.toString)
          writeResponse(Response.Err("clear-cache", msg))
      }

  private def handleCompile(
      params: CompileParams
  ): ZIO[Any, Throwable, Unit] =
    val options = paramsToOptions(params)
    // Run the existing one-shot pipeline. Each compile-document RPC is
    // self-contained today; once the disk cache lands the warm runs will be
    // near-instant because the CompilerFactory is already cached in-memory
    // across `runMarklit` invocations within a single daemon process.
    //
    // We've already redirected `System.out` to stderr at daemon startup, so
    // the inner pipeline's per-file SUCCESS lines, resolver progress, and
    // override notices flow to stderr (visible to the user via the build
    // plugin's INHERIT) and can't land on the JSON-RPC channel.
    MarklitCli
      .runMarklit(options)
      .either
      .flatMap {
        case Right(_) =>
          val files = params.inputFiles.map { f =>
            FileResult(f, success = true, summary = "ok", errors = Nil)
          }
          writeResponse(
            Response.Ok("compile-document", Some(CompileResponse(files)))
          )
        case Left(err) =>
          val msg = Option(err.getMessage).getOrElse(err.toString)
          val files = params.inputFiles.map { f =>
            FileResult(f, success = false, summary = msg, errors = List(msg))
          }
          writeResponse(
            Response.Ok("compile-document", Some(CompileResponse(files)))
          )
      }

  private def paramsToOptions(params: CompileParams): MarklitOptions =
    MarklitOptions(
      inputFiles = params.inputFiles.map(Paths.get(_)),
      outputDir = params.outputDir.map(Paths.get(_)),
      watch = false,
      verbose = params.verbose,
      check = params.check,
      showVersionInOutput = params.showVersionInOutput,
      classpath = params.classpath,
      classpath2 = params.classpath2,
      classpath3 = params.classpath3,
      dependencies = params.dependencies,
      repositories = params.repositories,
      scalaVersion = params.scalaVersion,
      daemon = false,
      idleTimeoutSeconds = None,
      cacheDir = params.cacheDir.map(Paths.get(_))
    )

  private def writeResponse(resp: Response): ZIO[Any, Throwable, Unit] =
    // Write the JSON-RPC reply through the saved-aside stdout reference.
    // `System.out` itself was reassigned to stderr at daemon startup so
    // user-pipeline writes can't corrupt the wire format; `protocolOut`
    // is the *original* stdout, captured via `FileDescriptor.out`.
    ZIO.attempt {
      protocolOut.println(resp.toJson)
      protocolOut.flush()
    }
