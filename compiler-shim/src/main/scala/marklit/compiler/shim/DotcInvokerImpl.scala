package marklit.compiler.shim

import dotty.tools.dotc.Driver
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.reporting.{Diagnostic, Reporter}
import marklit.compiler.api.{
  CompileRequest,
  CompileResponse,
  Diag,
  DotcInvoker,
  Severity
}

import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

/** The shim implementation. This is the ONLY class in marklit that imports
  * `dotty.tools.dotc.*`. It is compiled into a small jar that travels with the
  * CLI as a resource, and at runtime it is loaded from a custom URLClassLoader
  * containing the user-requested `scala3-compiler` version.
  *
  * The class must be public and have a no-arg constructor so the orchestrator
  * can instantiate it via
  * `Class.forName(...).getDeclaredConstructor().newInstance()`.
  */
final class DotcInvokerImpl extends DotcInvoker:

  override def compilerVersion(): String =
    dotty.tools.dotc.config.Properties.versionNumberString

  override def compile(request: CompileRequest): CompileResponse =
    val classpath =
      request.classpath().asScala.mkString(java.io.File.pathSeparator)

    val args = (
      Vector(
        "-d",
        request.outputDir(),
        "-classpath",
        classpath
      ) ++ request.scalacOptions().asScala ++ request.sourceFiles().asScala
    ).toArray

    val reporter = new CollectingReporter
    val driver = new Driver:
      override def newCompiler(using Context): dotty.tools.dotc.Compiler =
        new dotty.tools.dotc.Compiler

    val _ = driver.process(args, reporter)

    val diags = reporter.diagnostics
      .map { d =>
        new Diag(d.severity, d.message, d.line, d.column, d.file.orNull)
      }
      .toList
      .asJava

    new CompileResponse(!reporter.hasErrors, diags)

end DotcInvokerImpl

/** Diagnostic record local to the shim. Kept Scala-native for ergonomic
  * collection here; we convert to the version-neutral `Diag` POJO at the
  * boundary in `compile`.
  */
private final case class ShimDiag(
    severity: Severity,
    message: String,
    line: Int,
    column: Int,
    file: Option[String]
)

/** Reporter that collects every diagnostic dotc emits during compilation.
  *
  * Lives in the shim because it extends a dotc class — the JVM resolves
  * `Reporter` (and `Diagnostic`, `Context`) through this class's defining
  * classloader, which contains the per-version `scala3-compiler` jar.
  */
private final class CollectingReporter extends Reporter:
  private val _diagnostics = ArrayBuffer.empty[ShimDiag]
  private var _hasErrors = false

  def diagnostics: List[ShimDiag] = _diagnostics.toList
  override def hasErrors: Boolean = _hasErrors

  override def doReport(dia: Diagnostic)(using Context): Unit =
    val severity = dia.level match
      case dotty.tools.dotc.interfaces.Diagnostic.ERROR =>
        _hasErrors = true
        Severity.ERROR
      case dotty.tools.dotc.interfaces.Diagnostic.WARNING =>
        Severity.WARNING
      case _ =>
        Severity.INFO

    val pos = dia.pos
    val line = if pos.exists then pos.line + 1 else 0
    val column = if pos.exists then pos.column + 1 else 0
    val file =
      if pos.exists && pos.source.file != null then Some(pos.source.file.path)
      else None

    _diagnostics += ShimDiag(severity, dia.message, line, column, file)
