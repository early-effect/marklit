package marklit.mill

import mill._
import mill.api.PathRef
import mill.scalalib._

/**
 * A Mill module trait that provides marklit documentation generation capabilities.
 *
 * Mix this into a ScalaModule to add markdown documentation with executable Scala code blocks.
 *
 * Example usage:
 * {{{
 * //| mvnDeps:
 * //| - io.github.russwyte::mill-marklit:0.1.0-SNAPSHOT
 *
 * import marklit.mill.MarklitModule
 *
 * object docs extends ScalaModule with MarklitModule {
 *   def scalaVersion = "3.8.2"
 *   // optionally override marklitSourceDir, marklitVerbose, etc.
 * }
 * }}}
 */
trait MarklitModule extends ScalaModule {

  /**
   * Directory containing markdown source files.
   * Defaults to `moduleDir / "markdown"`.
   */
  def marklitSourceDir: T[PathRef] = Task.Source(moduleDir / "markdown")

  /**
   * Whether to show Scala version in output code blocks.
   * Defaults to true.
   */
  def marklitShowVersion: T[Boolean] = true

  /**
   * Whether to render compile warnings in output code blocks.
   * Defaults to true.
   */
  def marklitShowWarnings: T[Boolean] = true

  /**
   * Enable verbose output from marklit.
   * Defaults to false.
   */
  def marklitVerbose: T[Boolean] = false

  /**
   * Additional classpath entries to pass to marklit.
   * By default, uses this module's compile classpath, filtering out Scala standard library jars
   * to avoid version conflicts (the CLI resolves its own Scala library).
   */
  def marklitClasspath: T[Seq[PathRef]] = Task {
    // Filter out:
    // - marklit-cli jar (contains bundled compiler)
    // - scala-library and scala3-library (CLI resolves these to avoid version conflicts)
    compileClasspath().filterNot { pr =>
      val name = pr.path.last
      name.contains("marklit-cli") ||
      name.startsWith("scala-library") ||
      name.startsWith("scala3-library")
    }
  }

  /**
   * Cross-built sibling modules whose classes should be made available to
   * cross-version code blocks. Typical usage:
   *
   * {{{
   * object core extends Cross[CoreModule](Seq(scala2, scala3))
   * trait CoreModule extends CrossScalaModule
   *
   * object docs extends ScalaModule with MarklitModule {
   *   def scalaVersion = scala3
   *   def moduleDeps = Seq(core(scala3))
   *   override def marklitCrossModuleDeps = core.crossModules
   * }
   * }}}
   *
   * Each entry's compile classpath is bucketed by its `crossScalaVersion`
   * major and forwarded as `--classpath-2` / `--classpath-3` to the CLI.
   * The bucket matching the docs module's own scalaVersion is skipped
   * (those classes are already in `marklitClasspath`).
   */
  def marklitCrossModuleDeps: Seq[CrossModuleBase] = Seq.empty

  /**
   * Per-major classpaths used when a code block opts into a Scala major
   * different from the docs module's own. By default, derived from
   * `marklitCrossModuleDeps`. Override directly to wire arbitrary classpaths
   * (e.g. published artifacts) to a major.
   */
  def marklitMajorClasspaths: T[Map[String, Seq[PathRef]]] = Task {
    val docsMajor = scalaVersion().takeWhile(_ != '.')
    // Task.traverse must see `marklitCrossModuleDeps` directly, not via a
    // local val — Mill's macro inspects the syntactic argument.
    // Use runClasspath so we get this dep's own compiled classes plus its
    // transitive deps. compileClasspath excludes the module's own output
    // (since "compile this module" doesn't need its own classes), which is
    // exactly the wrong answer here — we want to *use* the dep's classes
    // from a separate process.
    val perDepCps: Seq[Seq[PathRef]] =
      Task.traverse(marklitCrossModuleDeps)(_.runClasspath)()
    val pairs: Seq[(String, Seq[PathRef])] =
      marklitCrossModuleDeps.zip(perDepCps).map { case (dep, cp) =>
        val major = dep.crossScalaVersion.takeWhile(_ != '.')
        val filtered = cp.toSeq.filterNot { pr =>
          val name = pr.path.last
          name.contains("marklit-cli") ||
          name.startsWith("scala-library") ||
          name.startsWith("scala3-library")
        }
        major -> filtered
      }
    pairs
      .filter(_._1 != docsMajor)
      .groupBy(_._1)
      .view
      .mapValues(_.flatMap(_._2).distinct)
      .toMap
  }

  /**
   * Whether to talk to a long-lived marklit daemon JVM instead of spawning a
   * fresh subprocess per task. The daemon survives across `marklitGenerate` /
   * `marklitCheck` invocations within one Mill server lifetime, keeping the
   * per-version compiler classloaders warm. Default: true.
   */
  def marklitDaemonEnabled: T[Boolean] = true

