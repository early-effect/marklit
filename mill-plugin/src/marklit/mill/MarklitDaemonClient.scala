package marklit.mill

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
  * stdin/stdout. One client per Mill `Task.Worker` slot — Mill caches the
  * worker for the life of the daemon (or until inputs invalidate it) and calls
  * [[close]] when displacing or shutting down.
  *
  * Mirrors `marklit.sbt.MarklitDaemonClient`. The two implementations stay
  * intentionally similar — every protocol shape, error path, and respawn rule
  * lives in both. Keep them in sync by hand until the surface grows large
  * enough to justify a shared Java module.
  */
private[mill] final class MarklitDaemonClient(
    marklitJar: os.Path,
    idleTimeoutSeconds: Long,
    log: mill.api.daemon.Logger
) extends AutoCloseable:

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

  /** `clear-cache` RPC — wipe the on-disk cache at `cacheDir`. */
  def clearCache(cacheDir: String): Option[String] = rpcLock.synchronized {
    val daemon = ensureAlive()
    sendRequest(daemon, MarklitJson.clearCacheRequest(cacheDir))
    parseAck(readResponse(daemon))
  }

  /** Mill calls this when the worker is displaced or the build server is
    * shutting down. Best-effort: any IOException is logged and ignored — the
    * daemon's stdout-EOF will end the process anyway.
    */
  override def close(): Unit = rpcLock.synchronized {
    val daemon = active.getAndSet(null)
    if daemon != null then
      try
        sendRequest(daemon, MarklitJson.shutdownRequest)
        readResponse(daemon)
      catch case _: Throwable => ()
      try daemon.process.destroy()
      catch case _: Throwable => ()
  }

  private def ensureAlive(): ActiveDaemon =
    val current = active.get()
    if current != null && current.process.isAlive then current
    else
      if current != null && !current.process.isAlive then
        log.info("[marklit] daemon exited; respawning")
      val fresh = spawn()
      active.set(fresh)
      fresh

  private def spawn(): ActiveDaemon =
    val cmd = new java.util.ArrayList[String]()
    cmd.add("java")
    cmd.add("-jar")
    cmd.add(marklitJar.toString)
    cmd.add("--daemon")
    cmd.add("--idle-timeout")
    cmd.add(idleTimeoutSeconds.toString)

    val pb = new ProcessBuilder(cmd)
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

  private def sendRequest(d: ActiveDaemon, line: String): Unit =
    d.writer.println(line)
    if d.writer.checkError() then
      active.compareAndSet(d, null)
      sys.error("marklit daemon: broken pipe on send")

  private def readResponse(d: ActiveDaemon): String =
    val line = d.reader.readLine()
    if line == null then
      active.compareAndSet(d, null)
      sys.error("marklit daemon: stdout closed before response arrived")
    line

  private def parseAck(line: String): Option[String] =
    MarklitJson.extractStatus(line) match
      case Some("ok")    => None
      case Some("error") =>
        Some(MarklitJson.extractMessage(line).getOrElse("unknown error"))
      case Some(other) => Some(s"unexpected status '$other' in response: $line")
      case None        => Some(s"unparseable response: $line")

  private case class ActiveDaemon(
      process: Process,
      reader: BufferedReader,
      writer: PrintWriter
  )
