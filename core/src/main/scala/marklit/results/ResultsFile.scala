package marklit.results

import marklit.model.*
import marklit.processor.*
import zio.json.*

/** Serializable diagnostic */
final case class DiagnosticEntry(
    severity: String,
    message: String,
    line: Int,
    column: Int
)

object DiagnosticEntry:
  given JsonCodec[DiagnosticEntry] = DeriveJsonCodec.gen[DiagnosticEntry]

  def fromDiagnostic(d: ScalaDiagnostic): DiagnosticEntry =
    DiagnosticEntry(
      severity = d.severity match
        case DiagnosticSeverity.Error   => "error"
        case DiagnosticSeverity.Warning => "warning"
        case DiagnosticSeverity.Info    => "info"
      ,
      message = d.message,
      line = d.line,
      column = d.column
    )

/** A serializable result for a single code block */
final case class BlockResultEntry(
    locationKey: String, // "file.md:line:col" - unique identifier
    scalaVersion: String,
    code: String,
    success: Boolean,
    skipped: Boolean,
    executionOutput: Option[String],
    compileErrors: List[DiagnosticEntry],
    runtimeError: Option[String]
)

object BlockResultEntry:
  given JsonCodec[BlockResultEntry] = DeriveJsonCodec.gen[BlockResultEntry]

  def fromBlockResult(br: BlockResult, scalaVersion: String): BlockResultEntry =
    BlockResultEntry(
      locationKey =
        s"${br.block.location.file}:${br.block.location.startLine}:${br.block.location.startColumn}",
      scalaVersion = scalaVersion,
      code = br.block.code,
      success = br.isSuccess,
      skipped = br.skipped,
      executionOutput = br.executionOutput,
      compileErrors = br.compileResult
        .map(_.diagnostics.map(DiagnosticEntry.fromDiagnostic))
        .getOrElse(Nil),
      runtimeError = br.error.map(_.pretty)
    )

/** Results from a single marklit run */
final case class RunResults(
    scalaVersion: String,
    sourceFile: String,
    timestamp: Long,
    blocks: Vector[BlockResultEntry]
):
  /** Get results for non-skipped blocks */
  def processedBlocks: Vector[BlockResultEntry] =
    blocks.filterNot(_.skipped)

object RunResults:
  given JsonCodec[RunResults] = DeriveJsonCodec.gen[RunResults]

  def fromDocumentResult(
      result: DocumentResult,
      scalaVersion: String,
      sourceFile: String
  ): RunResults =
    RunResults(
      scalaVersion = scalaVersion,
      sourceFile = sourceFile,
      timestamp = System.currentTimeMillis(),
      blocks = result.blockResults.map(
        BlockResultEntry.fromBlockResult(_, scalaVersion)
      )
    )

/** Merged results from multiple runs (different Scala versions) */
final case class MergedResults(
    sourceFile: String,
    runs: Vector[RunResults]
):
  /** Get all results for a block, grouped by location key */
  def blocksByLocation: Map[String, Vector[BlockResultEntry]] =
    runs
      .flatMap(_.processedBlocks)
      .groupBy(_.locationKey)

  /** Check if all processed blocks succeeded */
  def isSuccess: Boolean =
    runs.flatMap(_.processedBlocks).forall(_.success)

  /** Get the Scala versions that processed each block */
  def versionsForBlock(locationKey: String): Vector[String] =
    blocksByLocation
      .get(locationKey)
      .map(_.map(_.scalaVersion))
      .getOrElse(Vector.empty)

object MergedResults:
  given JsonCodec[MergedResults] = DeriveJsonCodec.gen[MergedResults]

  /** Merge multiple run results for the same source file */
  def merge(results: Vector[RunResults]): MergedResults =
    require(results.nonEmpty, "Cannot merge empty results")
    val sourceFile = results.head.sourceFile
    require(
      results.forall(_.sourceFile == sourceFile),
      s"All results must be for the same source file, got: ${results.map(_.sourceFile).distinct}"
    )
    MergedResults(sourceFile, results)

  /** Load and merge results from multiple JSON files */
  def fromJsonFiles(jsons: Vector[String]): Either[String, MergedResults] =
    given JsonCodec[RunResults] = RunResults.given_JsonCodec_RunResults
    val parsed = jsons.map(_.fromJson[RunResults])
    val errors = parsed.collect { case Left(e) => e }
    if errors.nonEmpty then
      Left(s"Failed to parse results: ${errors.mkString(", ")}")
    else
      val results = parsed.collect { case Right(r) => r }
      Right(merge(results))