  /**
   * Idle timeout (seconds) before an inactive daemon shuts itself down.
   * Default: 900 (15 minutes).
   */
  def marklitDaemonIdleTimeoutSeconds: T[Long] = 900L

  /**
   * Persistent on-disk compile cache directory. Defaults to
   * `moduleDir / "out" / "marklit-cache"`-style under Mill's `Task.dest` for
   * a dedicated worker, so it survives across `marklitGenerate` /
   * `marklitCheck` invocations within and across Mill server lifetimes. Set
   * to `None` to disable caching entirely.
   */
  def marklitCacheDir: T[Option[PathRef]] = Task {
    Some(PathRef(Task.dest / "marklit-cache"))
  }

  /**
   * Long-lived RPC client to a marklit daemon. Cached by Mill for the life of
   * the build server (or until inputs invalidate it); Mill calls
   * `MarklitDaemonClient.close()` on displacement, which sends a `shutdown`
   * RPC and tears down the helper JVM.
   *
   * Inputs are intentionally narrow — `marklitCliJar` and the idle-timeout
   * setting. Changing the docs module's classpath or sources should NOT spin
   * up a new daemon; the per-request `compile-document` payload carries those.
   */
  def marklitDaemon: Worker[MarklitDaemonClient] = Task.Worker {
    new MarklitDaemonClient(
      marklitCliJar().path,
      marklitDaemonIdleTimeoutSeconds(),
      Task.log
    )
  }

  /**
   * Path to the marklit CLI jar.
   * By default, extracts the bundled jar from plugin resources.
   */
  private def marklitCliJar: T[PathRef] = Task {
    val dest = Task.dest / "marklit-cli.jar"
    // Try multiple classloader strategies to find the resource
    val resourceStream = Option(classOf[MarklitModule].getResourceAsStream("/marklit-cli.jar"))
      .orElse(Option(classOf[MarklitModule].getClassLoader.getResourceAsStream("marklit-cli.jar")))
      .orElse(Option(Thread.currentThread.getContextClassLoader.getResourceAsStream("marklit-cli.jar")))
      .getOrElse {
        throw new Exception(
          "marklit-cli.jar not found in plugin resources. " +
            "Plugin may not be packaged correctly."
        )
      }
    try {
      os.write(dest, resourceStream)
    } finally {
      resourceStream.close()
    }
    PathRef(dest)
  }

  /**
   * Generate markdown documentation with executed code output.
   * Output is written to the task destination directory.
   */
  def marklitGenerate: T[Seq[PathRef]] = Task {
    val sourceDir = marklitSourceDir().path
    val targetDir = Task.dest
    val cliJar = marklitCliJar().path
    val cpEntries = marklitClasspath().map(_.path.toString)
    val majorCps = marklitMajorClasspaths().view
      .mapValues(_.map(_.path.toString))
      .toMap
    val showVersion = marklitShowVersion()
    val showWarnings = marklitShowWarnings()
    val verbose = marklitVerbose()
    val scalaVer = scalaVersion()
    val cacheDirOpt = marklitCacheDir().map(_.path.toString)
    val daemonOpt =
      if (marklitDaemonEnabled()) Some(marklitDaemon()) else None

    if (!os.exists(sourceDir)) {
      Task.log.info(s"[marklit] No source directory: $sourceDir")
      Seq.empty[PathRef]
    } else {
      val sources = os.walk(sourceDir).filter(_.ext == "md").toSeq
      if (sources.isEmpty) {
        Task.log.info(s"[marklit] No markdown files in $sourceDir")
        Seq.empty[PathRef]
      } else {
        Task.log.info(s"[marklit] Generating ${sources.size} file(s)...")

        runMarklit(
          cliJar = cliJar,
          sources = sources,
          outputDir = Some(targetDir),
          classpath = cpEntries,
          majorClasspaths = majorCps,
          scalaVer = scalaVer,
          showVersion = showVersion,
          showWarnings = showWarnings,
          verbose = verbose,
          check = false,
          daemon = daemonOpt,
          taskLabel = "generation",
          log = Task.log,
          cacheDir = cacheDirOpt
        )

        sources.map(source => PathRef(targetDir / source.last))
      }
    }
  }

  /**
   * Check that markdown code blocks compile without generating output.
   */
  def marklitCheck: T[Unit] = Task {
    val sourceDir = marklitSourceDir().path
    val cliJar = marklitCliJar().path
    val cpEntries = marklitClasspath().map(_.path.toString)
    val majorCps = marklitMajorClasspaths().view
      .mapValues(_.map(_.path.toString))
      .toMap
    val verbose = marklitVerbose()
    val scalaVer = scalaVersion()
    val cacheDirOpt = marklitCacheDir().map(_.path.toString)
    val daemonOpt =
      if (marklitDaemonEnabled()) Some(marklitDaemon()) else None

    if (!os.exists(sourceDir)) {
      Task.log.info(s"[marklit] No source directory: $sourceDir")
    } else {
      val sources = os.walk(sourceDir).filter(_.ext == "md").toSeq
      if (sources.isEmpty) {
        Task.log.info(s"[marklit] No markdown files in $sourceDir")
      } else {
        Task.log.info(s"[marklit] Checking ${sources.size} file(s)...")

        runMarklit(
          cliJar = cliJar,
          sources = sources,
          outputDir = None,
          classpath = cpEntries,
          majorClasspaths = majorCps,
          scalaVer = scalaVer,
          showVersion = true,
          showWarnings = true,
          verbose = verbose,
          check = true,
          daemon = daemonOpt,
          taskLabel = "check",
          log = Task.log,
          cacheDir = cacheDirOpt
        )
      }
    }
  }

