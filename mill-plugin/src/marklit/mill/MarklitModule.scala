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
    val cp = marklitClasspath().map(_.path.toString).mkString(java.io.File.pathSeparator)
    val majorCps = marklitMajorClasspaths()
    val showVersion = marklitShowVersion()
    val verbose = marklitVerbose()
    val scalaVer = scalaVersion()

    if (!os.exists(sourceDir)) {
      Task.log.info(s"[marklit] No source directory: $sourceDir")
      Seq.empty[PathRef]
    } else {
      val sources = os.walk(sourceDir).filter(_.ext == "md")
      if (sources.isEmpty) {
        Task.log.info(s"[marklit] No markdown files in $sourceDir")
        Seq.empty[PathRef]
      } else {
        Task.log.info(s"[marklit] Generating ${sources.size} file(s)...")

        val args = Seq.newBuilder[String]
        args += "java"
        args += "-jar"
        args += cliJar.toString
        args += "--out"
        args += targetDir.toString
        args += "--scala-version"
        args += scalaVer
        if (cp.nonEmpty) {
          args += "--classpath"
          args += cp
        }
        majorCps.foreach { case (major, cps) =>
          if (cps.nonEmpty) {
            args += s"--classpath-$major"
            args += cps.map(_.path.toString).mkString(java.io.File.pathSeparator)
          }
        }
        if (!showVersion) {
          args += "--no-show-version"
        }
        if (verbose) {
          args += "--verbose"
        }
        args ++= sources.map(_.toString)

        val result = os.proc(args.result()).call(check = false, stderr = os.Pipe, stdout = os.Pipe)

        if (result.exitCode != 0) {
          Task.log.error(s"marklit stdout: ${result.out.text()}")
          Task.log.error(s"marklit stderr: ${result.err.text()}")
          throw new Exception(s"marklit generation failed with exit code ${result.exitCode}")
        }

        sources.map { source =>
          PathRef(targetDir / source.last)
        }
      }
    }
  }

  /**
   * Check that markdown code blocks compile without generating output.
   */
  def marklitCheck: T[Unit] = Task {
    val sourceDir = marklitSourceDir().path
    val cliJar = marklitCliJar().path
    val cp = marklitClasspath().map(_.path.toString).mkString(java.io.File.pathSeparator)
    val majorCps = marklitMajorClasspaths()
    val verbose = marklitVerbose()
    val scalaVer = scalaVersion()

    if (!os.exists(sourceDir)) {
      Task.log.info(s"[marklit] No source directory: $sourceDir")
    } else {
      val sources = os.walk(sourceDir).filter(_.ext == "md")
      if (sources.isEmpty) {
        Task.log.info(s"[marklit] No markdown files in $sourceDir")
      } else {
        Task.log.info(s"[marklit] Checking ${sources.size} file(s)...")

        val args = Seq.newBuilder[String]
        args += "java"
        args += "-jar"
        args += cliJar.toString
        args += "--check"
        args += "--scala-version"
        args += scalaVer
        if (cp.nonEmpty) {
          args += "--classpath"
          args += cp
        }
        majorCps.foreach { case (major, cps) =>
          if (cps.nonEmpty) {
            args += s"--classpath-$major"
            args += cps.map(_.path.toString).mkString(java.io.File.pathSeparator)
          }
        }
        if (verbose) {
          args += "--verbose"
        }
        args ++= sources.map(_.toString)

        val result = os.proc(args.result()).call(check = false)

        if (result.exitCode != 0) {
          throw new Exception(s"marklit check failed with exit code ${result.exitCode}")
        }
      }
    }
  }
}
