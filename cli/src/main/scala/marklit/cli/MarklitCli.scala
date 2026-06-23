package marklit.cli

import marklit.{MarklitRun, MarklitRunConfig}
import zio.*
import zio.cli.*
import zio.cli.HelpDoc.Span.text

import java.nio.file.Path

/** CLI options */
final case class MarklitOptions(
    inputFiles: List[Path],
    outputDir: Option[Path],
    watch: Boolean,
    verbose: Boolean,
    check: Boolean,
    showVersionInOutput: Boolean,
    showWarningsInOutput: Boolean,
    classpath: Option[String],
    classpath2: Option[String],
    classpath3: Option[String],
    dependencies: List[String],
    repositories: List[String],
    scalaVersion: Option[String],
    daemon: Boolean,
    idleTimeoutSeconds: Option[Int],
    cacheDir: Option[Path] = None,
    pageScope: Boolean = false
)

object MarklitCli extends ZIOCliDefault:

  // Define CLI arguments and options.
  // .repeat (not .repeat1) so files are zero-or-more — `marklit --daemon`
  // with no positional args still parses cleanly. One-shot mode validates
  // that at least one file is present below.
  val inputFiles: Args[List[Path]] =
    Args.path("input").repeat.map(_.toList)

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

  // --show-warnings=true|false controls whether compile warnings are rendered
  // in output blocks (default true). Per-block `show-warnings=true|false` info
  // string options override this for individual blocks. Accepts the values
  // recognized by zio-cli's PrimType.Bool (true/false/yes/no/on/off/1/0).
  private def parseBool(s: String): Option[Boolean] =
    s.toLowerCase match
      case "true" | "yes" | "on" | "1"  => Some(true)
      case "false" | "no" | "off" | "0" => Some(false)
      case _                            => None

  val showWarningsInOutput: Options[Boolean] =
    Options
      .text("show-warnings")
      .optional
      .map {
        case Some(s) => parseBool(s).getOrElse(true)
        case None    => true
      } ?? "Render compile warnings in output blocks (true/false; default true)"

  val classpath: Options[Option[String]] =
    Options.text("classpath").alias("cp").optional ??
      "Default classpath (used by blocks compiled at the default Scala major; colon/semicolon-separated)"

  // Per-major classpath overrides. When a block is compiled against a Scala
  // major different from the file/CLI default, the default --classpath is
  // built against the wrong major and forwarding it would either let user
  // code reference symbols the requested version doesn't have, or trigger
  // TASTy/library mismatches. The per-major flags let the build plugin send
  // the matching classpath for each major it cross-publishes.
  val classpath2: Options[Option[String]] =
    Options.text("classpath-2").optional ??
      "Classpath used when compiling Scala 2.x cross-version blocks (colon/semicolon-separated)"

  val classpath3: Options[Option[String]] =
    Options.text("classpath-3").optional ??
      "Classpath used when compiling Scala 3.x cross-version blocks (colon/semicolon-separated)"

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

  val daemon: Options[Boolean] =
    Options.boolean("daemon") ??
      "Run as a long-lived daemon that reads JSON-RPC requests from stdin"

  val idleTimeoutSeconds: Options[Option[Int]] =
    Options.integer("idle-timeout").optional.map(_.map(_.toInt)) ??
      "Daemon idle timeout in seconds (default: 900). Daemon exits after this much inactivity."

  // --cache-dir: persistent compile-result cache (off by default — pass a
  // directory to opt in). Build plugins point this at `target/marklit-cache`
  // so cache lifetime tracks the project's other build artifacts.
  val cacheDir: Options[Option[Path]] =
    Options
      .directory("cache-dir", Exists.Either)
      .optional ?? "Directory for the on-disk block compile cache (off by default)"

  // --page-scope: opt every anonymous block in every input file into a single
  // shared scope per file (mdoc-style). Per-block id=/extends= still wins.
  val pageScope: Options[Boolean] =
    Options.boolean("page-scope") ??
      "Share scope across all anonymous blocks in each file (default: each block isolated)"

  // zio-cli's `++` flattens via `Zippable`, so the result is a flat 16-tuple.
  val combinedOptions: Options[
    (
        Option[Path],
        Boolean,
        Boolean,
        Boolean,
        Boolean,
        Boolean,
        Option[String],
        Option[String],
        Option[String],
        List[String],
        List[String],
        Option[String],
        Boolean,
        Option[Int],
        Option[Path],
        Boolean
    )
  ] =
    outputDir ++ watch ++ verbose ++ check ++ showVersionInOutput ++ showWarningsInOutput ++ classpath ++ classpath2 ++ classpath3 ++ dependencies ++ repositories ++ scalaVersion ++ daemon ++ idleTimeoutSeconds ++ cacheDir ++ pageScope

  // Main command
  val marklitCommand: Command[MarklitOptions] =
    Command("marklit", combinedOptions, inputFiles)
      .map { case (opts, files) =>
        val (
          out,
          w,
          v,
          c,
          showV,
          showW,
          cp,
          cp2,
          cp3,
          deps,
          repos,
          sv,
          d,
          idle,
          cache,
          ps
        ) =
          opts
        MarklitOptions(
          files,
          out,
          w,
          v,
          c,
          showV,
          showW,
          cp,
          cp2,
          cp3,
          deps,
          repos,
          sv,
          d,
          idle,
          cache,
          ps
        )
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
      val effect =
        if options.daemon then
          Daemon.run(
            verbose = options.verbose,
            idleTimeout = options.idleTimeoutSeconds
              .map(s => Duration.fromSeconds(s.toLong))
              .getOrElse(Daemon.defaultIdleTimeout)
          )
        else if options.inputFiles.isEmpty then
          ZIO.fail(
            new RuntimeException(
              "No input files. Pass one or more markdown files, or use --daemon."
            )
          )
        else runMarklit(options)
      effect.as(options)
    }

  /** Split a `--classpath`-style flag value into entries (colon/semicolon
    * separated), or empty when absent.
    */
  private def splitClasspath(cp: Option[String]): Vector[String] =
    cp.map(_.split("[;:]").toVector).getOrElse(Vector.empty)

  def runMarklit(options: MarklitOptions): ZIO[Any, Throwable, Unit] =
    val config = MarklitRunConfig(
      inputFiles = options.inputFiles.toVector,
      outputDir = options.outputDir,
      scalaVersion = options.scalaVersion,
      classpath = splitClasspath(options.classpath),
      classpath2 = splitClasspath(options.classpath2),
      classpath3 = splitClasspath(options.classpath3),
      deps = options.dependencies.toVector,
      repos = options.repositories.toVector,
      cacheDir = options.cacheDir,
      pageScope = options.pageScope,
      check = options.check,
      showVersion = options.showVersionInOutput,
      showWarnings = options.showWarningsInOutput,
      verbose = options.verbose
    )

    for
      result <- MarklitRun
        .run(config)
        .mapError(e => new RuntimeException(e.pretty))

      // Surface the facade's informational notices (already verbose-gated).
      _ <- ZIO.foreachDiscard(result.notices)(Console.printLine(_))

      // Per-file summary + verbose error/diagnostic detail.
      _ <- ZIO.foreachDiscard(result.files) { file =>
        for
          _ <- Console.printLine(file.summary)
          _ <- ZIO.when(options.verbose && file.blockErrors.nonEmpty)(
            ZIO.foreachDiscard(file.blockErrors) { case (loc, msg) =>
              Console.printLine(s"  $loc: $msg")
            }
          )
          _ <- ZIO.when(options.verbose && file.failedCompiles.nonEmpty)(
            ZIO.foreachDiscard(file.failedCompiles) { case (loc, msg) =>
              Console.printLine(s"  $loc: $msg")
            }
          )
        yield ()
      }

      // Nonzero exit when any file failed (compile failures are data, not
      // exceptions, inside the facade — we translate them to a CLI failure here).
      _ <- ZIO
        .fail(new RuntimeException(s"${result.failedCount} file(s) failed"))
        .unless(result.success)
    yield ()
