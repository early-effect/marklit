package marklit.compiler.shim

import coursierapi.{Dependency, Fetch}
import marklit.compiler.api.{CompileRequest, Severity}
import zio.Scope
import zio.test.*

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Integration tests for the shim. The shim is the only place dotty.* is
  * touched, so these tests are the only place we verify a real compilation
  * against a real dotc — every other module mocks at the `Compiler` trait.
  *
  * The shim is exercised here within sbt's test classloader, which has the
  * project's compile-time `scala3-compiler` on it. Cross-version loading
  * happens later, in CompilerFactory's tests.
  */
object DotcInvokerImplSpec extends ZIOSpecDefault:

  /** Resolve scala3-library + scala-library jars via Coursier, against the
    * version the shim was compiled with. This is the same approach the
    * production code (CompilerFactory) takes — keeping the test honest about
    * the same boundary the real classpath construction uses.
    */
  private lazy val scalaLibraryJars: Vector[String] =
    val version = new DotcInvokerImpl().compilerVersion()
    Fetch
      .create()
      .addDependencies(
        Dependency.of("org.scala-lang", "scala3-library_3", version)
      )
      .fetch()
      .asScala
      .toVector
      .map(_.getAbsolutePath)

  /** Allocate a writable temp dir and a `.scala` source file with the given
    * content, then build a CompileRequest pointing at them. Returns the request
    * plus the temp dir so the caller can clean up.
    */
  private def mkRequest(source: String): (CompileRequest, Path) =
    val tmp = Files.createTempDirectory("marklit-shim-test-")
    val srcFile = tmp.resolve("Test.scala")
    Files.writeString(srcFile, source)
    val outDir = Files.createDirectory(tmp.resolve("out"))
    val req = new CompileRequest(
      List(srcFile.toString).asJava,
      scalaLibraryJars.asJava,
      outDir.toString,
      List.empty[String].asJava
    )
    (req, tmp)

  /** Recursively delete a temp tree. */
  private def cleanup(dir: Path): Unit =
    if Files.exists(dir) then
      Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(p => Files.deleteIfExists(p): Unit)

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("DotcInvokerImpl")(
      test("reports a non-empty compiler version") {
        val invoker = new DotcInvokerImpl
        val v = invoker.compilerVersion()
        assertTrue(
          v.nonEmpty,
          // The shim is built against scala3Version in build.sbt, which is on the 3.x line.
          v.startsWith("3.")
        )
      },
      test("compiles a valid Scala 3 file with no errors") {
        val (req, tmp) = mkRequest(
          """object Hello:
            |  def greet(): String = "hi"
            |""".stripMargin
        )
        try
          val invoker = new DotcInvokerImpl
          val resp = invoker.compile(req)
          assertTrue(
            resp.success(),
            !resp.diagnostics().asScala.exists(_.severity() == Severity.ERROR)
          )
        finally cleanup(tmp)
      },
      test("reports a type-mismatch error with line/column/severity") {
        val (req, tmp) = mkRequest(
          // Type error on line 2: assigning Int to String.
          """object Bad:
            |  val x: String = 42
            |""".stripMargin
        )
        try
          val invoker = new DotcInvokerImpl
          val resp = invoker.compile(req)
          val errs =
            resp.diagnostics().asScala.filter(_.severity() == Severity.ERROR)
          assertTrue(
            !resp.success(),
            errs.nonEmpty,
            errs.exists(_.line() == 2),
            errs.exists(d => d.message() != null && d.message().nonEmpty),
            // file path should be the source we wrote (or at least mention "Test.scala").
            errs.exists(d => Option(d.file()).exists(_.endsWith("Test.scala")))
          )
        finally cleanup(tmp)
      },
      test("scalacOptions are honoured (passing -deprecation)") {
        val (reqBase, tmp) = mkRequest(
          """object UsesDeprecated:
            |  @deprecated("old", "1.0") def old(): Int = 1
            |  val n: Int = old()
            |""".stripMargin
        )
        try
          // Re-wrap with -deprecation in scalacOptions.
          val req = new CompileRequest(
            reqBase.sourceFiles(),
            reqBase.classpath(),
            reqBase.outputDir(),
            List("-deprecation").asJava
          )
          val invoker = new DotcInvokerImpl
          val resp = invoker.compile(req)
          val warns =
            resp.diagnostics().asScala.filter(_.severity() == Severity.WARNING)
          assertTrue(
            // Compilation succeeds; only a warning fires.
            resp.success(),
            warns.exists(d =>
              d.message() != null && d
                .message()
                .toLowerCase
                .contains("deprecated")
            )
          )
        finally cleanup(tmp)
      }
    )
