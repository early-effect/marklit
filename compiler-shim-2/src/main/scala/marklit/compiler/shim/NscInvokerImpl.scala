package marklit.compiler.shim

import marklit.compiler.api.{
  CompileRequest,
  CompileResponse,
  Diag,
  DotcInvoker,
  Severity
}

import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._
import scala.tools.nsc.reporters.StoreReporter
import scala.tools.nsc.{Global, Settings}

/** The 2.13 sibling of `DotcInvokerImpl`. The ONLY class in marklit that
  * imports `scala.tools.nsc.*`. Compiled into a small jar bundled as a CLI
  * resource and loaded at runtime from a per-version URLClassLoader holding
  * the user-requested `scala-compiler:2.13.x`.
  *
  * Reuses the version-neutral `DotcInvoker` interface — the name is dotc-
  * historical but the surface fits both compilers.
  */
final class NscInvokerImpl extends DotcInvoker {

  override def compilerVersion(): String =
    scala.util.Properties.versionNumberString

  override def compile(request: CompileRequest): CompileResponse = {
    val classpath =
      request.classpath().asScala.mkString(java.io.File.pathSeparator)

    val settings = new Settings()
    settings.classpath.value = classpath
    settings.outdir.value = request.outputDir()
    // Apply user-supplied scalac options (e.g., "-deprecation").
    val opts = request.scalacOptions().asScala.toList
    if (opts.nonEmpty) settings.processArguments(opts, processAll = true)

    val reporter = new StoreReporter(settings)
    val global = new Global(settings, reporter)
    val run = new global.Run()
    run.compile(request.sourceFiles().asScala.toList)

    var hasErrors = false
    val diags = ArrayBuffer.empty[Diag]
    reporter.infos.foreach { info =>
      val severity: Severity = info.severity match {
        case reporter.ERROR =>
          hasErrors = true
          Severity.ERROR
        case reporter.WARNING => Severity.WARNING
        case _                => Severity.INFO
      }
      val pos = info.pos
      val line = if (pos.isDefined) pos.line else 0
      val column = if (pos.isDefined) pos.column else 0
      val file =
        if (pos.isDefined && pos.source != null && pos.source.file != null)
          pos.source.file.path
        else null
      diags += new Diag(severity, info.msg, line, column, file)
    }

    new CompileResponse(!hasErrors, diags.toList.asJava)
  }
}