  /** Send a compile or check request through the daemon when one is provided;
    * fall back to a one-shot subprocess on transport failure. Mirrors the
    * sbt-plugin's MarklitRunner.runViaDaemon shape so behavior is consistent
    * across build tools.
    */
  private def runMarklit(
      cliJar: os.Path,
      sources: Seq[os.Path],
      outputDir: Option[os.Path],
      classpath: Seq[String],
      majorClasspaths: Map[String, Seq[String]],
      scalaVer: String,
      showVersion: Boolean,
      showWarnings: Boolean,
      verbose: Boolean,
      check: Boolean,
      daemon: Option[MarklitDaemonClient],
      taskLabel: String,
      log: mill.api.daemon.Logger,
      cacheDir: Option[String]
  ): Unit = {
    val sep = java.io.File.pathSeparator
    val cpStr = if (classpath.isEmpty) None else Some(classpath.mkString(sep))
    def cpFor(major: String) =
      majorClasspaths.get(major).map(_.mkString(sep)).filter(_.nonEmpty)

    daemon match {
      case Some(client) =>
        try {
          val ack = client.compileDocument(
            inputFiles = sources.map(_.toString),
            outputDir = outputDir.map(_.toString),
            verbose = verbose,
            check = check,
            showVersionInOutput = showVersion,
            showWarningsInOutput = showWarnings,
            classpath = cpStr,
            classpath2 = cpFor("2"),
            classpath3 = cpFor("3"),
            scalaVersion = Some(scalaVer),
            cacheDir = cacheDir
          )
          ack match {
            case None => ()
            case Some(message) =>
              throw new Exception(s"marklit $taskLabel failed: $message")
          }
        } catch {
          case e: Exception if e.getMessage != null && e.getMessage.startsWith(
                "marklit " + taskLabel
              ) =>
            throw e
          case t: Throwable =>
            log.warn(
              s"[marklit] daemon RPC failed (${t.getMessage}); falling back to one-shot"
            )
            runOneShot(
              cliJar,
              sources,
              outputDir,
              classpath,
              majorClasspaths,
              scalaVer,
              showVersion,
              showWarnings,
              verbose,
              check,
              taskLabel,
              log,
              cacheDir
            )
        }
      case None =>
        runOneShot(
          cliJar,
          sources,
          outputDir,
          classpath,
          majorClasspaths,
          scalaVer,
          showVersion,
          showWarnings,
          verbose,
          check,
          taskLabel,
          log,
          cacheDir
        )
    }
  }

  private def runOneShot(
      cliJar: os.Path,
      sources: Seq[os.Path],
      outputDir: Option[os.Path],
      classpath: Seq[String],
      majorClasspaths: Map[String, Seq[String]],
      scalaVer: String,
      showVersion: Boolean,
      showWarnings: Boolean,
      verbose: Boolean,
      check: Boolean,
      taskLabel: String,
      log: mill.api.daemon.Logger,
      cacheDir: Option[String]
  ): Unit = {
    val sep = java.io.File.pathSeparator
    val args = Seq.newBuilder[String]
    args += "java"
    args += "-jar"
    args += cliJar.toString
    outputDir match {
      case Some(dir) =>
        args += "--out"
        args += dir.toString
      case None =>
        args += "--check"
    }
    args += "--scala-version"
    args += scalaVer
    if (classpath.nonEmpty) {
      args += "--classpath"
      args += classpath.mkString(sep)
    }
    majorClasspaths.foreach { case (major, cps) =>
      if (cps.nonEmpty) {
        args += s"--classpath-$major"
        args += cps.mkString(sep)
      }
    }
    cacheDir.foreach { d =>
      args += "--cache-dir"
      args += d
    }
    if (outputDir.isDefined && !showVersion) args += "--no-show-version"
    if (outputDir.isDefined) {
      args += "--show-warnings"
      args += showWarnings.toString
    }
    if (verbose) args += "--verbose"
    args ++= sources.map(_.toString)

    val result =
      os.proc(args.result()).call(check = false, stderr = os.Pipe, stdout = os.Pipe)

    if (result.exitCode != 0) {
      log.error(s"marklit stdout: ${result.out.text()}")
      log.error(s"marklit stderr: ${result.err.text()}")
      throw new Exception(s"marklit $taskLabel failed with exit code ${result.exitCode}")
    }
  }
}
