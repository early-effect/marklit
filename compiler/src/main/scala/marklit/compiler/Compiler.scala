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
    isZIOApp: Boolean = false // Wrap in ZIOAppDefault instead of plain object
):
  def append(code: String): ScopeContext =
    copy(priorCode = priorCode :+ code)

  def allCode: String =
    priorCode.mkString("\n\n")

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

  /** Execute previously compiled code and capture output */
  def execute(
      code: String,
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
        if compileResult.success then execute(code, context)
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
