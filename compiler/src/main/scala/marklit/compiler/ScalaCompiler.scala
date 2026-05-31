package marklit.compiler

import marklit.compiler.api.{CompileRequest, DotcInvoker, Severity}
import marklit.model.*
import zio.*

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Scala 3 compiler implementation that delegates the actual dotc invocation to
  * a [[DotcInvoker]] living on a per-version classloader. The marklit JVM does
  * not link against `dotty.tools.*` directly — see [[CompilerFactory]] for the
  * classloader-isolation contract.
  *
  * The classpath / scalacOptions / outputDir handling stays Scala-side: the
  * shim takes a [[CompileRequest]] of those values and is itself version-
  * neutral.
  *
  * @param invoker
  *   the version-specific dotc invoker.
  * @param classpath
  *   jars on the *user's* compile classpath (their fullClasspath, plus the
  *   resolved scala3-library/compiler jars from the factory).
  * @param scalacOptions
  *   global options (e.g., from the build).
  * @param outputDir
  *   writable directory where dotc emits .class files and the executor loads
  *   them from.
  * @param scalaVersion
  *   reported version for filtering decisions in
  *   [[marklit.processor.DocumentProcessor]].
  * @param runtimeLoader
  *   classloader used to resolve user-code classes at execution time. When
  *   provided, this is the per-version compiler loader from the factory; when
  *   None we fall back to `getClass.getClassLoader` (legacy in-process path).
  */
