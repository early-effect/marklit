package marklit.sbt

import sbt._
import sbt.Keys._

object MarklitPlugin extends AutoPlugin {

  object autoImport {
    // Settings
    val marklitSourceDirectory =
      settingKey[File]("Directory containing markdown source files")
    val marklitTargetDirectory =
      settingKey[File]("Directory for generated markdown output")
    val marklitShowVersion =
      settingKey[Boolean]("Show Scala version in output blocks")
    val marklitVerbose = settingKey[Boolean]("Enable verbose output")

    // Map from Scala major ("2", "3") to a per-major classpath. When a
    // markdown block opts into a cross-version compile (e.g.
    // `marklit:scala=2.13.16` from a project whose own scalaVersion is
    // 3.x), the matching entry is forwarded as `--classpath-<major>`. This
    // is the place to wire `(otherModule / Compile / fullClasspath)` from
    // a sibling module that's cross-built for the other major.
    val marklitMajorClasspaths = taskKey[Map[String, Seq[File]]](
      "Per-major classpath overrides for cross-version blocks (key = Scala major like \"2\" or \"3\")"
    )

    // Tasks
    val marklitCompile =
      taskKey[Unit]("Compile and verify markdown code blocks")
    val marklitGenerate = taskKey[Seq[File]]("Generate output markdown files")
    val marklitClean = taskKey[Unit]("Clean marklit output directory")
  }

  import autoImport._

  override def requires: Plugins = plugins.JvmPlugin
  override def trigger: PluginTrigger = allRequirements

  // Extract the embedded CLI jar on first use
  private lazy val extractedJar: File = {
    val tempDir = IO.createTemporaryDirectory
    val jarFile = tempDir / "marklit-cli.jar"

    val resourceStream = getClass.getResourceAsStream("/marklit-cli.jar")
    if (resourceStream == null) {
      sys.error(
        "marklit-cli.jar not found in plugin resources. Plugin may not be packaged correctly."
      )
    }

    try {
      IO.transfer(resourceStream, jarFile)
    } finally {
      resourceStream.close()
    }

    // Mark for cleanup on JVM exit
    tempDir.deleteOnExit()
    jarFile.deleteOnExit()

    jarFile
  }

  /** Auto-discover per-major classpaths from this project's dependsOn graph.
    *
    * For each project the docs module depends on (transitively), we look at
    * its `crossScalaVersions`. For every cross-version that's a *different*
    * major from the docs module's own scalaVersion, we add that project's
    * expected cross-build classes directory to the per-major classpath. The
    * user only needs to keep their cross-built jars compiled (e.g., via a
    * `+depModule/compile` step before `marklitGenerate`); the path discovery
    * is mechanical from sbt's standard layout (`<projDir>/target/scala-<v>/
    * classes`) and the project's own crossScalaVersions setting.
    *
    * The discovered map is then merged with any user override; user keys win
    * so explicit `marklitMajorClasspaths += ...` still works.
    */
  private def autoMajorClasspaths: Def.Initialize[Task[Map[String, Seq[File]]]] =
    Def.taskDyn {
      val docsMajor = scalaVersion.value.takeWhile(_ != '.')
      val deps = buildDependencies.value
        .classpath(thisProjectRef.value)
        .map(_.project)

      // For each direct dep, read its crossScalaVersions and target dir,
      // then materialize the cross-build's expected classes directory for
      // any major != docsMajor.
      val perDepFilter = ScopeFilter(inProjects(deps: _*))
      Def.task {
        val depCrosses = (Keys.crossScalaVersions ?? Seq.empty)
          .all(perDepFilter)
          .value
        val depTargets = Keys.target.all(perDepFilter).value

        val entries: Seq[(String, File)] = (depCrosses zip depTargets).flatMap {
          case (versions, tgt) =>
            versions
              .filter(v => v.takeWhile(_ != '.') != docsMajor)
              .flatMap { v =>
                val major = v.takeWhile(_ != '.')
                // sbt's cross-build target naming is annoyingly inconsistent:
                // 2.13 → "scala-2.13" (binary version), 3.x → "scala-3.x.y"
                // (full version). We try both and keep the one that exists.
                val binV =
                  if (major == "2") v.split('.').take(2).mkString(".")
                  else v
                val binDir = tgt / s"scala-$binV" / "classes"
                val fullDir = tgt / s"scala-$v" / "classes"
                Seq(binDir, fullDir).distinct
                  .filter(_.exists())
                  .headOption
                  .map(major -> _)
              }
        }

        entries
          .groupBy(_._1)
          .map { case (k, vs) => k -> vs.map(_._2).distinct }
      }
    }

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    // Default settings
    marklitSourceDirectory := (Compile / sourceDirectory).value / "markdown",
    marklitTargetDirectory := target.value / "marklit",
    marklitShowVersion := true,
    marklitVerbose := false,
    marklitMajorClasspaths := autoMajorClasspaths.value,

    // Compile task - check markdown files compile successfully
    marklitCompile := {
      val log = streams.value.log
      val sourceDir = marklitSourceDirectory.value
      val verbose = marklitVerbose.value
      // Get the project's full classpath (includes dependencies)
      val cp = (Compile / fullClasspath).value.files
      val scalaVer = scalaVersion.value
      val majorCps = marklitMajorClasspaths.value

      if (!sourceDir.exists()) {
        log.info(s"[marklit] No source directory: $sourceDir")
      } else {
        val sources = (sourceDir ** "*.md").get
        if (sources.isEmpty) {
          log.info(s"[marklit] No markdown files in $sourceDir")
        } else {
          log.info(s"[marklit] Checking ${sources.size} file(s)...")

          val exitCode =
            MarklitRunner.check(
              extractedJar,
              sources,
              cp,
              scalaVer,
              verbose,
              log,
              majorCps
            )
          if (exitCode != 0) {
            throw new MessageOnlyException(
              s"marklit compilation failed with exit code $exitCode"
            )
          }
        }
      }
    },

    // Generate task - render output markdown files
    marklitGenerate := {
      val log = streams.value.log
      val sourceDir = marklitSourceDirectory.value
      val targetDir = marklitTargetDirectory.value
      val showVersion = marklitShowVersion.value
      val verbose = marklitVerbose.value
      // Get the project's full classpath (includes dependencies)
      val cp = (Compile / fullClasspath).value.files
      val scalaVer = scalaVersion.value
      val majorCps = marklitMajorClasspaths.value

      if (!sourceDir.exists()) {
        log.info(s"[marklit] No source directory: $sourceDir")
        Seq.empty
      } else {
        val sources = (sourceDir ** "*.md").get
        if (sources.isEmpty) {
          log.info(s"[marklit] No markdown files in $sourceDir")
          Seq.empty
        } else {
          IO.createDirectory(targetDir)
          log.info(s"[marklit] Generating ${sources.size} file(s)...")
          log.info(s"[marklit] Scala version: $scalaVer")

          val exitCode = MarklitRunner.generate(
            extractedJar,
            sources,
            targetDir,
            cp,
            scalaVer,
            showVersion,
            verbose,
            log,
            majorCps
          )
          if (exitCode != 0) {
            throw new MessageOnlyException(
              s"marklit generation failed with exit code $exitCode"
            )
          }

          // Return generated files
          sources.map { source =>
            targetDir / source.getName
          }
        }
      }
    },

    // Clean task
    marklitClean := {
      val log = streams.value.log
      val targetDir = marklitTargetDirectory.value

      if (targetDir.exists()) {
        IO.delete(targetDir)
        log.info(s"[marklit] Cleaned: $targetDir")
      }
    }
  )
}
