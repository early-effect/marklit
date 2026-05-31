package marklit.processor

import marklit.model.*
import marklit.scope.*
import marklit.scope.Scope as MarklitScope
import zio.*

/** A single per-version compile+execute attempt for a cross-version block.
  *
  * Populated only for `scala=shared` / `scala=shared-{mv}` blocks, which are
  * compiled and executed once per applicable version in the document. For
  * non-shared blocks [[BlockResult.crossExecutions]] is empty.
  */
final case class BlockExecution(
    scalaVersion: String,
    compileResult: Option[CompileResult],
    executionOutput: Option[String],
    error: Option[MarklitError]
)

/** Result of processing a single code block.
  *
  * For non-shared blocks, [[compileResult]] / [[executionOutput]] / [[error]]
  * hold the single result and [[crossExecutions]] is empty. For shared blocks
  * (`scala=shared`, `scala=shared-{mv}`) all per-version attempts are in
  * [[crossExecutions]]; the legacy single fields mirror the *first*
  * cross-execution so existing single-result consumers keep working.
  */
final case class BlockResult(
    block: CodeBlock,
    compileResult: Option[CompileResult],
    executionOutput: Option[String],
    error: Option[MarklitError],
    skipped: Boolean = false,
    effectiveScalaVersion: Option[String] = None,
    crossExecutions: Vector[BlockExecution] = Vector.empty
):
  private def isExecutionSuccess(
      cr: Option[CompileResult],
      err: Option[MarklitError]
  ): Boolean =
    if block.expectsCrash then
      err match
        case Some(_: MarklitError.RuntimeError) => cr.forall(_.success)
        case None                               => false
        case Some(_)                            => false
    else err.isEmpty && cr.forall(_.success)

  /** A block is successful if:
    *   - It was skipped (version mismatch), OR
    *   - For non-shared blocks: compilation succeeded AND no unexpected errors
    *     occurred. For crash blocks, a RuntimeError is expected and counts as
    *     success.
    *   - For shared blocks: every cross-version execution succeeded under the
    *     same rules.
    */
  def isSuccess: Boolean =
    if skipped then true
    else if crossExecutions.nonEmpty then
      crossExecutions.forall(x =>
        isExecutionSuccess(x.compileResult, x.error)
      )
    else isExecutionSuccess(compileResult, error)

