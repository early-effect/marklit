package marklit.processor

import marklit.model.*
import marklit.scope.*
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

  def compile(
      code: String,
      priorCode: Vector[String],
      isZIOApp: Boolean = false,
      scalaVersion: Option[String] = None
  ): IO[MarklitError, CompileResult]

  def execute(
      code: String,
      priorCode: Vector[String],
      isZIOApp: Boolean = false,
      scalaVersion: Option[String] = None
  ): IO[MarklitError, String]

/** Live implementation that processes blocks sequentially, respecting scope
  * dependencies
  */
final class DocumentProcessorLive(
    scopeManager: ScopeManager,
    compiler: CompilerService
) extends DocumentProcessor:

  private val scalaVersion: String = compiler.defaultScalaVersion

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
          b.requestedSpecificScalaVersion.getOrElse(scalaVersion)
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

    seedAllShared *>
      ZIO.foreach(blocks)(processBlock).map { results =>
        val endTime = java.time.Instant.now()
        DocumentResult(results, java.time.Duration.between(startTime, endTime))
      }

  private def processBlock(block: CodeBlock): IO[MarklitError, BlockResult] =
    // The version this block actually compiled against. Per-block specific
    // versions win; otherwise we fall back to the service's default version.
    val effectiveVersion: String =
      block.requestedSpecificScalaVersion.getOrElse(scalaVersion)

    // Handle passthrough blocks - no processing
    if block.isPassthrough then
      ZIO.succeed(BlockResult(block, None, None, None))
    // Skip blocks that don't match the current Scala version
    else if !block.isCompatibleWith(scalaVersion) then
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
        compileResult <- compileBlock(block, allPriorCode)

        // Execute if compilation succeeded and block should execute
        execResult <- executeBlock(block, allPriorCode, compileResult)

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
      priorCode: Vector[String]
  ): IO[MarklitError, CompileResult] =
    val v = block.requestedSpecificScalaVersion
    if block.expectsFailure then
      // For fail blocks, we expect compilation to fail
      compiler.compile(block.code, priorCode, block.isZIOApp, v).either.map {
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
      compiler.compile(block.code, priorCode, block.isZIOApp, v).map { cr =>
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
    else compiler.compile(block.code, priorCode, block.isZIOApp, v)

  /** Result of execution, including both output and any captured error */
  private case class ExecResult(
      output: Option[String],
      error: Option[MarklitError]
  )

  private def executeBlock(
      block: CodeBlock,
      priorCode: Vector[String],
      compileResult: CompileResult
  ): IO[MarklitError, ExecResult] =
    val v = block.requestedSpecificScalaVersion
    if compileResult.success && block.shouldExecute then
      if block.expectsCrash then
        // For crash blocks, expect runtime exception - capture it for display
        compiler.execute(block.code, priorCode, block.isZIOApp, v).either.map {
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
          .execute(block.code, priorCode, block.isZIOApp, v)
          .map(out => ExecResult(Some(out), None))
    else ZIO.succeed(ExecResult(None, None))

object DocumentProcessorLive:
  def layer: URLayer[ScopeManager & CompilerService, DocumentProcessor] =
    ZLayer.fromFunction(DocumentProcessorLive(_, _))
