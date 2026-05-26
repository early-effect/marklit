package marklit

import marklit.cache.{BlockCache, BlockCacheKey}
import marklit.compiler.*
import marklit.model.*
import marklit.parser.*
import marklit.processor.*
import marklit.scope.ScopeManager
import zio.*

import java.nio.file.{Files, Path}
import java.security.MessageDigest

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
      MarklitLive(CompilerServiceAdapter.fixed(compiler), ScopeMode.Isolated)
    }

  /** Create a layer using a pre-built [[CompilerFactory]] and a default
    * version. Per-block specific-version requests are resolved against the
    * factory.
    *
    * @param majorClasspaths
    *   per-major classpath overrides: when a cross-version block requests Scala
    *   major `m`, the adapter looks up `majorClasspaths(m)` and forwards that
    *   to the factory's `forVersion`. When no entry exists for the requested
    *   major, the cross-version compiler runs with no extra classpath (the safe
    *   default — see `CompilerServiceAdapter.compilerFor` for why we don't
    *   reuse the default-major classpath).
    */
  def liveWithFactory(
      defaultScalaVersion: String,
      defaultExtraClasspath: Vector[String] = Vector.empty,
      defaultScalacOptions: Vector[String] = Vector.empty,
      majorClasspaths: Map[String, Vector[String]] = Map.empty,
      cacheDir: Option[Path] = None,
      scopeMode: ScopeMode = ScopeMode.Isolated
  ): ZLayer[CompilerFactory, Nothing, Marklit] =
    ZLayer.fromZIO {
      for
        factory <- ZIO.service[CompilerFactory]
        defaultC <- factory.forVersion(
          defaultScalaVersion,
          defaultExtraClasspath,
          defaultScalacOptions
        )
        cache = cacheDir.map(BlockCache.disk).getOrElse(BlockCache.noop)
        adapter = CompilerServiceAdapter.fromFactory(
          defaultC,
          factory,
          defaultScalacOptions,
          defaultExtraClasspath,
          majorClasspaths,
          cache
        )
      yield MarklitLive(adapter, scopeMode)
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
  *
  * Cache integration: if a non-noop [[BlockCache]] is supplied and the caller
  * passes a [[Location]] with the request, we hash every input that affects raw
  * compiler output (block code, prior code, effective version, classpath,
  * scalac options, ZIO-app flag, source location) and consult the cache before
  * invoking the underlying compiler. Missing entries are populated on
  * write-back. Classpath jar content hashes are pre-computed once at adapter
  * construction (per-bucket) so per-block hashing stays cheap.
  */
private final class CompilerServiceAdapter(
    defaultCompiler: Compiler,
    factory: Option[CompilerFactory],
    scalacOptions: Vector[String],
    defaultClasspath: Vector[String],
    majorClasspaths: Map[String, Vector[String]],
    cache: BlockCache,
    defaultClasspathHashes: Vector[String],
    majorClasspathHashes: Map[String, Vector[String]]
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

  /** Build the per-block ScopeContext.
    *
    * The output marker MUST be deterministic from the block's inputs: it is
    * baked into the compiled class files at compile time (as a `print(marker)`
    * call ahead of the user code) and then matched at execute time to strip
    * prior-code replay output. Compile and execute go through separate adapter
    * calls — if the marker were a fresh `UUID.randomUUID()` each time, the
    * execute-time marker would never match the one in the .class files, and the
    * marker would leak verbatim into the rendered output.
    */
  private def buildContext(
      code: String,
      priorCode: Vector[String],
      isZIOApp: Boolean
  ): ScopeContext =
    val marker =
      if priorCode.nonEmpty then Some(blockMarker(code, priorCode, isZIOApp))
      else None
    ScopeContext(
      priorCode = priorCode,
      outputMarker = marker,
      isZIOApp = isZIOApp
    )

  private def blockMarker(
      code: String,
      priorCode: Vector[String],
      isZIOApp: Boolean
  ): String =
    val md = MessageDigest.getInstance("SHA-256")
    md.update(code.getBytes("UTF-8"))
    md.update(0.toByte)
    priorCode.foreach { p =>
      md.update(p.getBytes("UTF-8"))
      md.update(0.toByte)
    }
    md.update((if isZIOApp then 1 else 0).toByte)
    val hex = md.digest().take(16).map(b => f"$b%02x").mkString
    s"__MARKLIT_${hex}__"

  /** Pick the classpath + jar-content-hashes bucket appropriate to a request.
    *   - No version requested → default classpath/hashes.
    *   - Same major as default → default classpath/hashes (the per-major bucket
    *     is for a *different* major than this service's default).
    *   - Otherwise → per-major bucket if configured, empty otherwise.
    */
  private def classpathFor(
      requested: Option[String]
  ): (Vector[String], Vector[String]) =
    requested match
      case None => (defaultClasspath, defaultClasspathHashes)
      case Some(v) if v == defaultCompiler.scalaVersion =>
        (defaultClasspath, defaultClasspathHashes)
      case Some(v) =>
        val major = v.takeWhile(_ != '.')
        if major == defaultCompiler.scalaVersion.takeWhile(_ != '.') then
          (defaultClasspath, defaultClasspathHashes)
        else
          (
            majorClasspaths.getOrElse(major, Vector.empty),
            majorClasspathHashes.getOrElse(major, Vector.empty)
          )

  override def compile(
      code: String,
      priorCode: Vector[String],
      isZIOApp: Boolean,
      scalaVersion: Option[String],
      location: Option[Location],
      scopeConfig: ScopeConfig,
      scopeMode: ScopeMode
  ): IO[MarklitError, CompileResult] =
    val invoke =
      compilerFor(scalaVersion).flatMap(
        _.compile(code, buildContext(code, priorCode, isZIOApp))
      )
    location match
      case None      => invoke
      case Some(loc) =>
        val effective = scalaVersion.getOrElse(defaultCompiler.scalaVersion)
        val (cp, cpHashes) = classpathFor(scalaVersion)
        val key = BlockCacheKey.make(
          code = code,
          priorCode = priorCode,
          scalaVersion = effective,
          classpath = cp,
          classpathHashes = cpHashes,
          scalacOptions = scalacOptions,
          isZIOApp = isZIOApp,
          file = loc.file,
          startLine = loc.startLine,
          startColumn = loc.startColumn,
          scopeConfig = scopeConfig,
          scopeMode = scopeMode
        )
        cache.get(key).flatMap {
          case Some(hit) => ZIO.succeed(hit)
          case None      => invoke.tap(cr => cache.put(key, cr))
        }

  override def execute(
      code: String,
      priorCode: Vector[String],
      isZIOApp: Boolean,
      scalaVersion: Option[String],
      classFilesDir: Option[Path]
  ): IO[MarklitError, String] =
    val ctx = buildContext(code, priorCode, isZIOApp)
    compilerFor(scalaVersion).flatMap { c =>
      classFilesDir match
        case Some(dir) => c.executeFromDir(dir, ctx).map(_.output)
        case None      => c.execute(code, ctx).map(_.output)
    }

private object CompilerServiceAdapter:
  def fixed(compiler: Compiler): CompilerService =
    new CompilerServiceAdapter(
      compiler,
      None,
      Vector.empty,
      Vector.empty,
      Map.empty,
      BlockCache.noop,
      Vector.empty,
      Map.empty
    )

  def fromFactory(
      defaultCompiler: Compiler,
      factory: CompilerFactory,
      scalacOptions: Vector[String],
      defaultClasspath: Vector[String],
      majorClasspaths: Map[String, Vector[String]] = Map.empty,
      cache: BlockCache = BlockCache.noop
  ): CompilerService =
    val defaultHashes = hashClasspath(defaultClasspath)
    val majorHashes = majorClasspaths.view
      .mapValues(hashClasspath)
      .toMap
    new CompilerServiceAdapter(
      defaultCompiler,
      Some(factory),
      scalacOptions,
      defaultClasspath,
      majorClasspaths,
      cache,
      defaultHashes,
      majorHashes
    )

  /** Hash each classpath entry's bytes (jar or class file) once at adapter
    * construction so per-block cache keys don't have to re-read jars off disk
    * on every block. Directories are hashed by listing their files and
    * concatenating their (relative-path, size, mtime) triples — a cheap
    * approximation that catches the cases we care about (recompilation changes
    * class files' mtimes; adding a class file extends the listing).
    *
    * We deliberately use the path string as the fallback when a file can't be
    * hashed (missing, permission, unusual): this still varies the key if the
    * user's classpath layout changes, while never blowing up the build.
    */
  private def hashClasspath(entries: Vector[String]): Vector[String] =
    entries.map { entry =>
      try
        val p = java.nio.file.Paths.get(entry)
        if !java.nio.file.Files.exists(p) then s"missing:$entry"
        else if java.nio.file.Files.isDirectory(p) then hashDir(p)
        else hashFile(p)
      catch case _: Throwable => s"err:$entry"
    }

  private def hashFile(p: java.nio.file.Path): String =
    val md = MessageDigest.getInstance("SHA-256")
    val in = java.nio.file.Files.newInputStream(p)
    try
      val buf = new Array[Byte](64 * 1024)
      var n = in.read(buf)
      while n > 0 do
        md.update(buf, 0, n)
        n = in.read(buf)
    finally in.close()
    bytesToHex(md.digest())

  private def hashDir(p: java.nio.file.Path): String =
    val md = MessageDigest.getInstance("SHA-256")
    val it =
      java.nio.file.Files.walk(p).sorted(java.util.Comparator.naturalOrder())
    try
      it.forEach { f =>
        if java.nio.file.Files.isRegularFile(f) then
          md.update(p.relativize(f).toString.getBytes("UTF-8"))
          md.update(0.toByte)
          val attrs = java.nio.file.Files.readAttributes(
            f,
            classOf[java.nio.file.attribute.BasicFileAttributes]
          )
          md.update(attrs.size().toString.getBytes("UTF-8"))
          md.update(0.toByte)
          md.update(
            attrs.lastModifiedTime().toMillis.toString.getBytes("UTF-8")
          )
          md.update(0.toByte)
      }
    finally it.close()
    bytesToHex(md.digest())

  private def bytesToHex(bs: Array[Byte]): String =
    val sb = new StringBuilder(bs.length * 2)
    bs.foreach { b =>
      val v = b & 0xff
      if v < 16 then sb.append('0')
      sb.append(java.lang.Integer.toHexString(v))
    }
    sb.toString

/** Live implementation */
final class MarklitLive(
    compilerService: CompilerService,
    scopeMode: ScopeMode = ScopeMode.Isolated
) extends Marklit:

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
      processor = DocumentProcessorLive(
        scopeManager,
        compilerService,
        scopeMode
      )

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
