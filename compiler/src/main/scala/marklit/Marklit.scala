package marklit

import marklit.compiler.*
import marklit.model.*
import marklit.parser.*
import marklit.processor.*
import marklit.scope.ScopeManager
import zio.*

import java.nio.file.{Files, Path}

/** Result of processing a markdown file */
final case class MarklitResult(
    sourceFile: Path,
    document: ParsedDocument,
    processingResult: DocumentResult
):
  def isSuccess: Boolean = processingResult.isSuccess

  def errors: Vector[(CodeBlock, MarklitError)] = processingResult.errors

  def summary: String =
    val status = if isSuccess then "SUCCESS" else "FAILED"
    val blockCount = document.codeBlocks.size
    val errorCount = errors.size
    s"$status: $sourceFile ($blockCount blocks, $errorCount errors, ${processingResult.processingTime.toMillis}ms)"

/** Main entry point for marklit document processing */
trait Marklit:
  /** Process a single markdown file */
  def processFile(path: Path): IO[MarklitError, MarklitResult]

  /** Process markdown content directly */
  def processContent(
      content: String,
      sourceName: String
  ): IO[MarklitError, MarklitResult]

  /** Process multiple files */
  def processFiles(paths: Vector[Path]): IO[MarklitError, Vector[MarklitResult]]

object Marklit:
  def processFile(path: Path): ZIO[Marklit, MarklitError, MarklitResult] =
    ZIO.serviceWithZIO[Marklit](_.processFile(path))

  def processContent(
      content: String,
      sourceName: String
  ): ZIO[Marklit, MarklitError, MarklitResult] =
    ZIO.serviceWithZIO[Marklit](_.processContent(content, sourceName))

  def processFiles(
      paths: Vector[Path]
  ): ZIO[Marklit, MarklitError, Vector[MarklitResult]] =
    ZIO.serviceWithZIO[Marklit](_.processFiles(paths))

  /** Create a layer with a fixed default Compiler. Per-block version requests
    * cannot resolve to a different compiler in this mode — the block will be
    * compiled by the default compiler and may fail if the requested version
    * differs significantly.
    *
    * Prefer [[layer]] for production, which wires in a [[CompilerFactory]]
    * capable of materializing arbitrary Scala 3 versions on demand.
    */
  def live: ZLayer[Compiler, Nothing, Marklit] =
    ZLayer.fromFunction { (compiler: Compiler) =>
      MarklitLive(CompilerServiceAdapter.fixed(compiler))
    }

  /** Create a layer using a pre-built [[CompilerFactory]] and a default
    * version. Per-block specific-version requests are resolved against the
    * factory.
    *
    * @param majorClasspaths
    *   per-major classpath overrides: when a cross-version block requests
    *   Scala major `m`, the adapter looks up `majorClasspaths(m)` and
    *   forwards that to the factory's `forVersion`. When no entry exists for
    *   the requested major, the cross-version compiler runs with no extra
    *   classpath (the safe default — see `CompilerServiceAdapter.compilerFor`
    *   for why we don't reuse the default-major classpath).
    */
  def liveWithFactory(
      defaultScalaVersion: String,
      defaultExtraClasspath: Vector[String] = Vector.empty,
      defaultScalacOptions: Vector[String] = Vector.empty,
      majorClasspaths: Map[String, Vector[String]] = Map.empty
  ): ZLayer[CompilerFactory, Nothing, Marklit] =
    ZLayer.fromZIO {
      for
        factory <- ZIO.service[CompilerFactory]
        defaultC <- factory.forVersion(
          defaultScalaVersion,
          defaultExtraClasspath,
          defaultScalacOptions
        )
        adapter = CompilerServiceAdapter.fromFactory(
          defaultC,
          factory,
          defaultScalacOptions,
          majorClasspaths
        )
      yield MarklitLive(adapter)
    }

  /** Fully configured layer that resolves a default-version compiler via
    * [[CompilerFactory]]. The factory caches by version, so when a document
    * opts into a specific version the same factory instance returns the right
    * compiler.
    */
  val layer: ZLayer[Any, Throwable, Marklit] =
    CompilerFactory.layer >>> liveWithFactory(
      CompilerFactory.defaultScalaVersion
    )

/** Adapter from Compiler(s) to CompilerService.
  *
  * Two flavors:
  *   - [[fixed]] uses one Compiler for everything; per-block version requests
  *     are ignored.
  *   - [[fromFactory]] holds a default compiler and a factory; per-block
  *     specific versions are resolved through the factory.
  */