/** Result of processing an entire document */
final case class DocumentResult(
    blockResults: Vector[BlockResult],
    processingTime: java.time.Duration
):
  def isSuccess: Boolean = blockResults.forall(_.isSuccess)

  /** Returns unexpected errors (excludes expected RuntimeErrors from crash
    * blocks). Walks per-version cross-executions for shared blocks so a
    * failure on any single cross version is surfaced.
    */
  def errors: Vector[(CodeBlock, MarklitError)] =
    blockResults.flatMap { br =>
      val errs =
        if br.crossExecutions.nonEmpty then br.crossExecutions.flatMap(_.error)
        else br.error.toVector
      errs.flatMap { e =>
        if br.block.expectsCrash && e.isInstanceOf[MarklitError.RuntimeError]
        then None
        else Some((br.block, e))
      }
    }

  def compileErrors: Vector[(CodeBlock, List[ScalaDiagnostic])] =
    blockResults.flatMap { br =>
      val crs =
        if br.crossExecutions.nonEmpty then br.crossExecutions.flatMap(_.compileResult)
        else br.compileResult.toVector
      crs.filterNot(_.success).map(cr => (br.block, cr.diagnostics))
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
      scopeMode: ScopeMode = ScopeMode.Isolated,
      topLevel: Boolean = false,
      topLevelPriorCode: Vector[String] = Vector.empty
  ): IO[MarklitError, CompileResult]

  def execute(
      code: String,
      priorCode: Vector[String],
      isZIOApp: Boolean,
      scalaVersion: Option[String],
      classFilesDir: Option[java.nio.file.Path],
      topLevel: Boolean = false,
      topLevelPriorCode: Vector[String] = Vector.empty
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
            block.expectsFailure || block.expectsCrash ||
            block.expectsWarnings || block.isTopLevel
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
              !b.isTopLevel &&
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
          processBlock(proc, versionsInUse).map(br => br.copy(block = original))
        }
        .map { results =>
          val endTime = java.time.Instant.now()
          DocumentResult(
            results,
            java.time.Duration.between(startTime, endTime)
          )
        }

  private def processBlock(
      block: CodeBlock,
      versionsInUse: Vector[String]
  ): IO[MarklitError, BlockResult] =
    // `top-level` is strict: it may only accompany scope options and a version
    // selector. Reject illegal combinations (e.g. top-level,silent) up front —
    // the error flows through the same path as a compile/runtime failure and
    // fails the run via DocumentResult.errors.
    block.modifierConflicts match
      case Some(msg) =>
        ZIO.succeed(
          BlockResult(
            block,
            None,
            None,
            Some(MarklitError.ValidationError(block.location, msg))
          )
        )
      case None => processBlockChecked(block, versionsInUse)

  private def processBlockChecked(
      block: CodeBlock,
      versionsInUse: Vector[String]
  ): IO[MarklitError, BlockResult] =
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
    // `scala=shared` / `scala=shared-{mv}` blocks fan out: compile+execute
    // once per applicable version in use, and never participate in a normal
    // (non-default) scope.
    else if block.isAnyShared then
      processSharedBlock(block, effectiveVersion, versionsInUse)
    else
      val effect = for
        // Resolve scope and get inherited code, split into hoisted top-level
        // definitions (emitted at file scope) and run-body prior code.
        resolved <- scopeManager.resolveScope(
          block.scopeConfig,
          block.location,
          Some(effectiveVersion),
          block.isTopLevel
        )
        // The resolved scope's own priorCode is folded into the correct bucket
        // by hoistCode/bodyCode based on the scope's kind.
        hoistCode = resolved.hoistCode
        bodyCode = resolved.bodyCode

        // Compile
        compileResult <- compileBlock(
          block,
          bodyCode,
          requestedVersion,
          hoistCode
        )

        // Execute if compilation succeeded and block should execute
        execResult <- executeBlock(
          block,
          bodyCode,
          compileResult,
          requestedVersion,
          hoistCode
        )

        // Record code in scope for subsequent blocks. Skip fail/crash blocks
        // (intentionally broken code).
        _ <- ZIO.unless(block.expectsFailure || block.expectsCrash)(
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

  /** Fan-out path for `scala=shared` / `scala=shared-{mv}`: compile+execute the
    * same source against every applicable version in use, gathering one
    * [[BlockExecution]] per version. The block is already pre-seeded into each
    * version's default-scope priorCode, so we strip the block's own code from
    * the inherited prior to avoid compiling it twice.
    */
  private def processSharedBlock(
      block: CodeBlock,
      fallbackVersion: String,
      versionsInUse: Vector[String]
  ): IO[MarklitError, BlockResult] =
    val applicable = versionsInUse.filter(block.appliesToDefaultScope)
    val targets =
      if applicable.isEmpty then Vector(fallbackVersion) else applicable

    ZIO
      .foreach(targets) { v =>
        val effect = for
          resolved <- scopeManager.resolveScope(
            ScopeConfig.empty,
            block.location,
            Some(v)
          )
          rawPrior = resolved.inheritedCode ++ resolved.scope.priorCode
          // Strip the block's own code to avoid compiling it twice — it was
          // already pre-seeded into this version's default scope.
          allPrior = rawPrior.filterNot(_ == block.code)
          requested = if v == scalaVersion then None else Some(v)
          compileResult <- compileBlock(block, allPrior, requested)
          execResult <-
            executeBlock(block, allPrior, compileResult, requested)
        yield BlockExecution(
          scalaVersion = v,
          compileResult = Some(compileResult),
          executionOutput = execResult.output,
          error = execResult.error
        )

        effect.catchAll(err =>
          ZIO.succeed(
            BlockExecution(
              scalaVersion = v,
              compileResult = None,
              executionOutput = None,
              error = Some(err)
            )
          )
        )
      }
      .map { execs =>
        val first = execs.headOption
        BlockResult(
          block = block,
          compileResult = first.flatMap(_.compileResult),
          executionOutput = first.flatMap(_.executionOutput),
          error = first.flatMap(_.error),
          effectiveScalaVersion = first.map(_.scalaVersion),
          crossExecutions = execs
        )
      }

  private def compileBlock(
      block: CodeBlock,
      priorCode: Vector[String],
      requestedVersion: Option[String],
      topLevelPriorCode: Vector[String] = Vector.empty
  ): IO[MarklitError, CompileResult] =
    val v = requestedVersion
    val loc = Some(block.location)
    def doCompile: IO[MarklitError, CompileResult] =
      compiler.compile(
        block.code,
        priorCode,
        block.isZIOApp,
        v,
        loc,
        block.scopeConfig,
        scopeMode,
        block.isTopLevel,
        topLevelPriorCode
      )
    if block.expectsFailure then
      // For fail blocks, we expect compilation to fail
      doCompile.either
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
      doCompile
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
    else doCompile

  /** Result of execution, including both output and any captured error */
  private case class ExecResult(
      output: Option[String],
      error: Option[MarklitError]
  )

  private def executeBlock(
      block: CodeBlock,
      priorCode: Vector[String],
      compileResult: CompileResult,
      requestedVersion: Option[String],
      topLevelPriorCode: Vector[String] = Vector.empty
  ): IO[MarklitError, ExecResult] =
    val v = requestedVersion
    val dir = compileResult.classFilesDir
    // top-level blocks are compile-only (shouldExecute == false), so we never
    // reach the execute calls for them. A *normal* block that hoists top-level
    // definitions does execute, and must pass the same topLevelPriorCode so the
    // output marker (which hashes it) matches the one baked in at compile time.
    def doExecute =
      compiler.execute(
        block.code,
        priorCode,
        block.isZIOApp,
        v,
        dir,
        block.isTopLevel,
        topLevelPriorCode
      )
    if compileResult.success && block.shouldExecute then
      if block.expectsCrash then
        // For crash blocks, expect runtime exception - capture it for display
        doExecute.either
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
      else doExecute.map(out => ExecResult(Some(out), None))
    else ZIO.succeed(ExecResult(None, None))

object DocumentProcessorLive:
  def layer: URLayer[ScopeManager & CompilerService, DocumentProcessor] =
    ZLayer.fromFunction(DocumentProcessorLive(_, _))
