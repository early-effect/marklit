package marklit.mill

import mill._
import mill.api.PathRef
import mill.scalalib._
import marklit.MarklitRunConfig

/** A Mill module trait that provides marklit documentation generation
  * capabilities.
  *
  * Mix this into a ScalaModule to add markdown documentation with executable
  * Scala code blocks. marklit's compiler runs IN-PROCESS (the trait depends on
  * marklit-compiler) — no CLI fat jar, no daemon subprocess.
  *
  * Example usage:
  * {{{
  * //| mvnDeps:
  * //| - io.github.russwyte::mill-marklit:0.1.0-LOCAL
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

  /** Directory containing markdown source files. Defaults to
    * `moduleDir / "markdown"`.
    */
  def marklitSourceDir: T[PathRef] = Task.Source(moduleDir / "markdown")

  /** Whether to show Scala version in output code blocks. Defaults to true.
    */
  def marklitShowVersion: T[Boolean] = true

  /** Whether to render compile warnings in output code blocks. Defaults to
    * true.
    */
  def marklitShowWarnings: T[Boolean] = true

  /** Enable verbose output from marklit. Defaults to false.
    */
  def marklitVerbose: T[Boolean] = false

  /** Share scope across all anonymous code blocks in each file. When true, the
    * first anonymous block in a file (per Scala version) opens an implicit
    * page-scope and every subsequent anonymous block extends it with `append`,
    * so values defined in earlier blocks remain visible to later ones. Blocks
    * with explicit `id=`/`extends=`, or with semantic-only modifiers
    * (`passthrough`, `shared`, `fail`, `crash`, `warn`), bypass the rewrite.
    * Defaults to false (each anonymous block isolated).
    */
  def marklitPageScope: T[Boolean] = false

  /** Additional classpath entries to pass to marklit. By default, uses this
    * module's compile classpath, filtering out Scala standard library jars to
    * avoid version conflicts (marklit resolves its own Scala library).
    */
  def marklitClasspath: T[Seq[PathRef]] = Task {
    // Filter out scala-library and scala3-library (marklit resolves these per
    // requested version to avoid version conflicts).
    compileClasspath().filterNot { pr =>
      val name = pr.path.last
      name.startsWith("scala-library") ||
      name.startsWith("scala3-library")
    }
  }

  /** Cross-built sibling modules whose classes should be made available to
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
    * major and forwarded to cross-version blocks. The bucket matching the docs
    * module's own scalaVersion is skipped (those classes are already in
    * `marklitClasspath`).
    */
  def marklitCrossModuleDeps: Seq[CrossModuleBase] = Seq.empty

  /** Per-major classpaths used when a code block opts into a Scala major
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
    // exactly the wrong answer here — we want to *use* the dep's classes.
    val perDepCps: Seq[Seq[PathRef]] =
      Task.traverse(marklitCrossModuleDeps)(_.runClasspath)()
    val pairs: Seq[(String, Seq[PathRef])] =
      marklitCrossModuleDeps.zip(perDepCps).map { case (dep, cp) =>
        val major = dep.crossScalaVersion.takeWhile(_ != '.')
        val filtered = cp.toSeq.filterNot { pr =>
          val name = pr.path.last
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

  /** Persistent on-disk compile cache directory. Defaults to a dedicated
    * worker dest under Mill's `out/`, so it survives across `marklitGenerate` /
    * `marklitCheck` invocations within and across Mill server lifetimes. Set to
    * `None` to disable caching entirely.
    */
  def marklitCacheDir: T[Option[PathRef]] = Task {
    Some(PathRef(Task.dest / "marklit-cache"))
  }

  /** Long-lived in-process marklit worker. Holds one warm CompilerFactory + ZIO
    * runtime for the life of the Mill build server (Mill calls `close()` on
    * displacement). Replaces the old out-of-process daemon worker.
    *
    * Inputs are intentionally empty — the per-request config carries sources
    * and classpaths, so changing them must NOT spin up a new worker.
    */
  def marklitWorker: Worker[MarklitWorker] = Task.Worker {
    new MarklitWorker
  }

  /** Generate markdown documentation with executed code output. Output is
    * written to the task destination directory.
    */
  def marklitGenerate: T[Seq[PathRef]] = Task {
    val sourceDir = marklitSourceDir().path
    val targetDir = Task.dest
    val cpEntries = marklitClasspath().map(_.path.toString)
    val majorCps = marklitMajorClasspaths().view
      .mapValues(_.map(_.path.toString))
      .toMap
    val showVersion = marklitShowVersion()
    val showWarnings = marklitShowWarnings()
    val verbose = marklitVerbose()
    val scalaVer = scalaVersion()
    val cacheDirOpt = marklitCacheDir().map(_.path)
    val pageScope = marklitPageScope()
    val worker = marklitWorker()

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
          worker = worker,
          sources = sources,
          outputDir = Some(targetDir),
          classpath = cpEntries,
          majorClasspaths = majorCps,
          scalaVer = scalaVer,
          showVersion = showVersion,
          showWarnings = showWarnings,
          verbose = verbose,
          check = false,
          taskLabel = "generation",
          log = Task.log,
          cacheDir = cacheDirOpt,
          pageScope = pageScope
        )

        sources.map(source => PathRef(targetDir / source.last))
      }
    }
  }

  /** Check that markdown code blocks compile without generating output.
    */
  def marklitCheck: T[Unit] = Task {
    val sourceDir = marklitSourceDir().path
    val cpEntries = marklitClasspath().map(_.path.toString)
    val majorCps = marklitMajorClasspaths().view
      .mapValues(_.map(_.path.toString))
      .toMap
    val verbose = marklitVerbose()
    val scalaVer = scalaVersion()
    val cacheDirOpt = marklitCacheDir().map(_.path)
    val pageScope = marklitPageScope()
    val worker = marklitWorker()

    if (!os.exists(sourceDir)) {
      Task.log.info(s"[marklit] No source directory: $sourceDir")
    } else {
      val sources = os.walk(sourceDir).filter(_.ext == "md").toSeq
      if (sources.isEmpty) {
        Task.log.info(s"[marklit] No markdown files in $sourceDir")
      } else {
        Task.log.info(s"[marklit] Checking ${sources.size} file(s)...")

        runMarklit(
          worker = worker,
          sources = sources,
          outputDir = None,
          classpath = cpEntries,
          majorClasspaths = majorCps,
          scalaVer = scalaVer,
          showVersion = true,
          showWarnings = true,
          verbose = verbose,
          check = true,
          taskLabel = "check",
          log = Task.log,
          cacheDir = cacheDirOpt,
          pageScope = pageScope
        )
      }
    }
  }

  /** Build a run config and execute it in-process on the warm worker, logging
    * the facade's notices/summaries and failing the task on any file failure.
    */
  private def runMarklit(
      worker: MarklitWorker,
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
      cacheDir: Option[os.Path],
      pageScope: Boolean
  ): Unit = {
    val config = MarklitRunConfig(
      inputFiles = sources.map(_.toNIO).toVector,
      outputDir = outputDir.map(_.toNIO),
      scalaVersion = Some(scalaVer),
      classpath = classpath.toVector,
      classpath2 = majorClasspaths.getOrElse("2", Seq.empty).toVector,
      classpath3 = majorClasspaths.getOrElse("3", Seq.empty).toVector,
      cacheDir = cacheDir.map(_.toNIO),
      pageScope = pageScope,
      check = check,
      showVersion = showVersion,
      showWarnings = showWarnings,
      verbose = verbose
    )

    val result = worker.run(config)

    result.notices.foreach(n => log.info(s"[marklit] $n"))
    result.files.foreach { file =>
      log.info(s"[marklit] ${file.summary}")
      if (verbose) {
        file.blockErrors.foreach { case (loc, msg) =>
          log.error(s"[marklit]   $loc: $msg")
        }
        file.failedCompiles.foreach { case (loc, msg) =>
          log.error(s"[marklit]   $loc: $msg")
        }
      }
    }

    if (!result.success) {
      throw new Exception(
        s"marklit $taskLabel failed: ${result.failedCount} file(s) failed"
      )
    }
  }
}
