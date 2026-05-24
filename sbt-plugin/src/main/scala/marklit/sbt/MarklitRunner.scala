package marklit.sbt

import sbt._
import scala.sys.process._

/** Runner for marklit operations - thin wrapper around marklit CLI.
  */
object MarklitRunner {

  /** Run marklit CLI with given arguments. Returns exit code.
    */
  def run(
      marklitJar: File,
      args: Seq[String],
      log: Logger
  ): Int = {
    val cmd = Seq("java", "-jar", marklitJar.getAbsolutePath) ++ args

    log.debug(s"[marklit] Running: ${cmd.mkString(" ")}")

    val exitCode = Process(cmd) ! ProcessLogger(
      out => log.info(s"[marklit] $out"),
      err => log.error(s"[marklit] $err")
    )

    exitCode
  }

  private def scalaVersionArg(scalaVersion: String): Seq[String] =
    Seq("--scala-version", scalaVersion)

  private def classpathArg(flag: String, classpath: Seq[File]): Seq[String] =
    if (classpath.nonEmpty)
      Seq(
        flag,
        classpath.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator)
      )
    else Seq.empty

  private def majorClasspathArgs(
      majorClasspaths: Map[String, Seq[File]]
  ): Seq[String] =
    majorClasspaths.toSeq.flatMap { case (major, cp) =>
      classpathArg(s"--classpath-$major", cp)
    }

  private def cacheDirArg(cacheDir: Option[File]): Seq[String] =
    cacheDir.toSeq.flatMap(d => Seq("--cache-dir", d.getAbsolutePath))

  /** Run marklit in check mode.
    */
  def check(
      marklitJar: File,
      sources: Seq[File],
      classpath: Seq[File],
      scalaVersion: String,
      verbose: Boolean,
      log: Logger,
      majorClasspaths: Map[String, Seq[File]] = Map.empty,
      daemon: Option[MarklitDaemonClient] = None,
      cacheDir: Option[File] = None
  ): Int = daemon match {
    case Some(client) =>
      runViaDaemon(
        client,
        sources = sources,
        outputDir = None,
        classpath = classpath,
        scalaVersion = scalaVersion,
        showVersion = true,
        check = true,
        verbose = verbose,
        log = log,
        majorClasspaths = majorClasspaths,
        marklitJar = marklitJar,
        cacheDir = cacheDir
      )
    case None =>
      val args = Seq("--check") ++
        scalaVersionArg(scalaVersion) ++
        classpathArg("--classpath", classpath) ++
        majorClasspathArgs(majorClasspaths) ++
        cacheDirArg(cacheDir) ++
        (if (verbose) Seq("--verbose") else Seq.empty) ++
        sources.map(_.getAbsolutePath)
      run(marklitJar, args, log)
  }

  /** Run marklit to generate output.
    */
  def generate(
      marklitJar: File,
      sources: Seq[File],
      outputDir: File,
      classpath: Seq[File],
      scalaVersion: String,
      showVersion: Boolean,
      verbose: Boolean,
      log: Logger,
      majorClasspaths: Map[String, Seq[File]] = Map.empty,
      daemon: Option[MarklitDaemonClient] = None,
      cacheDir: Option[File] = None
  ): Int = daemon match {
    case Some(client) =>
      runViaDaemon(
        client,
        sources = sources,
        outputDir = Some(outputDir),
        classpath = classpath,
        scalaVersion = scalaVersion,
        showVersion = showVersion,
        check = false,
        verbose = verbose,
        log = log,
        majorClasspaths = majorClasspaths,
        marklitJar = marklitJar,
        cacheDir = cacheDir
      )
    case None =>
      val args = Seq("--out", outputDir.getAbsolutePath) ++
        scalaVersionArg(scalaVersion) ++
        classpathArg("--classpath", classpath) ++
        majorClasspathArgs(majorClasspaths) ++
        cacheDirArg(cacheDir) ++
        (if (showVersion) Seq.empty else Seq("--no-show-version")) ++
        (if (verbose) Seq("--verbose") else Seq.empty) ++
        sources.map(_.getAbsolutePath)
      run(marklitJar, args, log)
  }

  /** Send the request as a JSON-RPC `compile-document` to a long-lived daemon.
    * On any transport-level failure (broken pipe, malformed response, daemon
    * won't spawn), log the cause and fall back to a one-shot subprocess so the
    * build still produces output.
    */
  private def runViaDaemon(
      client: MarklitDaemonClient,
      sources: Seq[File],
      outputDir: Option[File],
      classpath: Seq[File],
      scalaVersion: String,
      showVersion: Boolean,
      check: Boolean,
      verbose: Boolean,
      log: Logger,
      majorClasspaths: Map[String, Seq[File]],
      marklitJar: File,
      cacheDir: Option[File]
  ): Int = {
    val cpStr = pathString(classpath)
    val cp2 = majorClasspaths.get("2").flatMap(pathString)
    val cp3 = majorClasspaths.get("3").flatMap(pathString)
    try {
      client.compileDocument(
        inputFiles = sources.map(_.getAbsolutePath),
        outputDir = outputDir.map(_.getAbsolutePath),
        verbose = verbose,
        check = check,
        showVersionInOutput = showVersion,
        classpath = cpStr,
        classpath2 = cp2,
        classpath3 = cp3,
        scalaVersion = Some(scalaVersion),
        cacheDir = cacheDir.map(_.getAbsolutePath)
      ) match {
        case None =>
          0
        case Some(message) =>
          log.error(s"[marklit] daemon error: $message")
          1
      }
    } catch {
      case t: Throwable =>
        log.warn(
          s"[marklit] daemon RPC failed (${t.getMessage}); falling back to one-shot"
        )
        // One-shot fallback uses the same args we'd have built normally.
        val baseArgs = scalaVersionArg(scalaVersion) ++
          classpathArg("--classpath", classpath) ++
          majorClasspathArgs(majorClasspaths) ++
          cacheDirArg(cacheDir) ++
          (if (verbose) Seq("--verbose") else Seq.empty) ++
          sources.map(_.getAbsolutePath)
        val args = outputDir match {
          case Some(dir) =>
            Seq("--out", dir.getAbsolutePath) ++
              (if (showVersion) Seq.empty else Seq("--no-show-version")) ++
              baseArgs
          case None =>
            Seq("--check") ++ baseArgs
        }
        run(marklitJar, args, log)
    }
  }

  private def pathString(cp: Seq[File]): Option[String] =
    if (cp.isEmpty) None
    else
      Some(cp.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator))
}