private final class CompilerServiceAdapter(
    defaultCompiler: Compiler,
    factory: Option[CompilerFactory],
    scalacOptions: Vector[String],
    majorClasspaths: Map[String, Vector[String]]
) extends CompilerService:

  override def defaultScalaVersion: String = defaultCompiler.scalaVersion

  /** Per-major defaults used to resolve bare-major requests (`scala=2`,
    * `scala=3`). When the major matches the service's own default, that wins
    * (handled by the trait's base implementation). Otherwise we fall back to
    * the bundled shim version for the requested major — `defaultScalaVersion`
    * for `3`, `defaultScala2Version` for `2`.
    */
  override def defaultVersionForMajor(major: String): Option[String] =
    super.defaultVersionForMajor(major).orElse {
      major match
        case "3" => Some(CompilerFactory.defaultScalaVersion)
        case "2" => Some(CompilerFactory.defaultScala2Version)
        case _   => None
    }

  private def compilerFor(
      requested: Option[String]
  ): IO[MarklitError, Compiler] =
    requested match
      case None => ZIO.succeed(defaultCompiler)
      case Some(v) if v == defaultCompiler.scalaVersion =>
        ZIO.succeed(defaultCompiler)
      case Some(v) =>
        factory match
          // The default-major's extraClasspath was built against that major's
          // scala3-library/TASTy and is unsafe to forward to a different
          // major (would let user code reference symbols the requested version
          // doesn't have, or trigger TASTy/library-version errors). The
          // per-major classpath (when supplied by the build plugin) IS built
          // against this major and is safe to use; fall back to empty when no
          // override is configured.
          case Some(f) =>
            val major = v.takeWhile(_ != '.')
            val cp = majorClasspaths.getOrElse(major, Vector.empty)
            f.forVersion(v, cp, scalacOptions)
          case None => ZIO.succeed(defaultCompiler)

  private def buildContext(
      priorCode: Vector[String],
      isZIOApp: Boolean
  ): ScopeContext =
    val marker =
      if priorCode.nonEmpty then
        Some(s"__MARKLIT_${java.util.UUID.randomUUID()}__")
      else None
    ScopeContext(
      priorCode = priorCode,
      outputMarker = marker,
      isZIOApp = isZIOApp
    )

  override def compile(
      code: String,
      priorCode: Vector[String],
      isZIOApp: Boolean = false,
      scalaVersion: Option[String] = None
  ): IO[MarklitError, CompileResult] =
    compilerFor(scalaVersion).flatMap(
      _.compile(code, buildContext(priorCode, isZIOApp))
    )

  override def execute(
      code: String,
      priorCode: Vector[String],
      isZIOApp: Boolean = false,
      scalaVersion: Option[String] = None
  ): IO[MarklitError, String] =
    compilerFor(scalaVersion)
      .flatMap(_.execute(code, buildContext(priorCode, isZIOApp)))
      .map(_.output)

private object CompilerServiceAdapter:
  def fixed(compiler: Compiler): CompilerService =
    new CompilerServiceAdapter(compiler, None, Vector.empty, Map.empty)

  def fromFactory(
      defaultCompiler: Compiler,
      factory: CompilerFactory,
      scalacOptions: Vector[String],
      majorClasspaths: Map[String, Vector[String]] = Map.empty
  ): CompilerService =
    new CompilerServiceAdapter(
      defaultCompiler,
      Some(factory),
      scalacOptions,
      majorClasspaths
    )

/** Live implementation */
final class MarklitLive(compilerService: CompilerService) extends Marklit:

  override def processFile(path: Path): IO[MarklitError, MarklitResult] =
    for
      content <- ZIO
        .attempt(Files.readString(path))
        .mapError(e =>
          MarklitError.ParseError(
            Location(path.toString, 1, 1),
            s"Failed to read file: ${e.getMessage}"
          )
        )
      result <- processContent(content, path.toString)
    yield result.copy(sourceFile = path)

  override def processContent(
      content: String,
      sourceName: String
  ): IO[MarklitError, MarklitResult] =
    for
      // Parse markdown
      document <- MarkdownParser.parse(content, sourceName)

      // Create fresh scope manager for this document
      scopeManager <- ScopeManager.make

      // Create processor with the shared compiler service (factory-aware
      // when wired via Marklit.layer / liveWithFactory)
      processor = DocumentProcessorLive(scopeManager, compilerService)

      // Process all code blocks
      result <- processor.process(document.codeBlocks)
    yield MarklitResult(
      sourceFile = java.nio.file.Paths.get(sourceName),
      document = document,
      processingResult = result
    )

  override def processFiles(
      paths: Vector[Path]
  ): IO[MarklitError, Vector[MarklitResult]] =
    ZIO.foreach(paths)(processFile)
