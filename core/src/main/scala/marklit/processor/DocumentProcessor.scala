package marklit.processor

import marklit.model.*
import marklit.scope.*
import marklit.scope.Scope as MarklitScope
import zio.*

/** Result of processing a single code block */
final case class BlockResult(
    block: CodeBlock,
    compileResult: Option[CompileResult],
    executionOutput: Option[String],
    error: Option[MarklitError],
    skipped: Boolean = false,
    effectiveScalaVersion: Option[String] = None
):
  /** A block is successful if:
    *   - It was skipped (version mismatch), OR
    *   - Compilation succeeded AND no unexpected errors occurred For crash
    *     blocks, a RuntimeError is expected and counts as success
    */
  def isSuccess: Boolean =
    if skipped then true
    else if block.expectsCrash then
      // Crash blocks succeed if they got the expected RuntimeError
      error match
        case Some(_: MarklitError.RuntimeError) =>
          compileResult.forall(_.success)
        case None    => false // Expected crash but didn't get one
        case Some(_) => false // Got unexpected error type
    else error.isEmpty && compileResult.forall(_.success)

/** Result of processing an entire document */
final case class DocumentResult(
    blockResults: Vector[BlockResult],
    processingTime: java.time.Duration
):
  def isSuccess: Boolean = blockResults.forall(_.isSuccess)

  /** Returns unexpected errors (excludes expected RuntimeErrors from crash
    * blocks)
    */
  def errors: Vector[(CodeBlock, MarklitError)] =
    blockResults.flatMap { br =>
      br.error.flatMap { e =>
        // Don't count expected crash errors
        if br.block.expectsCrash && e.isInstanceOf[MarklitError.RuntimeError]
        then None
        else Some((br.block, e))
      }
    }

  def compileErrors: Vector[(CodeBlock, List[ScalaDiagnostic])] =
    blockResults.flatMap { br =>
      br.compileResult
        .filterNot(_.success)
        .map(cr => (br.block, cr.diagnostics))
    }

/** Processes markdown documents with Scala code blocks */
trait DocumentProcessor:
  /** Process all code blocks in document order */
  def process(blocks: Vector[CodeBlock]): IO[MarklitError, DocumentResult]

object DocumentProcessor:
  def process(
      blocks: Vector[CodeBlock]
  ): ZIO[DocumentProcessor, MarklitError, DocumentResult] =
    ZIO.serviceWithZIO[DocumentProcessor](_.process(blocks))

/** Compiler abstraction for DocumentProcessor - decoupled from actual compiler
  * impl.
  *
  * `scalaVersion` is the version of the *default* compiler this service is
  * bound to (used for filtering blocks that target a different major). When a
  * block requests a specific version (e.g. `scala=3.7.0`), the service must
  * obtain a compiler for that version via [[forVersion]].
  */
trait CompilerService:
  /** The default Scala version used when a block does not specify one. */
  def defaultScalaVersion: String

  /** A default version for the requested major. Used to resolve bare-major
    * scope-config requests (`scala=2`, `scala=3`) to a concrete version when
    * the service's [[defaultScalaVersion]] is not in the requested major. The
    * default for the matching major is [[defaultScalaVersion]] itself.
    *
    * Returns `None` if marklit can't pick a default for that major (e.g. an
    * unsupported major like `1`); the block is then skipped.
    */
  def defaultVersionForMajor(major: String): Option[String] =
    if defaultScalaVersion.takeWhile(_ != '.') == major then
      Some(defaultScalaVersion)
    else None

  def compile(
      code: String,
      priorCode: Vector[String],
      isZIOApp: Boolean,
      scalaVersion: Option[String],
      location: Option[Location],
      scopeConfig: ScopeConfig = ScopeConfig.empty,
      scopeMode: ScopeMode = ScopeMode.Isolated
  ): IO[MarklitError, CompileResult]

  def execute(
      code: String,
      priorCode: Vector[String],
      isZIOApp: Boolean,
      scalaVersion: Option[String],
      classFilesDir: Option[java.nio.file.Path]
  ): IO[MarklitError, String]

/** Live implementation that processes blocks sequentially, respecting scope
  * dependencies
  */
