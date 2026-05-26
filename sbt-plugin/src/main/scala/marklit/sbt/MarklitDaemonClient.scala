package marklit.sbt

import sbt._

import java.io.{
  BufferedReader,
  BufferedWriter,
  InputStreamReader,
  OutputStreamWriter,
  PrintWriter
}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/** Long-lived marklit daemon process and a thin RPC client around its
  * stdin/stdout. One client per sbt session — the same JVM is reused across
  * `marklitGenerate` / `marklitCompile` invocations so per-version compiler
  * classloaders stay warm.
  *
  * Lifecycle:
  *   - First RPC lazy-spawns the daemon and stores the [[Process]] handle.
  *   - Subsequent RPCs reuse the same process.
  *   - If a prior process died (parent exit, idle timeout, crash), the next RPC
  *     respawns transparently.
  *   - [[shutdown]] sends a graceful `shutdown` RPC and closes the streams.
  *
  * Thread-safe: the active [[Process]] is held in an [[AtomicReference]] and
  * one mutex serializes the request/response window so two concurrent tasks
  * can't interleave RPCs on the same pipe.
  */
private[sbt] final class MarklitDaemonClient(
    marklitJar: File,
    idleTimeoutSeconds: Long,
    log: Logger
) {

  private val active = new AtomicReference[ActiveDaemon](null)
  private val rpcLock = new AnyRef

  /** Send a `compile-document` RPC. Returns `None` on success, `Some(msg)` on
    * protocol-level error. Throws on transport failure (broken pipe, malformed
    * response, daemon refused to spawn) — the caller catches and falls back to
    * one-shot mode.
    */
  def compileDocument(
      inputFiles: Seq[String],
      outputDir: Option[String],
      verbose: Boolean,
      check: Boolean,
      showVersionInOutput: Boolean,
      showWarningsInOutput: Boolean,
      classpath: Option[String],
      classpath2: Option[String],
      classpath3: Option[String],
      scalaVersion: Option[String],
      cacheDir: Option[String],
      pageScope: Boolean
  ): Option[String] = rpcLock.synchronized {
    val daemon = ensureAlive()
    val request = MarklitJson.compileDocumentRequest(
      inputFiles = inputFiles,
      outputDir = outputDir,
      verbose = verbose,
      check = check,
      showVersionInOutput = showVersionInOutput,
      showWarningsInOutput = showWarningsInOutput,
      classpath = classpath,
      classpath2 = classpath2,
      classpath3 = classpath3,
      scalaVersion = scalaVersion,
      cacheDir = cacheDir,
      pageScope = pageScope
    )
    sendRequest(daemon, request)
    parseAck(readResponse(daemon))
  }

  /** Send a `clear-cache` RPC for the given on-disk cache directory. Returns
    * `None` on success, `Some(msg)` on protocol-level error. Throws on
    * transport failure (caller falls back to filesystem-level cleanup).
    */
  def clearCache(cacheDir: String): Option[String] = rpcLock.synchronized {
    val daemon = ensureAlive()
    sendRequest(daemon, MarklitJson.clearCacheRequest(cacheDir))
    parseAck(readResponse(daemon))
  }

  /** Graceful shutdown. Best-effort: any IOException is logged and ignored (the
    * daemon's stdout-EOF will end the process anyway).
    */
  def shutdown(): Unit = rpcLock.synchronized {
    val daemon = active.getAndSet(null)
    if (daemon != null) {
      try {
        sendRequest(daemon, MarklitJson.shutdownRequest)
        // Drain the ack so the daemon's `Console.printLine` doesn't EPIPE
        // before its own loop exits.
        readResponse(daemon)
      } catch {
        case _: Throwable => // best-effort — process is going away regardless
      }
      try daemon.process.destroy()
      catch { case _: Throwable => () }
    }
  }

  private def ensureAlive(): ActiveDaemon = {
    val current = active.get()
    if (current != null && current.process.isAlive) current
    else {
      // Either uninitialized or the prior daemon died (idle timeout, crash,
      // user kill). Spawn a fresh one.
      if (current != null && !current.process.isAlive) {
        log.info("[marklit] daemon exited; respawning")
      }
      val fresh = spawn()
      active.set(fresh)
      fresh
    }
  }

  private def spawn(): ActiveDaemon = {
    val cmd = new java.util.ArrayList[String]()
    cmd.add("java")
    cmd.add("-jar")
    cmd.add(marklitJar.getAbsolutePath)
    cmd.add("--daemon")
    cmd.add("--idle-timeout")
    cmd.add(idleTimeoutSeconds.toString)

    val pb = new ProcessBuilder(cmd)
    // Inherit stderr so the daemon's diagnostic output (per-file SUCCESS/
    // FAIL lines, override notifications) flows to the user's console.
    // stdin/stdout are pipes for the JSON-RPC channel.
    pb.redirectError(ProcessBuilder.Redirect.INHERIT)
    pb.redirectInput(ProcessBuilder.Redirect.PIPE)
    pb.redirectOutput(ProcessBuilder.Redirect.PIPE)

    val process = pb.start()
    val writer = new PrintWriter(
      new BufferedWriter(
        new OutputStreamWriter(process.getOutputStream, StandardCharsets.UTF_8)
      ),
      /* autoFlush = */ true
    )
    val reader = new BufferedReader(
      new InputStreamReader(process.getInputStream, StandardCharsets.UTF_8)
    )

    log.debug(
      s"[marklit] spawned daemon (pid=${process.pid()}, idle-timeout=${idleTimeoutSeconds}s)"
    )
    ActiveDaemon(process, reader, writer)
  }

  private def sendRequest(d: ActiveDaemon, line: String): Unit = {
    d.writer.println(line)
    if (d.writer.checkError()) {
      // PrintWriter swallows IOExceptions and only surfaces them via
      // checkError. Treat as broken pipe — clear the slot so the next call
      // respawns.
      active.compareAndSet(d, null)
      sys.error("marklit daemon: broken pipe on send")
    }
  }

  private def readResponse(d: ActiveDaemon): String = {
    val line = d.reader.readLine()
    if (line == null) {
      active.compareAndSet(d, null)
      sys.error("marklit daemon: stdout closed before response arrived")
    }
    line
  }

  private def parseAck(line: String): Option[String] = {
    MarklitJson.extractStatus(line) match {
      case Some("ok")    => None
      case Some("error") =>
        Some(MarklitJson.extractMessage(line).getOrElse("unknown error"))
      case Some(other) => Some(s"unexpected status '$other' in response: $line")
      case None        => Some(s"unparseable response: $line")
    }
  }

  private case class ActiveDaemon(
      process: Process,
      reader: BufferedReader,
      writer: PrintWriter
  )
}
