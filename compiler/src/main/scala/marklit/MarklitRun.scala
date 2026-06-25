package marklit

import marklit.compiler.CompilerFactory
import marklit.model.{MarklitError, ScopeMode}
import marklit.renderer.{MarkdownRenderer, RenderConfig}
import marklit.resolver.DependencyResolver
import zio.*

import java.nio.file.{Files, Path}

/** Configuration for an end-to-end marklit run over a set of markdown files.
  *
  * This is the CLI-independent superset of the options the CLI used to thread
  * through command-line flags. Classpaths arrive already split into entries
  * (the CLI does the `[;:]` splitting before constructing this); `watch` and
  * `daemon` have no place here — they are concerns of the caller's process
  * model, not of the per-run orchestration.
  */
final case class MarklitRunConfig(
    inputFiles: Vector[Path],
    outputDir: Option[Path] = None,
    scalaVersion: Option[String] = None,
    classpath: Vector[String] = Vector.empty,
    classpath2: Vector[String] = Vector.empty,
    classpath3: Vector[String] = Vector.empty,
    deps: Vector[String] = Vector.empty,
    repos: Vector[String] = Vector.empty,
    cacheDir: Option[Path] = None,
    pageScope: Boolean = false,
    check: Boolean = false,
    showVersion: Boolean = true,
    showWarnings: Boolean = true,
    verbose: Boolean = false,
    runResourceClass: Option[String] = None
)

/** Per-file outcome with no Console side effects. `rendered` is populated when
  * an output directory is configured and the file is not in check mode (so a
  * caller can choose to write it itself); `outputPath` is where it would land.
  */
final case class MarklitFileReport(
    sourceFile: Path,
    success: Boolean,
    summary: String,
    blockErrors: Vector[(String, String)],
    failedCompiles: Vector[(String, String)],
    rendered: Option[String],
    outputPath: Option[Path]
)

/** Structured result of a run. `notices` are ordered informational lines the
  * caller may surface verbatim (the facade has already applied the verbose gate
  * when deciding which lines to include). Compile failures are reported as data
  * via [[MarklitFileReport.success]]; only file-read/parse/resolution errors
  * fail the effect.
  */
final case class MarklitRunResult(
    files: Vector[MarklitFileReport],
    notices: Vector[String]
):
  def success: Boolean = files.forall(_.success)
  def failedCount: Int = files.count(!_.success)

/** The shared, side-effect-free orchestration that drives marklit over a set of
  * files: parse `//> using` directives, aggregate and resolve dependencies
  * once, build per-major classpaths, process each file through [[Marklit]],
  * render and optionally write outputs. Both the CLI and the build-tool plugins
  * call this so the multi-file logic lives in exactly one place.
  */
