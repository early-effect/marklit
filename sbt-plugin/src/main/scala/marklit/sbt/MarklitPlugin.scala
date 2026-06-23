package marklit.sbt

import marklit.MarklitRunConfig
import sbt.*
import sbt.Keys.*

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
    // 3.x), the matching entry is forwarded to the cross-version compiler.
    // This is the place to wire `(otherModule / Compile / fullClasspath)`
    // from a sibling module that's cross-built for the other major.
    val marklitMajorClasspaths = taskKey[Map[String, Seq[File]]](
      "Per-major classpath overrides for cross-version blocks (key = Scala major like \"2\" or \"3\")"
    )

    // Tasks
    val marklitCompile =
      taskKey[Unit]("Compile and verify markdown code blocks")
    val marklitGenerate = taskKey[Seq[File]]("Generate output markdown files")
    val marklitClean = taskKey[Unit]("Clean marklit output directory")
  }

  import autoImport.*

  override def requires: Plugins = plugins.JvmPlugin
  override def trigger: PluginTrigger = allRequirements

  /** Filter scala-library / scala3-library jars out of a forwarded classpath.
    * marklit resolves the per-version stdlib via Coursier; leaving the host
    * project's stdlib on the classpath leaks its bin-TASTy into a different
    * compile context.
    */
  private def filterForwardedClasspath(cp: Seq[File]): Seq[File] =
    cp.filterNot { f =>
      val name = f.getName
      name.startsWith("scala-library") ||
      name.startsWith("scala3-library")
    }

  /** Resolve the real on-disk paths of a Classpath. sbt 2.0 classpaths are
    * `Seq[Attributed[xsbti.HashedVirtualFileRef]]`; the converter turns each
    * virtual ref into a `java.nio.file.Path` (then a `File`).
    */
  private def classpathFiles(
      cp: Def.Classpath,
      converter: xsbti.FileConverter
  ): Seq[File] =
    cp.map(af => converter.toPath(af.data).toFile)

  /** Resolve a dep project's per-cross-version classes directory.
    *
    * In sbt 2.0 the build output layout is
    * `<base>/out/jvm/scala-<fullVersion>/<module>/classes`, and the `target`
    * key for the dep is already scoped to the *current* session's Scala version
    * (e.g. `.../out/jvm/scala-3.8.2/marklit-example-core`). To reach a
    * different cross version we substitute the `scala-<version>` path segment.
    *
    * For robustness we also emit the sbt 1.x candidates (`target/scala-<binV>`
    * and `target/scala-<fullVersion>`); the caller filters by existence and
    * picks the first that's present.
    */
  private def crossClassesDir(target: File, version: String): Seq[File] = {
    val major = version.takeWhile(_ != '.')
    val binV =
      if (major == "2") version.split('.').take(2).mkString(".")
      else version

    // sbt 2.0 layout: target == .../out/jvm/scala-<curVer>/<module>.
    // Replace the scala-<curVer> grandparent segment with scala-<version>.
    val parent = Option(target.getParentFile)
    val sbt2Candidate: Seq[File] =
      parent match {
        case Some(p) if p.getName.startsWith("scala-") =>
          Seq(p.getParentFile / s"scala-$version" / target.getName / "classes")
        case _ => Seq.empty
      }

    // sbt 1.x layout: target/scala-<binV-or-full>/classes.
    val sbt1Candidates = Seq(
      target / s"scala-$binV" / "classes",
      target / s"scala-$version" / "classes"
    )

    (sbt2Candidate ++ sbt1Candidates).distinct
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
    */
  private def autoMajorClasspaths
      : Def.Initialize[Task[Map[String, Seq[File]]]] =
    Def.taskDyn {
      val docsMajor = scalaVersion.value.takeWhile(_ != '.')
      val deps = buildDependencies.value
        .classpath(thisProjectRef.value)
        .map(_.project)

      val perDepFilter = ScopeFilter(inProjects(deps*))
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
    * version, then runs the suffix command.
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

  /** Build a per-major classpath map of real paths for the run config. */
  private def majorClasspathStrings(
      majorCps: Map[String, Seq[File]]
  ): (Vector[String], Vector[String]) = {
    def entries(major: String): Vector[String] =
      filterForwardedClasspath(majorCps.getOrElse(major, Seq.empty))
        .map(_.getAbsolutePath)
        .toVector
    (entries("2"), entries("3"))
  }

  override lazy val buildSettings: Seq[Setting[?]] = Seq(
    // Release the in-process CompilerFactory (temp shim jars, cached
    // classloaders) when the build session ends. `onUnload` runs on sbt exit
    // and on `reload`. We chain after any existing onUnload so we don't clobber
    // other plugins' cleanup.
    Global / onUnload := { (s: State) =>
      val previous = (Global / onUnload).value
      val next = previous(s)
      try MarklitSession.shutdown()
      catch { case _: Throwable => () }
      next
    },
    Global / commands ++= Seq(marklitGenerateCommand, marklitCompileCommand)
  )

  override lazy val projectSettings: Seq[Setting[?]] = Seq(
    // Default settings
    marklitSourceDirectory := (Compile / sourceDirectory).value / "markdown",
    marklitTargetDirectory := target.value / "marklit",
    marklitShowVersion := true,
    marklitShowWarnings := true,
    marklitVerbose := false,
    marklitPageScope := false,
    marklitCacheDirectory := Some(target.value / "marklit-cache"),
    // Uncached: yields Map[String, Seq[File]], not a cacheable output type.
    marklitMajorClasspaths := Def.uncached(autoMajorClasspaths.value),

    // Make `sbt ~marklitGenerate` (and friends) re-trigger when a markdown
    // source under marklitSourceDirectory is edited. Uncached: WatchSource has
    // no JsonFormat for sbt 2.0's task-result cache.
    Compile / watchSources := Def.uncached {
      (Compile / watchSources).value :+ new WatchSource(
        marklitSourceDirectory.value,
        "*.md" || "*.markdown",
        HiddenFileFilter
      )
    },

    // Compile task - check markdown files compile successfully.
    // Def.uncached: this task drives the compiler and has File-typed inputs that
    // sbt 2.0 can't hash for its default task-result cache; it must always run.
    marklitCompile := Def.uncached {
      val log = streams.value.log
      val sourceDir = marklitSourceDirectory.value
      val verbose = marklitVerbose.value
      val converter = fileConverter.value
      val cp = filterForwardedClasspath(
        classpathFiles((Compile / fullClasspath).value, converter)
      ).map(_.getAbsolutePath).toVector
      val scalaVer = scalaVersion.value
      val (cp2, cp3) = majorClasspathStrings(marklitMajorClasspaths.value)
      val cacheDir = marklitCacheDirectory.value
      val pageScope = marklitPageScope.value

      if (!sourceDir.exists()) {
        log.info(s"[marklit] No source directory: $sourceDir")
      } else {
        val sources = (sourceDir ** "*.md").get()
        if (sources.isEmpty) {
          log.info(s"[marklit] No markdown files in $sourceDir")
        } else {
          log.info(s"[marklit] Checking ${sources.size} file(s)...")

          val config = MarklitRunConfig(
            inputFiles = sources.map(_.toPath).toVector,
            scalaVersion = Some(scalaVer),
            classpath = cp,
            classpath2 = cp2,
            classpath3 = cp3,
            cacheDir = cacheDir.map(_.toPath),
            pageScope = pageScope,
            check = true,
            verbose = verbose
          )
          val _ = MarklitInProcess.run(config, "compilation", log)
        }
      }
    },

    // Generate task - render output markdown files.
    // Def.uncached: side-effecting (runs the compiler, writes files) and returns
    // Seq[File], which is not a cacheable output type in sbt 2.0.
    marklitGenerate := Def.uncached {
      val log = streams.value.log
      val sourceDir = marklitSourceDirectory.value
      val targetDir = marklitTargetDirectory.value
      val showVersion = marklitShowVersion.value
      val showWarnings = marklitShowWarnings.value
      val verbose = marklitVerbose.value
      val converter = fileConverter.value
      val cp = filterForwardedClasspath(
        classpathFiles((Compile / fullClasspath).value, converter)
      ).map(_.getAbsolutePath).toVector
      val scalaVer = scalaVersion.value
      val (cp2, cp3) = majorClasspathStrings(marklitMajorClasspaths.value)
      val cacheDir = marklitCacheDirectory.value
      val pageScope = marklitPageScope.value

      if (!sourceDir.exists()) {
        log.info(s"[marklit] No source directory: $sourceDir")
        Seq.empty
      } else {
        val sources = (sourceDir ** "*.md").get()
        if (sources.isEmpty) {
          log.info(s"[marklit] No markdown files in $sourceDir")
          Seq.empty
        } else {
          IO.createDirectory(targetDir)
          log.info(s"[marklit] Generating ${sources.size} file(s)...")
          log.info(s"[marklit] Scala version: $scalaVer")

          val config = MarklitRunConfig(
            inputFiles = sources.map(_.toPath).toVector,
            outputDir = Some(targetDir.toPath),
            scalaVersion = Some(scalaVer),
            classpath = cp,
            classpath2 = cp2,
            classpath3 = cp3,
            cacheDir = cacheDir.map(_.toPath),
            pageScope = pageScope,
            check = false,
            showVersion = showVersion,
            showWarnings = showWarnings,
            verbose = verbose
          )
          val _ = MarklitInProcess.run(config, "generation", log)

          // Return generated files
          sources.map(source => targetDir / source.getName)
        }
      }
    },

    // Clean task — removes both rendered output and the persistent compile
    // cache via a plain filesystem delete (the in-process factory holds no OS
    // locks on the cache dir; the disk cache opens/closes per entry).
    marklitClean := Def.uncached {
      val log = streams.value.log
      val targetDir = marklitTargetDirectory.value
      val cacheDir = marklitCacheDirectory.value

      if (targetDir.exists()) {
        IO.delete(targetDir)
        log.info(s"[marklit] Cleaned: $targetDir")
      }

      cacheDir.foreach { dir =>
        if (dir.exists()) {
          IO.delete(dir)
          log.info(s"[marklit] Cleared cache: $dir")
        }
      }
    }
  )
}
