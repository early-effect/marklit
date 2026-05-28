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
    val marklitShowWarnings =
      settingKey[Boolean]("Render compile warnings in output blocks")
    val marklitVerbose = settingKey[Boolean]("Enable verbose output")
    val marklitPageScope = settingKey[Boolean](
      "Share scope across all anonymous blocks in each file (default: false)"
    )

    // Daemon settings — when enabled, marklit tasks talk to a long-lived
    // marklit JVM that survives across task invocations within the sbt
    // session. This keeps the per-Scala-version compiler classloaders warm
    // (cold-start ≈ 1-2s per fresh major), so the second `marklitGenerate`
    // run is substantially faster than the first.
    val marklitDaemon = settingKey[Boolean](
      "Enable the long-lived marklit daemon for warm-classloader reuse across tasks"
    )
    val marklitDaemonIdleTimeout = settingKey[Long](
      "Idle timeout in seconds before an inactive daemon shuts itself down (default: 900 = 15 minutes)"
    )

    // Persistent block-compile cache. SHA-256-keyed entries live under this
    // directory and survive across sbt sessions. Default sits inside the
    // project's `target/` so `sbt clean` removes it the same as other build
    // artifacts; `marklitClean` clears it explicitly. Set to `None` to opt
    // out of caching entirely.
    val marklitCacheDirectory = settingKey[Option[File]](
      "Directory for the on-disk block compile cache (None disables caching)"
    )

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

  /** Filter scala-library / scala3-library jars (and the bundled marklit-cli)
    * out of a forwarded classpath. The CLI resolves the per-version stdlib via
    * Coursier; leaving the host project's stdlib on the classpath leaks
    * 3.8.x-bin TASTy into 3.7.x compile contexts.
    */
  private def filterForwardedClasspath(cp: Seq[File]): Seq[File] =
    cp.filterNot { f =>
      val name = f.getName
      name.contains("marklit-cli") ||
      name.startsWith("scala-library") ||
      name.startsWith("scala3-library")
    }

  /** Resolve a dep project's per-cross-version classes directory. sbt's
    * cross-build target naming is inconsistent: 2.13 → "scala-2.13" (binary
    * version), 3.x → "scala-3.x.y" (full version). Returns the candidate dirs
    * in order — caller picks an existing one or, when checking readiness,
    * accepts the binary form as the canonical location.
    */
  private def crossClassesDir(target: File, version: String): Seq[File] = {
    val major = version.takeWhile(_ != '.')
    val binV =
      if (major == "2") version.split('.').take(2).mkString(".")
      else version
    val binDir = target / s"scala-$binV" / "classes"
    val fullDir = target / s"scala-$version" / "classes"
    Seq(binDir, fullDir).distinct
  }

  /** Auto-discover per-major classpaths from this project's dependsOn graph.
    *
    * For each project the docs module depends on (transitively), we look at its
    * `crossScalaVersions`. For every cross-version that's a *different* major
    * from the docs module's own scalaVersion, we add that project's expected
    * cross-build classes directory to the per-major classpath. Cross-builds are
    * produced automatically by the `marklitGenerate` / `marklitCompile`
    * commands (which schedule `++ <v>! depProj/compile` ahead of the task
    * invocation). When invoking the task directly via project scoping
    * (`docs/marklitGenerate`), the user is responsible for having compiled the
    * cross-builds first.
    *
    * The discovered map is then merged with any user override; user keys win so
    * explicit `marklitMajorClasspaths += ...` still works.
    */
  private def autoMajorClasspaths
      : Def.Initialize[Task[Map[String, Seq[File]]]] =
    Def.taskDyn {
      val docsMajor = scalaVersion.value.takeWhile(_ != '.')
      val deps = buildDependencies.value
        .classpath(thisProjectRef.value)
        .map(_.project)

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
                crossClassesDir(tgt, v)
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

  /** Compute the commands needed before invoking `taskName` on every project
    * that has MarklitPlugin enabled.
    *
    * The returned sequence is ordered:
    *   1. `+ <depProj>/Compile/compile` for each unique cross-built dep project
    *      (the `+` prefix iterates the dep's own `crossScalaVersions` and is
    *      responsible for stashing & restoring session state — so we get back
    *      to the user's original `scalaVersion` per project before the docs
    *      task runs).
    *   2. `<docsProj>/<taskName>` per marklit-enabled project.
    *
    * Why `+` (cross-prefix) and not `++ <v>!` (set-version)? The `++` form
    * mutates the session globally and there's no public API to reliably
    * un-mutate without going through `Cross.SwitchCommand`. The `+` form uses
    * sbt's internal session-stash machinery to compile *all* of the project's
    * `crossScalaVersions` and restore the original state. That's stronger than
    * we need (it cross-builds even the major already in the docs scope), but
    * the resulting compile is incremental, so the cost of re-touching the same
    * major is near-zero on a warm build.
    *
    * Each dep project is cross-compiled at most once (de-duplicated across docs
    * projects).
    */
  private def buildCrossCommands(
      state: State,
      taskName: String
  ): List[String] = {
    val extracted = Project.extract(state)
    val structure = extracted.structure

    // Detect "marklit-enabled" projects by their marklitSourceDirectory
    // existing on disk. The plugin auto-attaches to every project (via
    // allRequirements + JvmPlugin), so the setting itself is defined
    // everywhere — only projects that actually have markdown sources count.
    val docsProjects: Seq[ProjectRef] = structure.allProjectRefs.filter { ref =>
      (ref / marklitSourceDirectory)
        .get(structure.data)
        .exists(_.exists())
    }

    if (docsProjects.isEmpty) Nil
    else {
      // Find direct dependsOn project refs of each docs project that declare
      // a non-trivial crossScalaVersions (more than just the one default).
      val crossDepRefs: Seq[ProjectRef] = docsProjects.flatMap { ref =>
        val depRefs: Seq[ProjectRef] = Project
          .getProjectForReference(ref, structure)
          .toSeq
          .flatMap(_.dependencies.map(_.project))
          .collect { case p: ProjectRef => p }
        depRefs.filter { dr =>
          val crosses = (dr / crossScalaVersions)
            .get(structure.data)
            .getOrElse(Seq.empty)
          crosses.size > 1
        }
      }.distinct

      val crossCmds =
        crossDepRefs.map(dr => s"+ ${dr.project}/Compile/compile")
      val taskCmds = docsProjects.map(p => s"${p.project}/$taskName")

      crossCmds.toList ::: taskCmds.toList
    }
  }

  /** Build-level commands that schedule the necessary cross-version compiles
    * before invoking each marklit-enabled project's task.
    *
    * Why a command (and not a task)? sbt's `scalaVersion` is a setting, not a
    * parameter to `compile`. The `+` cross-prefix is a *command-level* loop
    * that re-applies the build with a different `scalaVersion` per cross
    * version, then runs the suffix command. There is no public API to do this
    * from a `Def.task` body — it must be done by prepending commands to
    * `state.remainingCommands`. This is the same machinery `sbt.Cross` uses
    * internally for the `+` command.
    *
    * Users who run the project-scoped task directly (`docs/marklitGenerate`)
    * still need to ensure cross-builds are present (e.g., via prior
    * `+depModule/compile`). The build-level commands `marklitGenerate` /
    * `marklitCompile` (defined here, no project selector) handle that
    * automatically by detecting which projects have markdown sources, which
    * deps they have, and what cross versions those deps declare.
    */
  private val marklitGenerateCommand: Command =
    Command.command("marklitGenerate") { state =>
      val cmds = buildCrossCommands(state, "marklitGenerate")
      if (cmds.isEmpty) state
      else cmds ::: state
    }

  private val marklitCompileCommand: Command =
    Command.command("marklitCompile") { state =>
      val cmds = buildCrossCommands(state, "marklitCompile")
      if (cmds.isEmpty) state
      else cmds ::: state
    }

  override lazy val buildSettings: Seq[Setting[_]] = Seq(
    // Tear daemons down when the build session ends. `onUnload` runs on
    // sbt exit and on `reload`. We chain after any existing onUnload so we
    // don't clobber other plugins' cleanup.
    Global / onUnload := { (s: State) =>
      val previous = (Global / onUnload).value
      val next = previous(s)
      try MarklitDaemonRegistry.shutdownAll()
      catch { case _: Throwable => () }
      next
    },
    Global / commands ++= Seq(marklitGenerateCommand, marklitCompileCommand)
  )

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    // Default settings
    marklitSourceDirectory := (Compile / sourceDirectory).value / "markdown",
    marklitTargetDirectory := target.value / "marklit",
    marklitShowVersion := true,
    marklitShowWarnings := true,
    marklitVerbose := false,
    marklitPageScope := false,
    marklitDaemon := true,
    marklitDaemonIdleTimeout := 900L,
    marklitCacheDirectory := Some(target.value / "marklit-cache"),
    marklitMajorClasspaths := autoMajorClasspaths.value,

    // Make `sbt ~marklitGenerate` (and friends) re-trigger when a markdown
    // source under marklitSourceDirectory is edited. Without this, sbt only
    // watches Scala/Java sources and a `.md` save would not retrigger the
    // task.
    Compile / watchSources += new WatchSource(
      marklitSourceDirectory.value,
      "*.md" || "*.markdown",
      HiddenFileFilter
    ),

    // Compile task - check markdown files compile successfully
    marklitCompile := {
      val log = streams.value.log
      val sourceDir = marklitSourceDirectory.value
      val verbose = marklitVerbose.value
      // Get the project's full classpath (includes dependencies)
      val cp = filterForwardedClasspath((Compile / fullClasspath).value.files)
      val scalaVer = scalaVersion.value
      val majorCps = marklitMajorClasspaths.value
      val cacheDir = marklitCacheDirectory.value
      val pageScope = marklitPageScope.value
      val daemon =
        if (marklitDaemon.value)
          Some(
            MarklitDaemonRegistry.get(
              extractedJar,
              marklitDaemonIdleTimeout.value,
              log
            )
          )
        else None

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
              majorCps,
              daemon,
              cacheDir,
              pageScope
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
      val showWarnings = marklitShowWarnings.value
      val verbose = marklitVerbose.value
      // Get the project's full classpath (includes dependencies)
      val cp = filterForwardedClasspath((Compile / fullClasspath).value.files)
      val scalaVer = scalaVersion.value
      val majorCps = marklitMajorClasspaths.value
      val cacheDir = marklitCacheDirectory.value
      val pageScope = marklitPageScope.value
      val daemon =
        if (marklitDaemon.value)
          Some(
            MarklitDaemonRegistry.get(
              extractedJar,
              marklitDaemonIdleTimeout.value,
              log
            )
          )
        else None

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
            showWarnings,
            verbose,
            log,
            majorCps,
            daemon,
            cacheDir,
            pageScope
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

    // Clean task — removes both rendered output and the persistent compile
    // cache. We try the daemon's `clear-cache` RPC first when one is running
    // (so the daemon's in-memory file handles are released cleanly on
    // Windows); on any failure we fall back to a filesystem delete.
    marklitClean := {
      val log = streams.value.log
      val targetDir = marklitTargetDirectory.value
      val cacheDir = marklitCacheDirectory.value
      val daemon =
        if (marklitDaemon.value)
          Some(
            MarklitDaemonRegistry.get(
              extractedJar,
              marklitDaemonIdleTimeout.value,
              log
            )
          )
        else None

      if (targetDir.exists()) {
        IO.delete(targetDir)
        log.info(s"[marklit] Cleaned: $targetDir")
      }

      cacheDir.foreach { dir =>
        if (dir.exists()) {
          val cleared = daemon match {
            case Some(client) =>
              try {
                client.clearCache(dir.getAbsolutePath) match {
                  case None    => true
                  case Some(_) => false
                }
              } catch { case _: Throwable => false }
            case None => false
          }
          if (!cleared) IO.delete(dir)
          log.info(s"[marklit] Cleared cache: $dir")
        }
      }
    }
  )
}
