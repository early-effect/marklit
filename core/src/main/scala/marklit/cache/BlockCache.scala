package marklit.cache

import marklit.model.*
import zio.*
import zio.json.*

import java.nio.file.{Files, Path}
import java.security.MessageDigest

/** A persistent content-addressed cache for compile results.
  *
  * Each entry is keyed by a SHA-256 hash of every input that influences a
  * block's compile outcome — block source, prior code, effective Scala version,
  * classpath, scalac options, modifiers, and source location. Entries live in a
  * fan-out directory layout `<root>/<key[0..2]>/<key>.{json,classes}` to avoid
  * 100k-files-in-one-dir issues on older filesystems.
  *
  *   - `<key>.json` holds the diagnostics + success flag.
  *   - `<key>.classes/` holds the dotc-emitted class files, copied verbatim
  *     from the per-block compile output directory. The executor reads directly
  *     from this directory on a hit, so no materialization step is needed; the
  *     cached dir is treated as immutable.
  *
  * Compile-only: we cache `CompileResult` (success flag, diagnostics, class
  * files) but never execution output. Execute blocks always re-run because
  * their outputs may legitimately depend on side-effecting state we can't
  * capture.
  */
trait BlockCache:
  def get(key: BlockCacheKey): UIO[Option[CompileResult]]

  /** Persist the result. When [[result.classFilesDir]] is set and
    * [[result.success]] is true, the directory's contents are copied into the
    * cache entry; the cached `CompileResult` returned from a future [[get]]
    * will point at the cached copy.
    */
  def put(key: BlockCacheKey, result: CompileResult): UIO[Unit]
  def clear: UIO[Unit]

object BlockCache:

  /** A no-op cache. Useful when the user runs without `--cache-dir` (raw
    * one-shot CLI invocations) or for tests that want to bypass caching.
    */
  val noop: BlockCache = new BlockCache:
    def get(key: BlockCacheKey): UIO[Option[CompileResult]] = ZIO.none
    def put(key: BlockCacheKey, result: CompileResult): UIO[Unit] = ZIO.unit
    def clear: UIO[Unit] = ZIO.unit

  /** Disk-backed cache rooted at [[root]]. Misses (missing file, malformed
    * JSON) return `None`; we never fail the compile path because of a cache
    * read error. Writes are best-effort: a cache write failure is logged at the
    * layer above (here, swallowed) — the user still gets correct compilation.
    */
  def disk(root: Path): BlockCache = new DiskBlockCache(root)

private final class DiskBlockCache(root: java.nio.file.Path) extends BlockCache:

  private def jsonPath(key: BlockCacheKey): java.nio.file.Path =
    val k = key.value
    root.resolve(k.substring(0, 2)).resolve(s"$k.json")

  private def classesPath(key: BlockCacheKey): java.nio.file.Path =
    val k = key.value
    root.resolve(k.substring(0, 2)).resolve(s"$k.classes")

  override def get(key: BlockCacheKey): UIO[Option[CompileResult]] =
    ZIO
      .attemptBlocking {
        val p = jsonPath(key)
        if !Files.isRegularFile(p) then None
        else
          val s = Files.readString(p)
          s.fromJson[CachedCompileResult].toOption.map { cached =>
            val classes = classesPath(key)
            val classDir =
              if cached.success && Files.isDirectory(classes) then Some(classes)
              else None
            cached.toCompileResult(classDir)
          }
      }
      .catchAll(_ => ZIO.none)

  override def put(key: BlockCacheKey, result: CompileResult): UIO[Unit] =
    ZIO
      .attemptBlocking {
        val p = jsonPath(key)
        Files.createDirectories(p.getParent): Unit

        // Copy class files first; only on success do we publish the JSON
        // entry, so a partial write never appears as a hit.
        result.classFilesDir.filter(_ => result.success).foreach { src =>
          if Files.isDirectory(src) then
            val dest = classesPath(key)
            // Wipe any prior cached classes for this key; replace, not merge.
            if Files.exists(dest) then deleteRecursive(dest)
            copyDirectory(src, dest)
        }

        val tmp = Files.createTempFile(p.getParent, s"${key.value}-", ".tmp")
        Files.writeString(
          tmp,
          CachedCompileResult.from(result).toJson
        )
        Files.move(
          tmp,
          p,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE
        ): Unit
      }
      .catchAll(_ => ZIO.unit)

  override def clear: UIO[Unit] =
    ZIO
      .attemptBlocking {
        if Files.isDirectory(root) then deleteRecursive(root, keepRoot = true)
      }
      .catchAll(_ => ZIO.unit)

  private def copyDirectory(src: Path, dest: Path): Unit =
    Files.createDirectories(dest): Unit
    val it = Files.walk(src)
    try
      it.forEach { p =>
        val rel = src.relativize(p)
        val target = dest.resolve(rel.toString)
        if Files.isDirectory(p) then Files.createDirectories(target): Unit
        else
          Files.copy(
            p,
            target,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
          ): Unit
      }
    finally it.close()

  private def deleteRecursive(p: Path, keepRoot: Boolean = false): Unit =
    val it = Files.walk(p).sorted(java.util.Comparator.reverseOrder())
    try
      it.forEach { f =>
        if !(keepRoot && f == p) then Files.deleteIfExists(f): Unit
      }
    finally it.close()