object MarklitRun:

  /** Run with a freshly-built [[CompilerFactory]] (the CLI path). The factory
    * is built once and shared across all files, so per-version compilers are
    * reused within the run.
    */
  def run(
      config: MarklitRunConfig,
      writeOutputs: Boolean = true
  ): IO[MarklitError, MarklitRunResult] =
    ZIO.scoped {
      CompilerFactory.layer.build
        .mapError(t =>
          MarklitError.ResolutionError(
            s"Failed to initialize compiler factory: ${t.getMessage}"
          )
        )
        .flatMap(env => runWith(config, env.get[CompilerFactory], writeOutputs))
    }

  /** Run with a caller-supplied, long-lived [[CompilerFactory]] (the plugin
    * path). The factory's per-version cache survives across invocations,
    * keeping compilers warm — the in-process replacement for the old daemon.
    */
  def runWith(
      config: MarklitRunConfig,
      factory: CompilerFactory,
      writeOutputs: Boolean = true
  ): IO[MarklitError, MarklitRunResult] =
    for
      notices <- Ref.make(Chunk.empty[String])
      addNote = (s: String) => notices.update(_ :+ s)

      _ <- ZIO.when(config.verbose)(
        addNote(s"Processing ${config.inputFiles.size} file(s)...")
      )

      // Validate input files exist.
      _ <- ZIO.foreachDiscard(config.inputFiles) { path =>
        ZIO
          .fail(
            MarklitError.ParseError(
              marklit.model.Location(path.toString, 1, 1),
              s"File not found: $path"
            )
          )
          .unless(Files.exists(path))
      }

      // Effective default Scala version before per-file using directives.
      // Precedence: config.scalaVersion > the bundled shim's compile-time version.
      shimDefault = CompilerFactory.defaultScalaVersion
      cliDefault = config.scalaVersion.getOrElse(shimDefault)

      _ <- ZIO.when(config.verbose)(
        addNote(s"Default Scala version: $cliDefault")
      )

      // Read each input file and parse its using directives. Per-file parsing
      // lets us emit one notice per file when `//> using scala` overrides the
      // default.
      perFile <- ZIO.foreach(config.inputFiles) { path =>
        for
          content <- ZIO
            .attempt(Files.readString(path))
            .mapError(e =>
              MarklitError.ParseError(
                marklit.model.Location(path.toString, 1, 1),
                s"Failed to read file: ${e.getMessage}"
              )
            )
          directives = DependencyResolver.parseUsingDirectives(content)
        yield (path, directives)
      }

      // Aggregate dependencies/repos/scalacOptions across files (resolved once).
      allDeps = config.deps ++ perFile
        .flatMap(_._2.dependencies)
        .distinct
      allRepos = config.repos ++ perFile
        .flatMap(_._2.repositories)
        .distinct
      allScalacOptions = perFile.flatMap(_._2.scalacOptions).distinct

      _ <- ZIO.when(config.verbose && allDeps.nonEmpty)(
        addNote(s"Resolving dependencies: ${allDeps.mkString(", ")}")
      )

      // Resolve user dependencies once, against the default version. Per-file
      // overrides only affect compilation, not Coursier resolution of user deps.
      resolvedJars <-
        if allDeps.nonEmpty then
          DependencyResolver
            .resolve(allDeps, cliDefault, allRepos)
            .mapError(e => MarklitError.ResolutionError(e.getMessage))
        else ZIO.succeed(Vector.empty[String])

      _ <- ZIO.when(config.verbose && resolvedJars.nonEmpty)(
        addNote(s"Resolved ${resolvedJars.size} JAR(s)")
      )

      // Filter scala-library/scala3-library out of resolved deps — the
      // per-version classloader brings matching copies transitively, and a
      // user-resolved copy at a different version causes duplicate-package errors.
      filteredResolved = resolvedJars.filterNot { jar =>
        val fileName = java.nio.file.Paths.get(jar).getFileName.toString
        fileName.startsWith("scala-library") ||
        fileName.startsWith("scala3-library")
      }
      fullClasspath = config.classpath ++ filteredResolved

      // Per-major classpaths from the build plugin's cross-publish. Resolved
      // Coursier deps are version-agnostic enough to share across majors; the
      // per-version classloader's transitive stdlib wins via the filter above.
      majorClasspaths = Map(
        "2" -> config.classpath2,
        "3" -> config.classpath3
      ).collect {
        case (m, cp) if cp.nonEmpty => m -> (cp ++ filteredResolved)
      }

      // One notice per file whose using directive opts into a different version.
      _ <- ZIO.foreachDiscard(perFile) { case (path, directives) =>
        directives.scalaVersion match
          case Some(v) if v != cliDefault =>
            addNote(s"$path: //> using scala $v overrides default $cliDefault")
          case _ => ZIO.unit
      }

      // Process each file with its own effective default version (file directive
      // wins over the run default). The whole pass runs inside one scope: a
      // build-provided run resource (when configured) is acquired once before
      // any file and released after the last — so external state set up for the
      // docs (a DB container, a temp dir, …) lives for exactly one run and is
      // torn down before the next, even on failure.
      reports <- ZIO.scoped {
        acquireRunResource(
          config,
          factory,
          fullClasspath,
          cliDefault,
          addNote
        ).flatMap { runLoader =>
          ZIO.foreach(perFile) { case (path, directives) =>
            val fileDefault = directives.scalaVersion.getOrElse(cliDefault)
            val absPath = path.toAbsolutePath
            Marklit
              .processFile(absPath)
              .map(result => buildReport(result, config))
              .provide(
                ZLayer.succeed(factory),
                Marklit.liveWithFactory(
                  fileDefault,
                  fullClasspath,
                  allScalacOptions,
                  majorClasspaths,
                  config.cacheDir,
                  if config.pageScope then ScopeMode.Page
                  else ScopeMode.Isolated,
                  runLoader
                )
              )
          }
        }
      }

      // Write outputs only when the whole run succeeded — matching the CLI's
      // previous all-or-nothing behavior (it failed before writing if any file
      // failed). Rendering itself already happened in buildReport.
      allSuccess = reports.forall(_.success)
      _ <- ZIO.when(
        writeOutputs && !config.check && config.outputDir.isDefined && allSuccess
      ) {
        ZIO.foreachDiscard(reports) { report =>
          (report.rendered, report.outputPath) match
            case (Some(content), Some(out)) =>
              ZIO
                .attempt {
                  Files.createDirectories(out.getParent)
                  Files.writeString(out, content)
                }
                .mapError(e =>
                  MarklitError.ParseError(
                    marklit.model.Location(out.toString, 1, 1),
                    s"Failed to write output: ${e.getMessage}"
                  )
                ) *> ZIO.when(config.verbose)(addNote(s"Wrote: $out"))
            case _ => ZIO.unit
        }
      }

      noticeLines <- notices.get
    yield MarklitRunResult(reports, noticeLines.toVector)

  /** Acquire the build-provided run resource (when configured) for the duration
    * of the enclosing [[Scope]]. A no-op when `config.runResourceClass` is
    * unset.
    *
    * The resource is a user class on the docs' own classpath implementing the
    * JDK type `java.util.function.Supplier[AutoCloseable]`: `get()` performs
    * setup (start a DB container, create a schema, …) and returns an
    * `AutoCloseable` whose `close()` is the teardown. Using only JDK types
    * keeps the instance usable across marklit's classloader boundary — see
    * [[CompilerFactory.userClassLoader]].
    *
    * Both `get()` and `close()` are best-effort with respect to the run: a
    * setup failure is surfaced as a notice and lets the run proceed (the docs
    * that need the resource will fail on their own and report it); a teardown
    * failure is logged and swallowed so it never masks the run's real result.
    */
  private def acquireRunResource(
      config: MarklitRunConfig,
      factory: CompilerFactory,
      userClasspath: Vector[String],
      defaultVersion: String,
      addNote: String => UIO[Unit]
  ): URIO[Scope, Option[ClassLoader]] =
    config.runResourceClass match
      case None      => ZIO.none
      case Some(fqn) =>
        for
          // Build the per-run user loader U first. The resource instance lives
          // in U, and every block executes against U (see liveWithFactory), so
          // all blocks share the one instance for this run.
          loader <- factory.userClassLoader(defaultVersion, userClasspath)
          _ <- ZIO
            .acquireRelease(
              ZIO.attempt {
                val supplier = Class
                  .forName(fqn, true, loader)
                  .getDeclaredConstructor()
                  .newInstance()
                  .asInstanceOf[java.util.function.Supplier[AutoCloseable]]
                supplier.get()
              }
            )(handle =>
              ZIO
                .attempt(handle.close())
                .catchAll(e =>
                  addNote(
                    s"run resource '$fqn' teardown failed: ${e.getMessage}"
                  )
                )
            )
            .foldZIO(
              e =>
                addNote(s"run resource '$fqn' setup failed: ${e.getMessage}"),
              _ => ZIO.unit
            )
        yield Some(loader)

  /** Build a side-effect-free per-file report from a [[MarklitResult]],
    * rendering output markdown when an output dir is configured and the run is
    * not in check mode.
    */
  private def buildReport(
      result: MarklitResult,
      config: MarklitRunConfig
  ): MarklitFileReport =
    val blockErrors =
      result.errors.map((block, error) => (block.location.pretty, error.pretty))

    val failedCompiles =
      result.processingResult.blockResults
        .filter(br =>
          !br.isSuccess && br.error.isEmpty &&
            br.compileResult.exists(!_.success)
        )
        .map { br =>
          val diags = br.compileResult.map(_.diagnostics).getOrElse(Nil)
          val diagStr =
            diags.map(d => s"${d.severity}: ${d.message}").mkString("; ")
          (br.block.location.pretty, s"Compile failed - $diagStr")
        }

    val shouldRender = config.outputDir.isDefined && !config.check
    val rendered =
      if shouldRender then
        Some(
          MarkdownRenderer.render(
            result.document,
            result.processingResult,
            RenderConfig(
              showScalaVersion = config.showVersion,
              showCompileWarnings = config.showWarnings
            )
          )
        )
      else None
    val outputPath =
      config.outputDir.map(_.resolve(result.sourceFile.getFileName))

    MarklitFileReport(
      sourceFile = result.sourceFile,
      success = result.isSuccess,
      summary = result.summary,
      blockErrors = blockErrors,
      failedCompiles = failedCompiles,
      rendered = rendered,
      outputPath = outputPath
    )
