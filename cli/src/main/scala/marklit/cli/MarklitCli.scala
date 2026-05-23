package marklit.cli

import marklit.{Marklit, MarklitResult}
import marklit.compiler.CompilerFactory
import marklit.renderer.{MarkdownRenderer, RenderConfig}
import marklit.resolver.DependencyResolver
import zio.*
import zio.cli.*
import zio.cli.HelpDoc.Span.text

import java.nio.file.{Files, Path}

/** CLI options */
final case class MarklitOptions(
    inputFiles: List[Path],
    outputDir: Option[Path],
    watch: Boolean,
    verbose: Boolean,
    check: Boolean,
    showVersionInOutput: Boolean,
    classpath: Option[String],
    dependencies: List[String],
    repositories: List[String],
    scalaVersion: Option[String]
)

object MarklitCli extends ZIOCliDefault:

  // Define CLI arguments and options
  val inputFiles: Args[List[Path]] =
    Args.path("input").repeat1.map(_.toList)

  val outputDir: Options[Option[Path]] =
    Options
      .directory("out", Exists.Either)
      .optional ?? "Output directory for processed files"

  val watch: Options[Boolean] =
    Options
      .boolean("watch")
      .alias("w") ?? "Watch for file changes and reprocess"

  val verbose: Options[Boolean] =
    Options.boolean("verbose").alias("v") ?? "Enable verbose output"

  val check: Options[Boolean] =
    Options
      .boolean("check")
      .alias("c") ?? "Check mode: verify code compiles without writing output"

  // Default is to show the Scala version annotation on each output block.
  // Pass `--no-show-version` to suppress it. We invert the boolean here
  // because zio-cli's `Options.boolean` is a flag (absent = false, present
  // = true) and `withDefault(true)` doesn't override the absent case for
  // flags — so we phrase the flag negatively and invert the result.
  val showVersionInOutput: Options[Boolean] =
    Options
      .boolean("no-show-version")
      .map(!_) ?? "Suppress Scala version annotation in output blocks"

  val classpath: Options[Option[String]] =
    Options.text("classpath").alias("cp").optional ??
      "Additional classpath entries (colon or semicolon separated)"

  // keyValueMap allows --dep key=value to be repeated, we use it with dummy values
  // User can do: --dep dev.zio::zio:2.1.24=_ --dep org.typelevel::cats:2.10.0=_
  // Or simpler: just use comma-separated in a single --deps flag
  val dependencies: Options[List[String]] =
    Options.text("deps").alias("d").optional.map { opt =>
      opt.map(_.split(",").toList.map(_.trim).filter(_.nonEmpty)).getOrElse(Nil)
    } ?? "Dependencies to resolve, comma-separated (e.g., --deps 'dev.zio::zio:2.1.24,org.typelevel::cats:2.10.0')"

  val repositories: Options[List[String]] =
    Options.text("repos").alias("r").optional.map { opt =>
      opt.map(_.split(",").toList.map(_.trim).filter(_.nonEmpty)).getOrElse(Nil)
    } ?? "Additional Maven repositories, comma-separated"

  val scalaVersion: Options[Option[String]] =
    Options.text("scala-version").optional ??
      "Default Scala 3 version (overridden by `//> using scala` and per-block `scala=<version>`)"

  val combinedOptions: Options[
    (
        Option[Path],
        Boolean,
        Boolean,
        Boolean,
        Boolean,
        Option[String],
        List[String],
        List[String],
        Option[String]
    )
  ] =
    outputDir ++ watch ++ verbose ++ check ++ showVersionInOutput ++ classpath ++ dependencies ++ repositories ++ scalaVersion

  // Main command
  val marklitCommand: Command[MarklitOptions] =
    Command("marklit", combinedOptions, inputFiles)
      .map { case (opts, files) =>
        val (out, w, v, c, showV, cp, deps, repos, sv) = opts
        MarklitOptions(files, out, w, v, c, showV, cp, deps, repos, sv)
      }
      .withHelp(
        HelpDoc.p("marklit - Typechecked documentation for Scala") +
          HelpDoc.p("Process markdown files with embedded Scala code blocks.") +
          HelpDoc.p("") +
          HelpDoc.p("Dependencies can be specified via:") +
          HelpDoc.p("  --dep org::name:version  (command line)") +
          HelpDoc.p("  //> using dep org::name:version  (in code blocks)")
      )

  override val cliApp: CliApp[Any, Throwable, MarklitOptions] =
    CliApp.make(
      name = "marklit",
      version = "0.1.0",
      summary = text("Typechecked documentation for Scala"),
      command = marklitCommand
    ) { options =>
      runMarklit(options).as(options)
    }

  def runMarklit(options: MarklitOptions): ZIO[Any, Throwable, Unit] =
    for
      _ <- ZIO.when(options.verbose)(
        Console.printLine(s"Processing ${options.inputFiles.size} file(s)...")
      )

      // Validate input files exist
      _ <- ZIO.foreachDiscard(options.inputFiles) { path =>
        ZIO
          .fail(new RuntimeException(s"File not found: $path"))
          .unless(Files.exists(path))
      }

      // Effective default Scala version, before per-file using directives
      // are applied. Precedence: --scala-version > shim's compile-time version.
      shimDefault = CompilerFactory.defaultScalaVersion
      cliDefault = options.scalaVersion.getOrElse(shimDefault)

      _ <- ZIO.when(options.verbose)(
        Console.printLine(s"Default Scala version: $cliDefault")
      )

      // Read each input file and parse its using directives. Per-file parsing
      // (rather than one merged set) lets us emit one notification per file
      // when a `//> using scala` directive overrides the CLI default.
      perFile <- ZIO.foreach(options.inputFiles) { path =>
        for
          content <- ZIO.attempt(Files.readString(path))
          directives = DependencyResolver.parseUsingDirectives(content)
        yield (path, directives)
      }

      // Aggregate dependencies across files (kept as a single resolution
      // bundle to avoid resolving the same dep N times).
      allDeps = options.dependencies.toVector ++ perFile.toVector
        .flatMap(_._2.dependencies)
        .distinct
      allRepos = options.repositories.toVector ++ perFile.toVector
        .flatMap(_._2.repositories)
        .distinct
      allScalacOptions = perFile.toVector.flatMap(_._2.scalacOptions).distinct

      _ <- ZIO.when(options.verbose && allDeps.nonEmpty)(
        Console.printLine(s"Resolving dependencies: ${allDeps.mkString(", ")}")
      )

      // Resolve dependencies once, against the CLI default version. Per-file
      // overrides only affect compilation, not Coursier resolution of user deps.
      resolvedJars <- {
        if allDeps.nonEmpty then
          DependencyResolver
            .resolve(allDeps, cliDefault, allRepos)
            .mapError(e =>
              new RuntimeException(
                s"Dependency resolution failed: ${e.getMessage}"
              )
            )
        else ZIO.succeed(Vector.empty[String])
      }

      _ <- ZIO.when(options.verbose && resolvedJars.nonEmpty)(
        Console.printLine(s"Resolved ${resolvedJars.size} JAR(s)")
      )

      // Filter scala-library/scala3-library out of resolved deps — the
      // per-version classloader brings matching copies transitively, and a
      // user-resolved copy at a different version causes duplicate-package
      // errors.
      cliClasspath = options.classpath
        .map(_.split("[;:]").toVector)
        .getOrElse(Vector.empty)
      filteredResolved = resolvedJars.filterNot { jar =>
        val fileName = java.nio.file.Paths.get(jar).getFileName.toString
        fileName.startsWith("scala-library") || fileName.startsWith(
          "scala3-library"
        )
      }
      fullClasspath = cliClasspath ++ filteredResolved

      // Print override notifications: one info line per file whose using
      // directive opts into a Scala version different from the CLI default.
      _ <- ZIO.foreachDiscard(perFile) { case (path, directives) =>
        directives.scalaVersion match
          case Some(v) if v != cliDefault =>
            Console.printLine(
              s"$path: //> using scala $v overrides default $cliDefault"
            )
          case _ => ZIO.unit
      }

      // Process each file with its own effective default version (file
      // directive wins over CLI default). Per-block specific versions are
      // handled inside the processor via the same factory instance.
      results <- ZIO
        .foreach(perFile) { case (path, directives) =>
          val fileDefault = directives.scalaVersion.getOrElse(cliDefault)
          val fileScalacOptions =
            directives.scalacOptions.toVector ++ allScalacOptions
          val absPath = path.toAbsolutePath
          Marklit
            .processFile(absPath)
            .provideSome[CompilerFactory](
              Marklit.liveWithFactory(
                fileDefault,
                fullClasspath,
                fileScalacOptions
              )
            )
            .mapError(e => new RuntimeException(e.pretty))
        }
        .provide(CompilerFactory.layer)

      // Report results
      _ <- ZIO.foreachDiscard(results) { result =>
        for
          _ <- Console.printLine(result.summary)
          _ <- ZIO.when(options.verbose && result.errors.nonEmpty)(
            ZIO.foreachDiscard(result.errors) { case (block, error) =>
              Console.printLine(
                s"  ${block.location.pretty}: ${error.pretty}"
              )
            }
          )
          failedBlocks = result.processingResult.blockResults.filter(br =>
            !br.isSuccess && br.error.isEmpty && br.compileResult.exists(
              !_.success
            )
          )
          _ <- ZIO.when(options.verbose && failedBlocks.nonEmpty)(
            ZIO.foreachDiscard(failedBlocks) { br =>
              val diagnostics =
                br.compileResult.map(_.diagnostics).getOrElse(Nil)
              val diagStr = diagnostics
                .map(d => s"${d.severity}: ${d.message}")
                .mkString("; ")
              Console.printLine(
                s"  ${br.block.location.pretty}: Compile failed - $diagStr"
              )
            }
          )
        yield ()
      }

      // Check for failures
      failures = results.filterNot(_.isSuccess)
      _ <- ZIO
        .fail(new RuntimeException(s"${failures.size} file(s) failed"))
        .unless(failures.isEmpty)

      // Write output if not in check mode
      _ <- ZIO.when(!options.check && options.outputDir.isDefined)(
        writeOutputs(
          results.toVector,
          options.outputDir.get,
          options.verbose,
          options.showVersionInOutput
        )
      )
    yield ()

  /** Write final rendered markdown output */
  private def writeOutputs(
      results: Vector[MarklitResult],
      outputDir: Path,
      verbose: Boolean,
      showVersion: Boolean
  ): ZIO[Any, Throwable, Unit] =
    for
      _ <- ZIO.attempt(Files.createDirectories(outputDir))
      _ <- ZIO.foreachDiscard(results) { result =>
        val outputPath = outputDir.resolve(result.sourceFile.getFileName)
        val config = RenderConfig(showScalaVersion = showVersion)
        for
          rendered <- ZIO.succeed(
            MarkdownRenderer.render(
              result.document,
              result.processingResult,
              config
            )
          )
          _ <- ZIO.attempt(Files.writeString(outputPath, rendered))
          _ <- ZIO.when(verbose)(Console.printLine(s"  Wrote: $outputPath"))
        yield ()
      }
    yield ()