/** A 64-character lowercase hex SHA-256 of the cache inputs. The wrapper type
  * keeps us from accidentally treating a raw user string as a key.
  */
final case class BlockCacheKey(value: String) extends AnyVal

object BlockCacheKey:

  /** Build a key from every input that affects raw compiler output.
    *
    * Modifiers like `expectsFailure` / `expectsWarnings` are deliberately *not*
    * keyed — they alter how the document processor interprets the result (a
    * "fail" block flips a compile failure into a successful BlockResult), but
    * they don't change the bytes the compiler produces. Caching the raw
    * [[CompileResult]] and letting the wrapper layer reinterpret it on every
    * run is correct and keeps the key narrow.
    *
    * Adding a field here invalidates every existing cache entry — that's the
    * correct behavior, since old entries were computed without considering the
    * new input. If you're tempted to keep entries across a key-shape change:
    * don't. Bump the version prefix below instead, which also invalidates
    * cleanly.
    */
  def make(
      code: String,
      priorCode: Vector[String],
      scalaVersion: String,
      classpath: Vector[String],
      classpathHashes: Vector[String],
      scalacOptions: Vector[String],
      isZIOApp: Boolean,
      file: String,
      startLine: Int,
      startColumn: Int,
      scopeConfig: ScopeConfig = ScopeConfig.empty,
      scopeMode: ScopeMode = ScopeMode.Isolated,
      topLevel: Boolean = false,
      topLevelPriorCode: Vector[String] = Vector.empty
  ): BlockCacheKey =
    val md = MessageDigest.getInstance("SHA-256")
    def feed(s: String): Unit =
      md.update(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      md.update(0.toByte)
    // Version prefix — bump on key-shape changes to invalidate old entries.
    feed("v3")
    feed(scalaVersion)
    feed(file)
    feed(startLine.toString)
    feed(startColumn.toString)
    feed(if isZIOApp then "z" else "n")
    feed(if topLevel then "t" else "w")
    feed("hoist")
    md.update(topLevelPriorCode.size.toString.getBytes("UTF-8"))
    md.update(0.toByte)
    topLevelPriorCode.foreach(feed)
    feed("scalac")
    scalacOptions.foreach(feed)
    feed("cp")
    classpath.foreach(feed)
    feed("cph")
    classpathHashes.foreach(feed)
    feed("priors")
    md.update(priorCode.size.toString.getBytes("UTF-8"))
    md.update(0.toByte)
    priorCode.foreach(feed)
    feed("code")
    feed(code)
    feed("scope")
    feed(scopeMode.toString)
    feed(scopeConfig.id.getOrElse(""))
    feed(scopeConfig.extendsScope.getOrElse(""))
    feed(if scopeConfig.append then "a" else "n")
    feed(scopeConfig.scalaVersion.getOrElse(""))
    val digest = md.digest()
    val sb = new StringBuilder(digest.length * 2)
    digest.foreach { b =>
      val v = b & 0xff
      if v < 16 then sb.append('0')
      sb.append(java.lang.Integer.toHexString(v))
    }
    BlockCacheKey(sb.toString)

/** On-disk JSON shape for the metadata side of a cached CompileResult. Class
  * files live in a sibling `<key>.classes/` directory and are wired back in by
  * [[DiskBlockCache.get]] — they aren't carried in the JSON.
  */
private final case class CachedCompileResult(
    success: Boolean,
    diagnostics: List[CachedDiagnostic]
):
  def toCompileResult(classFilesDir: Option[Path]): CompileResult =
    CompileResult(
      success = success,
      diagnostics = diagnostics.map(_.toScalaDiagnostic),
      classFilesDir = classFilesDir
    )

private object CachedCompileResult:
  given JsonCodec[CachedCompileResult] =
    DeriveJsonCodec.gen[CachedCompileResult]

  def from(cr: CompileResult): CachedCompileResult =
    CachedCompileResult(
      success = cr.success,
      diagnostics = cr.diagnostics.map(CachedDiagnostic.from)
    )

private final case class CachedDiagnostic(
    severity: String,
    message: String,
    line: Int,
    column: Int,
    file: Option[String]
):
  def toScalaDiagnostic: ScalaDiagnostic =
    ScalaDiagnostic(
      severity = severity match
        case "error"   => DiagnosticSeverity.Error
        case "warning" => DiagnosticSeverity.Warning
        case _         => DiagnosticSeverity.Info,
      message = message,
      line = line,
      column = column,
      file = file
    )

private object CachedDiagnostic:
  given JsonCodec[CachedDiagnostic] = DeriveJsonCodec.gen[CachedDiagnostic]

  def from(d: ScalaDiagnostic): CachedDiagnostic =
    CachedDiagnostic(
      severity = d.severity match
        case DiagnosticSeverity.Error   => "error"
        case DiagnosticSeverity.Warning => "warning"
        case DiagnosticSeverity.Info    => "info"
      ,
      message = d.message,
      line = d.line,
      column = d.column,
      file = d.file
    )
