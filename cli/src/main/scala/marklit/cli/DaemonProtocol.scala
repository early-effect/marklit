package marklit.cli

import zio.json.*

/** JSON-RPC protocol for marklit's daemon mode.
  *
  * One request per line over stdin, one response per line over stdout. The
  * structure is intentionally close to [[MarklitOptions]] so a daemon request
  * is roughly equivalent to "the CLI options for one invocation." Fields that
  * only make sense at the CLI level (e.g. `daemon`, `idle-timeout`, `watch`)
  * are not part of the protocol.
  *
  * Methods:
  *   - `compile-document` — process one or more markdown files; returns a
  *     [[CompileResponse]] with per-file success.
  *   - `clear-cache` — placeholder for the disk cache landing in Phase B.
  *     Currently a no-op that returns `ok`.
  *   - `shutdown` — daemon writes an ack and exits its loop.
  */
object DaemonProtocol:

  /** Tag-discriminated request union. zio-json's `@jsonDiscriminator` lets us
    * use a sum-of-records ADT without writing AST-level codecs.
    */
  @jsonDiscriminator("method")
  sealed trait Request
  object Request:
    @jsonHint("compile-document")
    final case class CompileDocument(params: CompileParams) extends Request
    @jsonHint("clear-cache")
    final case class ClearCache(params: ClearCacheParams) extends Request
    @jsonHint("shutdown")
    case object Shutdown extends Request

    given JsonCodec[Request] = DeriveJsonCodec.gen[Request]

  /** Parameters for the `clear-cache` RPC. The daemon doesn't keep cache state
    * between requests; the caller tells it which directory to clear.
    */
  final case class ClearCacheParams(cacheDir: String)
  object ClearCacheParams:
    given JsonCodec[ClearCacheParams] = DeriveJsonCodec.gen[ClearCacheParams]

  /** A single compile request. Mirrors the subset of [[MarklitOptions]] that
    * makes sense over the wire. Paths are strings (the daemon resolves them).
    */
  final case class CompileParams(
      inputFiles: List[String],
      outputDir: Option[String],
      verbose: Boolean = false,
      check: Boolean = false,
      showVersionInOutput: Boolean = true,
      classpath: Option[String] = None,
      classpath2: Option[String] = None,
      classpath3: Option[String] = None,
      dependencies: List[String] = Nil,
      repositories: List[String] = Nil,
      scalaVersion: Option[String] = None,
      cacheDir: Option[String] = None
  )
  object CompileParams:
    given JsonCodec[CompileParams] = DeriveJsonCodec.gen[CompileParams]

  /** Tag-discriminated response union. */
  @jsonDiscriminator("status")
  sealed trait Response
  object Response:
    @jsonHint("ok")
    final case class Ok(method: String, result: Option[CompileResponse] = None)
        extends Response
    @jsonHint("error")
    final case class Err(method: String, message: String) extends Response

    given JsonCodec[Response] = DeriveJsonCodec.gen[Response]

  /** Per-file outcome from one compile-document request. */
  final case class FileResult(
      file: String,
      success: Boolean,
      summary: String,
      errors: List[String]
  )
  object FileResult:
    given JsonCodec[FileResult] = DeriveJsonCodec.gen[FileResult]

  final case class CompileResponse(files: List[FileResult]):
    def isSuccess: Boolean = files.forall(_.success)
  object CompileResponse:
    given JsonCodec[CompileResponse] = DeriveJsonCodec.gen[CompileResponse]
