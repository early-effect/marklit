package marklit.compiler

import marklit.model.*
import zio.*

/** Context for compilation - accumulated definitions from prior blocks in scope
  */
final case class ScopeContext(
    priorCode: Vector[String] = Vector.empty,
    classpath: Vector[String] = Vector.empty,
    scalacOptions: Vector[String] = Vector.empty,
    outputMarker: Option[String] =
      None, // UUID marker to identify where new output starts
    isZIOApp: Boolean = false, // Wrap in ZIOAppDefault instead of plain object
    topLevel: Boolean =
      false, // Compile this block verbatim as its own compilation unit
    topLevelPriorCode: Vector[String] =
      Vector.empty // Inherited definitions hoisted ABOVE the wrapper
):
  def append(code: String): ScopeContext =
    copy(priorCode = priorCode :+ code)

  def allCode: String =
    priorCode.mkString("\n\n")

  /** Inherited top-level definitions, joined, to emit at file scope (above the
    * `MarklitWrapper` object for normal blocks, or as the whole unit for
    * top-level blocks).
    */
  def hoistedCode: String =
    topLevelPriorCode.mkString("\n\n")

object ScopeContext:
  val empty: ScopeContext = ScopeContext()

/** Compiler service - compiles and executes Scala code */
trait Compiler:
  /** The Scala version this compiler uses */
  def scalaVersion: String

  /** Compile a code block within the given scope context */
  def compile(
      code: String,
      context: ScopeContext
  ): IO[MarklitError, CompileResult]

  /** Execute previously compiled code and capture output. Implementations may
    * recompile internally if they don't carry the per-block class files around
    * — prefer [[executeFromDir]] when you already have a
    * [[CompileResult.classFilesDir]] in hand.
    */
  def execute(
      code: String,
      context: ScopeContext
  ): IO[MarklitError, ExecutionResult]

  /** Execute the wrapper class loaded from [[classFilesDir]] directly, without
    * recompiling. The directory must be the one returned by a prior successful
    * [[compile]] (or restored from the cache).
    */
  def executeFromDir(
      classFilesDir: java.nio.file.Path,
      context: ScopeContext
  ): IO[MarklitError, ExecutionResult]

  /** Compile and execute in one step */
  def compileAndExecute(
      code: String,
      context: ScopeContext
  ): IO[MarklitError, (CompileResult, ExecutionResult)] =
    for
      compileResult <- compile(code, context)
      execResult <-
        if compileResult.success then
          compileResult.classFilesDir match
            case Some(d) => executeFromDir(d, context)
            case None    => execute(code, context)
        else ZIO.succeed(ExecutionResult("", Map.empty))
    yield (compileResult, execResult)

object Compiler:
  def compile(
      code: String,
      context: ScopeContext
  ): ZIO[Compiler, MarklitError, CompileResult] =
    ZIO.serviceWithZIO[Compiler](_.compile(code, context))

  def execute(
      code: String,
      context: ScopeContext
  ): ZIO[Compiler, MarklitError, ExecutionResult] =
    ZIO.serviceWithZIO[Compiler](_.execute(code, context))

  def compileAndExecute(
      code: String,
      context: ScopeContext
  ): ZIO[Compiler, MarklitError, (CompileResult, ExecutionResult)] =
    ZIO.serviceWithZIO[Compiler](_.compileAndExecute(code, context))