final class DocumentProcessorLive(
    scopeManager: ScopeManager,
    compiler: CompilerService,
    scopeMode: ScopeMode = ScopeMode.Isolated
) extends DocumentProcessor:

  private val scalaVersion: String = compiler.defaultScalaVersion

  /** Compute the version a block will be compiled against. Mirrors the
    * precedence used by [[processBlock]]: per-block specific version, then
    * bare-major, then `shared-{mv}` major, then service default.
    *
    * Returns `Left(())` when a bare-major has no default (block is skipped).
    */
  private def effectiveVersionFor(
      block: CodeBlock
  ): Either[Unit, String] =
    block.requestedSpecificScalaVersion match
      case Some(v) => Right(v)
      case None    =>
        block.scopeConfig.scalaVersion match
          case Some(bareMajor) =>
            compiler.defaultVersionForMajor(bareMajor).toRight(())
          case None =>
            block.sharedMajor match
              case Some(m) =>
                compiler.defaultVersionForMajor(m).toRight(())
              case None => Right(scalaVersion)

  /** Under page-scope, rewrite each otherwise-anonymous block's config to the
    * exact shape a user could write by hand: the first such block (per Scala
    * version) becomes `id=__page__<v>`, every subsequent one becomes
    * `extends=__page__<v>, append`. Blocks that already carry an explicit
    * `id`/`extends`/`append`, plus `passthrough`, `shared`, `fail`, `crash`,
    * and `warn` blocks, are passed through untouched — they opt out of the page
    * scope the same way they would in a hand-written file.
    */
  private def applyPageScope(
      blocks: Vector[CodeBlock]
  ): Vector[CodeBlock] =
    if scopeMode != ScopeMode.Page then blocks
    else
      val (rewritten, _) = blocks.foldLeft(
        (Vector.empty[CodeBlock], Set.empty[String])
      ) { case ((acc, initialized), block) =>
        val cfg = block.scopeConfig
        val isAnon = cfg.id.isEmpty && cfg.extendsScope.isEmpty && !cfg.append
        val opaque =
          block.isPassthrough || block.isAnyShared ||
            block.expectsFailure || block.expectsCrash || block.expectsWarnings
        if !isAnon || opaque then (acc :+ block, initialized)
        else
          effectiveVersionFor(block) match
            case Left(_)  => (acc :+ block, initialized) // will be skipped
            case Right(v) =>
              val pageId = MarklitScope.pageIdFor(v)
              val newCfg =
                if initialized.contains(pageId) then
                  cfg.copy(extendsScope = Some(pageId), append = true)
                else cfg.copy(id = Some(pageId))
              (acc :+ block.copy(scopeConfig = newCfg), initialized + pageId)
      }
      rewritten

  override def process(
      blocks: Vector[CodeBlock]
  ): IO[MarklitError, DocumentResult] =
    val startTime = java.time.Instant.now()

    // Discover every version that will use a per-version default scope —
    // any block with no explicit id/extends/append contributes its effective
    // version. Shared blocks count too: they live in their own version's
    // default scope. The CLI/file default version is always included.
    val versionsInUse: Vector[String] =
      (blocks.iterator.collect {
        case b
            if !b.isPassthrough &&
              b.scopeConfig.id.isEmpty &&
              b.scopeConfig.extendsScope.isEmpty &&
              !b.scopeConfig.append =>
          b.requestedSpecificScalaVersion
            .orElse {
              b.scopeConfig.scalaVersion
                .flatMap(compiler.defaultVersionForMajor)
            }
            .orElse {
              // shared-{mv} contributes to its major's default scope; make sure
              // that scope exists in versionsInUse even if no concrete block in
              // the document declares that major.
              b.sharedMajor.flatMap(compiler.defaultVersionForMajor)
            }
            .getOrElse(scalaVersion)
      }.toVector :+ scalaVersion).distinct

    // Pre-seed phase: for every shared block (in document order), inject its
    // code into every applicable version-default scope. After this phase,
    // every per-version default scope's `priorCode` begins with all the
    // shared blocks that apply to it — exactly the "prepended at document
    // start" semantic.
    val sharedBlocks = blocks.filter(b =>
      b.isAnyShared && !b.isPassthrough &&
        !b.expectsFailure && !b.expectsCrash &&
        b.isCompatibleWith(scalaVersion)
    )
    val seedAllShared: UIO[Unit] =
      ZIO.foreachDiscard(sharedBlocks) { sb =>
        val targets = versionsInUse.filter(sb.appliesToDefaultScope)
        ZIO.foreachDiscard(targets) { v =>
          scopeManager.seedDefaultPriorCode(v, sb.code)
        }
      }

    val processed = applyPageScope(blocks)
    // Pair each processed (possibly page-scope-rewritten) block with its
    // original CodeBlock so BlockResult.block remains the instance that
    // appears in document.segments. The renderer keys block lookups by
    // identity, so a rewritten block here would render with no output.
    val pairs = processed.zip(blocks)
    seedAllShared *>
      ZIO
        .foreach(pairs) { case (proc, original) =>
          processBlock(proc).map(br => br.copy(block = original))
        }
        .map { results =>
          val endTime = java.time.Instant.now()
          DocumentResult(
            results,
            java.time.Duration.between(startTime, endTime)
          )
        }

  private def processBlock(block: CodeBlock): IO[MarklitError, BlockResult] =
    // Resolve the version this block compiles against. Precedence:
    //   1. Per-block specific version (e.g. `scala=3.7.0`) — exact request.
    //   2. Per-block bare-major (e.g. `scala=2`) — pick a default for that
    //      major. When the service's default already matches, that version
    //      is used; otherwise CompilerService picks a per-major default
    //      (e.g. the bundled 2.13 shim version). If no default exists for
    //      the major, skip the block.
    //   3. Service default — for blocks with no version request.
    val resolvedVersion: Option[Either[Unit, String]] =
      block.requestedSpecificScalaVersion match
        case Some(v) => Some(Right(v))
        case None    =>
          block.scopeConfig.scalaVersion match
            case Some(bareMajor) =>
              compiler.defaultVersionForMajor(bareMajor) match
                case Some(v) => Some(Right(v))
                case None    => Some(Left(()))
            case None =>
              // A `shared-{mv}` block belongs to its major's default scope; if
              // we let it inherit the file default, it would land on a scope
              // pre-seeded with shared-{other-mv} and produce duplicate
              // definitions. Resolve to a default for its declared major.
              block.sharedMajor.flatMap(compiler.defaultVersionForMajor) match
                case Some(v) => Some(Right(v))
                case None    =>
                  if block.sharedMajor.isDefined then Some(Left(())) else None

    val (effectiveVersion: String, requestedVersion: Option[String]) =
      resolvedVersion match
        case Some(Right(v)) =>
          (v, if v == scalaVersion then None else Some(v))
        case _ => (scalaVersion, None)

    // Handle passthrough blocks - no processing
    if block.isPassthrough then
      ZIO.succeed(BlockResult(block, None, None, None))
    // Skip when bare-major requests a major we have no default for.
    else if resolvedVersion.contains(Left(())) then
      ZIO.succeed(BlockResult(block, None, None, None, skipped = true))
    else
      val effect = for
        // Resolve scope and get inherited code
        resolved <- scopeManager.resolveScope(
          block.scopeConfig,
          block.location,
          Some(effectiveVersion)
        )
        rawPriorCode = resolved.inheritedCode ++ resolved.scope.priorCode
        // Shared blocks were pre-seeded into their own version's default
        // scope, so when we resolve to that scope we'd see ourselves in the
        // priorCode. Strip the block's own code to avoid compiling it twice.
        allPriorCode =
          if block.isAnyShared then rawPriorCode.filterNot(_ == block.code)
          else rawPriorCode

        // Compile
        compileResult <- compileBlock(block, allPriorCode, requestedVersion)

        // Execute if compilation succeeded and block should execute
        execResult <- executeBlock(
          block,
          allPriorCode,
          compileResult,
          requestedVersion
        )

        // Record code in scope for subsequent blocks. Skip:
        //  - fail/crash blocks (intentionally broken code)
        //  - shared blocks (already pre-seeded in their version's default)
        _ <- ZIO.unless(
          block.expectsFailure || block.expectsCrash || block.isAnyShared
        )(
          scopeManager.recordCode(resolved.scope.id, block.code)
        )
      yield BlockResult(
        block = block,
        compileResult = Some(compileResult),
        executionOutput = execResult.output,
        error = execResult.error, // Captured error for crash blocks
        effectiveScalaVersion = Some(effectiveVersion)
      )

      effect.catchAll { error =>
        ZIO.succeed(
          BlockResult(
            block,
            None,
            None,
            Some(error),
            effectiveScalaVersion = Some(effectiveVersion)
          )
        )
      }

  private def compileBlock(
      block: CodeBlock,
      priorCode: Vector[String],
      requestedVersion: Option[String]
  ): IO[MarklitError, CompileResult] =
    val v = requestedVersion
    val loc = Some(block.location)
    if block.expectsFailure then
      // For fail blocks, we expect compilation to fail
      compiler
        .compile(
          block.code,
          priorCode,
          block.isZIOApp,
          v,
          loc,
          block.scopeConfig,
          scopeMode
        )
        .either
        .map {
          case Left(_) =>
            // Expected failure - treat as success
            CompileResult(success = true, diagnostics = Nil)
          case Right(cr) if !cr.success =>
            // Got expected failure
            CompileResult(success = true, diagnostics = cr.diagnostics)
          case Right(_) =>
            // Compilation succeeded when it should have failed
            CompileResult(
              success = false,
              diagnostics = List(
                ScalaDiagnostic(
                  DiagnosticSeverity.Error,
                  "Expected compilation failure, but code compiled successfully",
                  block.location.startLine,
                  block.location.startColumn,
                  Some(block.location.file)
                )
              )
            )
        }
    else if block.expectsWarnings then
      // For warn blocks, we expect compilation warnings
      compiler
        .compile(
          block.code,
          priorCode,
          block.isZIOApp,
          v,
          loc,
          block.scopeConfig,
          scopeMode
        )
        .map { cr =>
          val hasWarnings = cr.warnings.nonEmpty
          if hasWarnings then
            // Got expected warnings - success
            cr
          else
            // No warnings when we expected some
            CompileResult(
              success = false,
              diagnostics = List(
                ScalaDiagnostic(
                  DiagnosticSeverity.Error,
                  "Expected compilation warnings, but code compiled without warnings",
                  block.location.startLine,
                  block.location.startColumn,
                  Some(block.location.file)
                )
              )
            )
        }
    else
      compiler.compile(
        block.code,
        priorCode,
        block.isZIOApp,
        v,
        loc,
        block.scopeConfig,
        scopeMode
      )

  /** Result of execution, including both output and any captured error */
  private case class ExecResult(
      output: Option[String],
      error: Option[MarklitError]
  )

  private def executeBlock(
      block: CodeBlock,
      priorCode: Vector[String],
      compileResult: CompileResult,
      requestedVersion: Option[String]
  ): IO[MarklitError, ExecResult] =
    val v = requestedVersion
    val dir = compileResult.classFilesDir
    if compileResult.success && block.shouldExecute then
      if block.expectsCrash then
        // For crash blocks, expect runtime exception - capture it for display
        compiler
          .execute(block.code, priorCode, block.isZIOApp, v, dir)
          .either
          .map {
            case Left(err @ MarklitError.RuntimeError(_, output)) =>
              ExecResult(
                if output.nonEmpty then Some(output) else None,
                Some(err)
              ) // Expected crash
            case Left(e) =>
              ExecResult(Some(s"Unexpected error: ${e.pretty}"), None)
            case Right(output) =>
              ExecResult(
                Some(
                  s"Expected runtime crash, but execution succeeded with output: $output"
                ),
                None
              )
          }
      else
        compiler
          .execute(block.code, priorCode, block.isZIOApp, v, dir)
          .map(out => ExecResult(Some(out), None))
    else ZIO.succeed(ExecResult(None, None))

object DocumentProcessorLive:
  def layer: URLayer[ScopeManager & CompilerService, DocumentProcessor] =
    ZLayer.fromFunction(DocumentProcessorLive(_, _))