final class ScalaCompiler(
    invoker: DotcInvoker,
    classpath: Vector[String],
    scalacOptions: Vector[String],
    outputDir: Path,
    override val scalaVersion: String,
    runtimeLoader: Option[ClassLoader] = None
) extends Compiler:

  /** Whether we're compiling Scala 3 code */
  private val isScala3: Boolean = scalaVersion.startsWith("3")

  override def compile(
      code: String,
      context: ScopeContext
  ): IO[MarklitError, CompileResult] =
    ZIO
      .attempt {
        // Inherited top-level definitions (e.g. an `enum`) are emitted at file
        // scope — above the wrapper for normal blocks, or as the entire unit
        // for a top-level block. Empty for the common case.
        val hoist = context.hoistedCode

        // Per-block output directory. Every compile gets its own subdir so
        // dotc's `MarklitWrapper$.class` from one block doesn't clobber the
        // next, and so the executor can load class files for *this* block
        // without re-running dotc — see [[executeFromDir]].
        val perBlockOut = Files.createTempDirectory(outputDir, "block-")
        val sourceFile = Files.createTempFile(perBlockOut, "marklit_", ".scala")
        try
          val sourceCode =
            if context.topLevel then
              // Top-level blocks compile verbatim as their own compilation
              // unit: no wrapper, and no `print(marker)` (a bare statement is
              // illegal at file scope, and top-level blocks never execute).
              if hoist.isEmpty then code else s"$hoist\n\n$code"
            else
              val marker = context.outputMarker.getOrElse("")

              // For ZIO blocks, we handle markers differently since plain
              // print() happens at effect construction time, not execution.
              val (markerCode, codeWithMarker) =
                if context.isZIOApp && marker.nonEmpty then
                  ("", s"""zio.ZIO.succeed(print("$marker")) *> {\n$code\n}""")
                else
                  val mc =
                    if marker.nonEmpty then s"""print("$marker")\n""" else ""
                  (mc, code)

              val bodyCode =
                if context.priorCode.isEmpty then s"$markerCode$codeWithMarker"
                else s"${context.allCode}\n\n$markerCode$codeWithMarker"

              val wrapped =
                if context.isZIOApp then wrapInZIOApp(bodyCode)
                else wrapInObject(bodyCode)

              // Hoisted top-level definitions go ABOVE the wrapper object so
              // they live at file scope; the executable body stays in `run()`.
              if hoist.isEmpty then wrapped else s"$hoist\n\n$wrapped"

          Files.writeString(sourceFile, sourceCode)

          val effectiveClasspath = (classpath ++ context.classpath).toList
          val effectiveOpts =
            (scalacOptions ++ context.scalacOptions).toList
          val request = new CompileRequest(
            List(sourceFile.toString).asJava,
            effectiveClasspath.asJava,
            perBlockOut.toString,
            effectiveOpts.asJava
          )

          val resp = invoker.compile(request)
          val diagnostics = resp.diagnostics().asScala.toList.map { d =>
            ScalaDiagnostic(
              severity = d.severity() match
                case Severity.ERROR   => DiagnosticSeverity.Error
                case Severity.WARNING => DiagnosticSeverity.Warning
                case Severity.INFO    => DiagnosticSeverity.Info
              ,
              message = d.message(),
              line = d.line(),
              column = d.column(),
              file = Option(d.file())
            )
          }

          CompileResult(
            success = resp.success(),
            diagnostics = diagnostics,
            classFilesDir = if resp.success() then Some(perBlockOut) else None
          )
        finally Files.deleteIfExists(sourceFile)
      }
      .mapError(e =>
        MarklitError.CompileError(
          List(
            ScalaDiagnostic(
              DiagnosticSeverity.Error,
              s"Compilation failed: ${e.getMessage}",
              0,
              0,
              None
            )
          )
        )
      )

  override def execute(
      code: String,
      context: ScopeContext
  ): IO[MarklitError, ExecutionResult] =
    compile(code, context).flatMap { compileResult =>
      if !compileResult.success then
        ZIO.fail(MarklitError.CompileError(compileResult.errors))
      else
        compileResult.classFilesDir match
          case Some(dir) => executeFromDir(dir, context)
          // Defensive: a successful compile *should* carry the dir, but if
          // some adapter strips it (a cached-result that lost the dir, etc.)
          // we still need to run the wrapper. The legacy fallback to the
          // shared outputDir would be unsafe (multi-block clobbering), so we
          // recompile to obtain a fresh dir.
          case None =>
            compile(code, context).flatMap {
              case CompileResult(true, _, Some(d)) =>
                executeFromDir(d, context)
              case other =>
                ZIO.fail(MarklitError.CompileError(other.errors))
            }
    }

  override def executeFromDir(
      classFilesDir: Path,
      context: ScopeContext
  ): IO[MarklitError, ExecutionResult] =
    executeCompiled(classFilesDir, context)

  /** Execute the previously compiled MarklitWrapper class */
  private def executeCompiled(
      classFilesDir: Path,
      context: ScopeContext
  ): IO[MarklitError, ExecutionResult] =
    ZIO
      .attemptBlocking {
        val baos = new ByteArrayOutputStream()
        val ps = new PrintStream(baos, true)
        val oldOut = java.lang.System.out
        val oldErr = java.lang.System.err

        try
          // Redirect BEFORE loading classes so ZIO's Console initializes with
          // our stream.
          java.lang.System.setOut(ps)
          java.lang.System.setErr(ps)

          val urls = (
            Vector(classFilesDir.toUri.toURL) ++
              classpath.map(p => java.nio.file.Paths.get(p).toUri.toURL) ++
              context.classpath.map(p => java.nio.file.Paths.get(p).toUri.toURL)
          ).toArray

          // Parent loader: for true multi-version safety the runtime loader
          // should be the per-version compiler loader (so user code sees the
          // matching scala3-library at runtime). When unset, fall back to the
          // current loader — fine for single-version use.
          val parent = runtimeLoader.getOrElse(getClass.getClassLoader)
          val classLoader =
            if context.isZIOApp then new ChildFirstClassLoader(urls, parent)
            else new java.net.URLClassLoader(urls, parent)

          // Redirect scala.Console on the *user code's* classloader. Each
          // per-version classloader has its own scala.Console$ Module whose
          // `out` was captured from System.out the first time it was touched
          // (most likely during dotc's own compilation, which happened with
          // the original System.out still active). Calling setOut on the
          // marklit-loader's Console (the one in scope here) doesn't affect
          // the user-loader's Console — different Class objects entirely.
          val userConsoleSetOut = withScalaConsoleOut(classLoader, ps)
          val userConsoleSetErr = withScalaConsoleErr(classLoader, ps)

          scala.Console.withOut(ps) {
            scala.Console.withErr(ps) {
              try
                val mainClass = classLoader.loadClass("MarklitWrapper$")
                val instance = mainClass.getField("MODULE$").get(null)
                val runMethod = mainClass.getMethod("run")
                runMethod.invoke(instance)
              finally
                userConsoleSetOut()
                userConsoleSetErr()
            }
          }
          ps.flush()

          val fullOutput = baos.toString
          val newOutput = context.outputMarker match
            case Some(marker) =>
              val markerIdx = fullOutput.indexOf(marker)
              if markerIdx >= 0 then
                fullOutput.substring(markerIdx + marker.length)
              else fullOutput
            case None => fullOutput

          ExecutionResult(
            output = newOutput,
            values = Map.empty
          )
        finally
          java.lang.System.setOut(oldOut)
          java.lang.System.setErr(oldErr)
      }
      .mapError { e =>
        val cause = e match
          case ite: java.lang.reflect.InvocationTargetException =>
            Option(ite.getCause).getOrElse(ite)
          case other => other
        MarklitError.RuntimeError(cause, "")
      }

  /** Wrap code in an object so it can be compiled as a complete compilation
    * unit. For Scala 3, uses indentation-sensitive syntax to avoid breaking
    * user code. For Scala 2, uses brace syntax.
    */
  private def wrapInObject(code: String): String =
    if isScala3 then
      val indentedCode = code.linesIterator
        .map(line => if line.trim.isEmpty then line else "    " + line)
        .mkString("\n")
      s"""object MarklitWrapper:
         |  def run(): Unit =
         |$indentedCode
         |""".stripMargin
    else
      val indentedCode = code.linesIterator
        .map(line => if line.trim.isEmpty then line else "    " + line)
        .mkString("\n")
      s"""object MarklitWrapper {
         |  def run(): Unit = {
         |$indentedCode
         |  }
         |}
         |""".stripMargin

  /** Wrap code as a ZIO effect that runs synchronously. */
  private def wrapInZIOApp(code: String): String =
    if isScala3 then
      val indentedCode = code.linesIterator
        .map(line => if line.trim.isEmpty then line else "      " + line)
        .mkString("\n")
      s"""import zio._
         |
         |object MarklitWrapper:
         |  def run(): Unit =
         |    val effect: ZIO[Any, Any, Any] =
         |$indentedCode
         |
         |    val runtime = Unsafe.unsafe { implicit unsafe =>
         |      Runtime.unsafe.fromLayer(Runtime.removeDefaultLoggers)
         |    }
         |
         |    Unsafe.unsafe { implicit unsafe =>
         |      runtime.unsafe.run(effect).getOrThrowFiberFailure()
         |    }
         |""".stripMargin
    else
      val indentedCode = code.linesIterator
        .map(line => if line.trim.isEmpty then line else "        " + line)
        .mkString("\n")
      s"""import zio._
         |
         |object MarklitWrapper {
         |  def run(): Unit = {
         |    val effect: ZIO[Any, Any, Any] = {
         |$indentedCode
         |    }
         |
         |    val runtime = Unsafe.unsafe { implicit unsafe =>
         |      Runtime.unsafe.fromLayer(Runtime.removeDefaultLoggers)
         |    }
         |
         |    Unsafe.unsafe { implicit unsafe =>
         |      runtime.unsafe.run(effect).getOrThrowFiberFailure()
         |    }
         |  }
         |}
         |""".stripMargin

  /** Reflectively redirect `scala.Console.out` on the user-code classloader.
    *
    * Each per-version classloader has its own `scala.Console$` Module object
    * whose `out` field was captured from `System.out` the first time it was
    * touched (typically during dotc's own compilation, before we redirected
    * stdout). Calling `Console.withOut` on the marklit-loader's Console only
    * affects the marklit-side Console — it's a different Class object from the
    * user-loader's, so `println` from user code still goes to the original
    * stream.
    *
    * Returns a closure that restores the previous out.
    */
  private def withScalaConsoleOut(
      cl: ClassLoader,
      ps: PrintStream
  ): () => Unit =
    redirectScalaConsole(cl, ps, "setOut", "out")

  private def withScalaConsoleErr(
      cl: ClassLoader,
      ps: PrintStream
  ): () => Unit =
    redirectScalaConsole(cl, ps, "setErr", "err")

  private def redirectScalaConsole(
      cl: ClassLoader,
      ps: PrintStream,
      setterName: String,
      getterName: String
  ): () => Unit =
    // Scala 3's Console uses a DynamicVariable internally — there is no public
    // global setter on the Module. We reach the underlying var directly via
    // the private `outVar` / `errVar` fields, which hold a
    // scala.util.DynamicVariable[PrintStream]. Setting `value` on that var
    // changes the *default* PrintStream returned by `Console.out` / `.err`,
    // which is what `Predef.println` etc. consult.
    try
      val consoleCls = Class.forName("scala.Console$", true, cl)
      val module = consoleCls.getField("MODULE$").get(null)
      val varFieldName = if setterName == "setOut" then "outVar" else "errVar"
      val varField = consoleCls.getDeclaredField(varFieldName)
      varField.setAccessible(true)
      val dynVar = varField.get(module)
      val dynVarCls = dynVar.getClass
      val valueGetter = dynVarCls.getMethod("value")
      val valueSetter = dynVarCls.getMethod("value_$eq", classOf[Object])
      val previous = valueGetter.invoke(dynVar)
      valueSetter.invoke(dynVar, ps)
      () =>
        try valueSetter.invoke(dynVar, previous)
        catch case _: Throwable => ()
    catch
      // If the user classloader can't see scala.Console (e.g., a non-Scala
      // runtime path), there's nothing to redirect. Return a no-op.
      case _: ClassNotFoundException => () => ()

object ScalaCompiler:
  /** Scala version returned by the bundled shim. Used as the fallback when no
    * version is otherwise specified.
    */
  def defaultScalaVersion: String =
    CompilerFactory.defaultScalaVersion

/** Child-first classloader that loads classes from URLs before delegating to
  * parent. Ensures libraries like ZIO get fresh initialization in each block.
  */
private class ChildFirstClassLoader(
    urls: Array[java.net.URL],
    parent: ClassLoader
) extends java.net.URLClassLoader(urls, parent):

  override def loadClass(name: String, resolve: Boolean): Class[?] =
    if name.startsWith("java.") ||
      name.startsWith("javax.") ||
      name.startsWith("sun.") ||
      name.startsWith("jdk.") ||
      name.startsWith("scala.") ||
      name.startsWith("dotty.")
    then super.loadClass(name, resolve)
    else
      try
        val c = findLoadedClass(name)
        if c != null then c
        else
          val loaded = findClass(name)
          if resolve then resolveClass(loaded)
          loaded
      catch
        case _: ClassNotFoundException =>
          super.loadClass(name, resolve)
