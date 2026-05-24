package marklit.processor

import marklit.model.*
import marklit.scope.*
import zio.*
import zio.test.*

object DocumentProcessorSpec extends ZIOSpecDefault:

  val testLocation = Location("test.md", 1, 1)

  /** Test compiler that tracks calls and returns configurable results.
    *
    * `defaultScalaVersion` is what the processor sees as the runtime default
    * (used by bare-major filtering). Per-block specific-version requests (e.g.
    * `scala=3.7.0`) are recorded in [[compileCallsWithVersion]] so tests can
    * assert that the right version was demanded.
    */
  class TestCompiler(
      compileResults: Map[String, CompileResult] = Map.empty,
      executeResults: Map[String, String] = Map.empty,
      failExecution: Set[String] = Set.empty,
      override val defaultScalaVersion: String = "3.3.7",
      majorDefaults: Map[String, String] = Map.empty
  ) extends CompilerService:
    override def defaultVersionForMajor(major: String): Option[String] =
      majorDefaults
        .get(major)
        .orElse(super.defaultVersionForMajor(major))

    val compileCalls =
      scala.collection.mutable.ArrayBuffer.empty[(String, Vector[String])]
    val compileCallsWithVersion =
      scala.collection.mutable.ArrayBuffer
        .empty[(String, Vector[String], Option[String])]
    val executeCalls =
      scala.collection.mutable.ArrayBuffer.empty[(String, Vector[String])]
    val executeCallsWithVersion =
      scala.collection.mutable.ArrayBuffer
        .empty[(String, Vector[String], Option[String])]
    val executeCallsWithDir =
      scala.collection.mutable.ArrayBuffer
        .empty[(String, Option[java.nio.file.Path])]

    override def compile(
        code: String,
        priorCode: Vector[String],
        isZIOApp: Boolean,
        scalaVersion: Option[String],
        location: Option[Location]
    ): IO[MarklitError, CompileResult] =
      compileCalls += ((code, priorCode))
      compileCallsWithVersion += ((code, priorCode, scalaVersion))
      ZIO.succeed(
        compileResults.getOrElse(
          code,
          CompileResult(success = true, diagnostics = Nil)
        )
      )

    override def execute(
        code: String,
        priorCode: Vector[String],
        isZIOApp: Boolean,
        scalaVersion: Option[String],
        classFilesDir: Option[java.nio.file.Path]
    ): IO[MarklitError, String] =
      executeCalls += ((code, priorCode))
      executeCallsWithVersion += ((code, priorCode, scalaVersion))
      executeCallsWithDir += ((code, classFilesDir))
      if failExecution.contains(code) then
        ZIO.fail(
          MarklitError
            .RuntimeError(new RuntimeException("crash"), "crash output")
        )
      else ZIO.succeed(executeResults.getOrElse(code, ""))

  def makeBlock(
      code: String,
      modifiers: Set[Modifier] = Set.empty,
      scopeConfig: ScopeConfig = ScopeConfig.empty
  ): CodeBlock =
    CodeBlock(code, modifiers, scopeConfig, testLocation)

  def spec = suite("DocumentProcessor")(
    suite("basic processing")(
      test("processes single block") {
        val block = makeBlock("println(1)")
        val compiler =
          new TestCompiler(executeResults = Map("println(1)" -> "1\n"))

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(block))
        yield assertTrue(
          result.isSuccess,
          result.blockResults.size == 1,
          result.blockResults.head.executionOutput == Some("1\n"),
          compiler.compileCalls.size == 1,
          compiler.executeCalls.size == 1
        )
      },

      test(
        "executeBlock receives the classFilesDir from the prior compileBlock"
      ) {
        // Phase B.2 contract: DocumentProcessor must thread the
        // classFilesDir from the compile result into the subsequent execute
        // call, so the CompilerService can skip a redundant recompile.
        val fakeDir = java.nio.file.Paths.get("/tmp/marklit-fake-block-xyz")
        val block = makeBlock("println(\"x\")")
        val compiler = new TestCompiler(
          compileResults = Map(
            "println(\"x\")" -> CompileResult(
              success = true,
              diagnostics = Nil,
              classFilesDir = Some(fakeDir)
            )
          ),
          executeResults = Map("println(\"x\")" -> "x\n")
        )

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          _ <- processor.process(Vector(block))
        yield assertTrue(
          compiler.executeCallsWithDir.size == 1,
          compiler.executeCallsWithDir.head._2.contains(fakeDir)
        )
      },

      test(
        "executeBlock receives None when compile produced no classFilesDir"
      ) {
        // The synthesized branches in compileBlock (expectsFailure /
        // expectsWarnings stand-ins) don't carry a dir — and that's fine,
        // because those blocks don't reach executeBlock anyway. But for
        // ordinary blocks where the compiler returns no dir (older mocks,
        // adapters that strip it), the processor must still pass through
        // None rather than crash.
        val block = makeBlock("println(0)")
        val compiler = new TestCompiler(
          compileResults = Map(
            "println(0)" -> CompileResult(success = true, diagnostics = Nil)
          ),
          executeResults = Map("println(0)" -> "0\n")
        )

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          _ <- processor.process(Vector(block))
        yield assertTrue(
          compiler.executeCallsWithDir.size == 1,
          compiler.executeCallsWithDir.head._2.isEmpty
        )
      },

      test(
        "BlockResult.effectiveScalaVersion is the default when block has no version override"
      ) {
        val block = makeBlock("println(1)")
        val compiler = new TestCompiler(
          executeResults = Map("println(1)" -> "1\n"),
          defaultScalaVersion = "3.8.3"
        )

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(block))
        yield assertTrue(
          result.blockResults.head.effectiveScalaVersion == Some("3.8.3")
        )
      },

      test(
        "BlockResult.effectiveScalaVersion is the per-block override when present"
      ) {
        val block = makeBlock(
          "println(1)",
          scopeConfig = ScopeConfig(scalaVersion = Some("3.7.3"))
        )
        val compiler = new TestCompiler(
          executeResults = Map("println(1)" -> "1\n"),
          defaultScalaVersion = "3.8.3"
        )

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(block))
        yield assertTrue(
          result.blockResults.head.effectiveScalaVersion == Some("3.7.3")
        )
      },

      test("processes multiple blocks in order") {
        val block1 = makeBlock("val x = 1")
        val block2 = makeBlock("val y = 2")
        val compiler = new TestCompiler()

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(block1, block2))
        yield assertTrue(
          result.isSuccess,
          result.blockResults.size == 2
        )
      },

      test("passthrough blocks are not compiled") {
        val block = makeBlock("not scala code", Set(Modifier.Passthrough))
        val compiler = new TestCompiler()

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(block))
        yield assertTrue(
          result.isSuccess,
          result.blockResults.head.compileResult.isEmpty,
          compiler.compileCalls.isEmpty,
          compiler.executeCalls.isEmpty
        )
      }
    ),

    suite("modifier handling")(
      test("silent blocks compile and execute (output hidden via showOutput)") {
        val block = makeBlock("println(1)", Set(Modifier.Silent))
        val compiler =
          new TestCompiler(executeResults = Map("println(1)" -> "1\n"))

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(block))
        yield assertTrue(
          result.isSuccess,
          result.blockResults.head.compileResult.isDefined,
          // Silent blocks DO execute, showOutput determines rendering
          compiler.executeCalls.nonEmpty,
          !block.showOutput // Silent means don't show output in rendered doc
        )
      },

      test("compile-only blocks don't execute") {
        val block = makeBlock("val x = 1", Set(Modifier.CompileOnly))
        val compiler = new TestCompiler()

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(block))
        yield assertTrue(
          result.isSuccess,
          result.blockResults.head.executionOutput.isEmpty,
          compiler.executeCalls.isEmpty
        )
      },

      test("fail blocks expect compilation failure") {
        val block = makeBlock("invalid syntax", Set(Modifier.Fail))
        val failResult = CompileResult(
          success = false,
          diagnostics =
            List(ScalaDiagnostic(DiagnosticSeverity.Error, "error", 1, 1, None))
        )
        val compiler =
          new TestCompiler(compileResults = Map("invalid syntax" -> failResult))

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(block))
        yield assertTrue(
          result.isSuccess, // fail block with compilation failure = success
          result.blockResults.head.compileResult.exists(_.success)
        )
      },

      test("fail block reports error when compilation succeeds") {
        val block = makeBlock("val x = 1", Set(Modifier.Fail))
        val compiler = new TestCompiler() // Default returns success

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(block))
        yield assertTrue(
          !result.isSuccess, // fail block with compilation success = failure
          result.blockResults.head.compileResult.exists(!_.success)
        )
      },

      test("fail block code is not recorded in scope") {
        val failBlock = makeBlock("val broken: String = 42", Set(Modifier.Fail))
        val nextBlock = makeBlock("val y = 1")
        val failResult = CompileResult(
          success = false,
          diagnostics = List(
            ScalaDiagnostic(
              DiagnosticSeverity.Error,
              "type mismatch",
              1,
              1,
              None
            )
          )
        )
        val compiler = new TestCompiler(compileResults =
          Map("val broken: String = 42" -> failResult)
        )

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(failBlock, nextBlock))
        yield
          // The second block should NOT have the fail block's broken code
          val (_, priorCode) = compiler.compileCalls(1)
          assertTrue(
            result.isSuccess, // Overall should succeed (fail block worked, next block worked)
            priorCode.isEmpty // No prior code from fail block
          )
      },

      test("crash blocks expect runtime exception") {
        val block = makeBlock("throw new Exception", Set(Modifier.Crash))
        val compiler =
          new TestCompiler(failExecution = Set("throw new Exception"))

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(block))
        yield assertTrue(
          result.isSuccess,
          result.blockResults.head.executionOutput.isDefined
        )
      },

      test("warn blocks expect compilation warnings") {
        val block = makeBlock("@deprecated val x = 1", Set(Modifier.Warn))
        val warnResult = CompileResult(
          success = true,
          diagnostics = List(
            ScalaDiagnostic(
              DiagnosticSeverity.Warning,
              "deprecated",
              1,
              1,
              None
            )
          )
        )
        val compiler = new TestCompiler(compileResults =
          Map("@deprecated val x = 1" -> warnResult)
        )

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(block))
        yield assertTrue(
          result.isSuccess, // warn block with warnings = success
          result.blockResults.head.compileResult.exists(_.success)
        )
      },

      test("warn block reports error when no warnings") {
        val block = makeBlock("val x = 1", Set(Modifier.Warn))
        val compiler =
          new TestCompiler() // Default returns success with no diagnostics

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(block))
        yield assertTrue(
          !result.isSuccess, // warn block without warnings = failure
          result.blockResults.head.compileResult.exists(!_.success)
        )
      }
    ),

    suite("scope integration")(
      test("blocks in same scope accumulate code") {
        val block1 = makeBlock("val x = 1")
        val block2 = makeBlock("val y = x + 1")
        val compiler = new TestCompiler()

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          _ <- processor.process(Vector(block1, block2))
        yield
          // Second block should have first block's code as prior
          val (_, priorCode) = compiler.compileCalls(1)
          assertTrue(priorCode == Vector("val x = 1"))
      },

      test("named scope isolates code") {
        val block1 =
          makeBlock("val a = 1", scopeConfig = ScopeConfig(id = Some("scope1")))
        val block2 =
          makeBlock("val b = 2", scopeConfig = ScopeConfig(id = Some("scope2")))
        val compiler = new TestCompiler()

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          _ <- processor.process(Vector(block1, block2))
        yield
          // Second block in different scope should have no prior code
          val (_, priorCode) = compiler.compileCalls(1)
          assertTrue(priorCode.isEmpty)
      },

      test("child scope inherits parent code") {
        val parent = makeBlock(
          "val base = 10",
          scopeConfig = ScopeConfig(id = Some("parent"))
        )
        val child = makeBlock(
          "val derived = base * 2",
          scopeConfig = ScopeConfig(
            id = Some("child"),
            extendsScope = Some("parent")
          )
        )
        val compiler = new TestCompiler()

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          _ <- processor.process(Vector(parent, child))
        yield
          // Child should inherit parent's code
          val (_, priorCode) = compiler.compileCalls(1)
          assertTrue(priorCode == Vector("val base = 10"))
      },

      test("append mutates parent scope and accumulates code") {
        val block1 = makeBlock(
          "var items = List(\"a\")",
          scopeConfig = ScopeConfig(id = Some("growing"))
        )
        val block2 = makeBlock(
          "items = items :+ \"b\"",
          scopeConfig = ScopeConfig(
            extendsScope = Some("growing"),
            append = true
          )
        )
        val block3 = makeBlock(
          "items = items :+ \"c\"",
          scopeConfig = ScopeConfig(
            extendsScope = Some("growing"),
            append = true
          )
        )
        val compiler = new TestCompiler()

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(block1, block2, block3))
        yield
          // All blocks should succeed
          assertTrue(
            result.isSuccess,
            result.blockResults.forall(_.isSuccess),
            // Second block should have first block's code
            compiler.compileCalls(1)._2 == Vector("var items = List(\"a\")"),
            // Third block should have first two blocks' code
            compiler.compileCalls(2)._2 == Vector(
              "var items = List(\"a\")",
              "items = items :+ \"b\""
            )
          )
      }
    ),

    suite("error handling")(
      test("compilation error is captured in result") {
        val block = makeBlock("val x: Int = \"string\"")
        val errorResult = CompileResult(
          success = false,
          diagnostics = List(
            ScalaDiagnostic(
              DiagnosticSeverity.Error,
              "type mismatch",
              1,
              1,
              None
            )
          )
        )
        val compiler = new TestCompiler(compileResults =
          Map("val x: Int = \"string\"" -> errorResult)
        )

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(block))
        yield assertTrue(
          !result.isSuccess,
          result.compileErrors.nonEmpty
        )
      }
    ),

    suite("scala version selection")(
      test("blocks without version are processed under default") {
        val block = makeBlock("val x = 1")
        val compiler = new TestCompiler(defaultScalaVersion = "3.3.7")

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(block))
        yield assertTrue(
          result.isSuccess,
          !result.blockResults.head.skipped,
          compiler.compileCalls.size == 1,
          compiler.compileCallsWithVersion.head._3.isEmpty
        )
      },

      test("bare-major scala=3 block runs on a 3.x default (no skip)") {
        val block = makeBlock(
          "enum Foo",
          scopeConfig = ScopeConfig(scalaVersion = Some("3"))
        )
        val compiler = new TestCompiler(defaultScalaVersion = "3.3.7")

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(block))
        yield assertTrue(
          result.isSuccess,
          !result.blockResults.head.skipped,
          compiler.compileCalls.size == 1,
          // Bare-major doesn't request a specific version on the call.
          compiler.compileCallsWithVersion.head._3.isEmpty
        )
      },

      test(
        "bare-major scala=2 on a 3.x default auto-resolves to a 2.13 default"
      ) {
        // CompilerService advertises a per-major default for 2 → "2.13.16".
        val block = makeBlock(
          "val x = 1",
          scopeConfig = ScopeConfig(scalaVersion = Some("2"))
        )
        val compiler = new TestCompiler(
          defaultScalaVersion = "3.3.7",
          majorDefaults = Map("2" -> "2.13.16", "3" -> "3.3.7")
        )

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(block))
        yield assertTrue(
          result.isSuccess,
          !result.blockResults.head.skipped,
          compiler.compileCallsWithVersion.head._3 == Some("2.13.16"),
          result.blockResults.head.effectiveScalaVersion == Some("2.13.16")
        )
      },

      test(
        "bare-major scala=3 on a 2.x default auto-resolves to a 3.x default"
      ) {
        val block = makeBlock(
          "enum Foo",
          scopeConfig = ScopeConfig(scalaVersion = Some("3"))
        )
        val compiler = new TestCompiler(
          defaultScalaVersion = "2.13.12",
          majorDefaults = Map("2" -> "2.13.12", "3" -> "3.8.3")
        )

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(block))
        yield assertTrue(
          result.isSuccess,
          !result.blockResults.head.skipped,
          compiler.compileCallsWithVersion.head._3 == Some("3.8.3"),
          result.blockResults.head.effectiveScalaVersion == Some("3.8.3")
        )
      },

      test("bare-major with no default for that major is skipped") {
        // No mapping for major "2" — should fall back to skip.
        val block = makeBlock(
          "val x = 1",
          scopeConfig = ScopeConfig(scalaVersion = Some("2"))
        )
        val compiler = new TestCompiler(
          defaultScalaVersion = "3.3.7",
          majorDefaults = Map("3" -> "3.3.7")
        )

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(block))
        yield assertTrue(
          result.isSuccess,
          result.blockResults.head.skipped,
          compiler.compileCalls.isEmpty
        )
      },

      test("specific version request is forwarded to the compiler service") {
        val block = makeBlock(
          "val x = 1",
          scopeConfig = ScopeConfig(scalaVersion = Some("3.7.0"))
        )
        val compiler = new TestCompiler(defaultScalaVersion = "3.3.7")

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(block))
        yield assertTrue(
          result.isSuccess,
          !result.blockResults.head.skipped,
          compiler.compileCalls.size == 1,
          compiler.compileCallsWithVersion.head._3 == Some("3.7.0"),
          compiler.executeCallsWithVersion.head._3 == Some("3.7.0")
        )
      },

      test("specific minor (e.g. 3.3) is also a specific-version request") {
        val block = makeBlock(
          "val x = 1",
          scopeConfig = ScopeConfig(scalaVersion = Some("3.3"))
        )
        val compiler = new TestCompiler(defaultScalaVersion = "3.3.7")

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(block))
        yield assertTrue(
          result.isSuccess,
          !result.blockResults.head.skipped,
          compiler.compileCallsWithVersion.head._3 == Some("3.3")
        )
      },

      test("skipped blocks don't affect overall success") {
        val block1 = makeBlock("val x = 1") // universal
        val block2 = makeBlock(
          "scala 2 only",
          scopeConfig = ScopeConfig(scalaVersion = Some("2"))
        )
        // No default for major 2 — block2 is skipped.
        val compiler = new TestCompiler(
          defaultScalaVersion = "3.3.7",
          majorDefaults = Map("3" -> "3.3.7")
        )

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(block1, block2))
        yield assertTrue(
          result.isSuccess,
          !result.blockResults(0).skipped,
          result.blockResults(1).skipped
        )
      }
    ),
    suite("per-version default scopes")(
      test(
        "blocks of different specific versions don't share a default scope"
      ) {
        val a = makeBlock(
          "val v = 1",
          scopeConfig = ScopeConfig(scalaVersion = Some("3.3.7"))
        )
        val b = makeBlock(
          "val v = 2",
          scopeConfig = ScopeConfig(scalaVersion = Some("3.7.3"))
        )
        val compiler = new TestCompiler(defaultScalaVersion = "3.8.3")

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(a, b))
        yield
          // Each block compiled with empty priorCode — neither sees the other.
          val priorA = compiler.compileCalls(0)._2
          val priorB = compiler.compileCalls(1)._2
          assertTrue(
            result.isSuccess,
            priorA.isEmpty,
            priorB.isEmpty
          )
      },
      test("same-version blocks accumulate in their version's default scope") {
        val a = makeBlock(
          "val a = 1",
          scopeConfig = ScopeConfig(scalaVersion = Some("3.7.3"))
        )
        val b = makeBlock(
          "val b = 2",
          scopeConfig = ScopeConfig(scalaVersion = Some("3.7.3"))
        )
        val compiler = new TestCompiler(defaultScalaVersion = "3.8.3")

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(a, b))
        yield
          val priorB = compiler.compileCalls(1)._2
          assertTrue(
            result.isSuccess,
            priorB == Vector("val a = 1")
          )
      }
    ),
    suite("shared blocks")(
      test(
        "shared block code is prepended to every per-version default scope"
      ) {
        val shared = makeBlock("import scala.util.Try", Set(Modifier.Shared))
        val a = makeBlock(
          "Try(1)",
          scopeConfig = ScopeConfig(scalaVersion = Some("3.3.7"))
        )
        val b = makeBlock(
          "Try(2)",
          scopeConfig = ScopeConfig(scalaVersion = Some("3.7.3"))
        )
        val compiler = new TestCompiler(defaultScalaVersion = "3.8.3")

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(shared, a, b))
        yield
          // The shared block is itself compiled with no prior code.
          val priorShared = compiler.compileCalls(0)._2
          val priorA = compiler.compileCalls(1)._2
          val priorB = compiler.compileCalls(2)._2
          assertTrue(
            result.isSuccess,
            priorShared.isEmpty,
            priorA == Vector("import scala.util.Try"),
            priorB == Vector("import scala.util.Try")
          )
      },
      test(
        "shared block prepends even when it appears after a regular block"
      ) {
        val a = makeBlock(
          "Try(1)",
          scopeConfig = ScopeConfig(scalaVersion = Some("3.7.3"))
        )
        val shared = makeBlock("import scala.util.Try", Set(Modifier.Shared))
        val compiler = new TestCompiler(defaultScalaVersion = "3.8.3")

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(a, shared))
        yield
          // "as if prepended at document's start": even though `a` comes
          // before `shared` in source order, `a` should see the shared
          // import in its priorCode.
          val priorA = compiler.compileCalls(0)._2
          assertTrue(
            result.isSuccess,
            priorA == Vector("import scala.util.Try")
          )
      },
      test("shared-{mv} only contributes to default scopes of that major") {
        val s3 = makeBlock("type X = Int", Set(Modifier.SharedMajor("3")))
        val a = makeBlock(
          "val a: X = 1",
          scopeConfig = ScopeConfig(scalaVersion = Some("3.7.3"))
        )
        val b = makeBlock(
          "val b = 2",
          scopeConfig = ScopeConfig(scalaVersion = Some("2.13.16"))
        )
        val compiler = new TestCompiler(defaultScalaVersion = "3.8.3")

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(s3, a, b))
        yield
          val priorA = compiler.compileCalls(1)._2
          // The 2.13 block is filtered out (bare-major mismatch w/ default
          // 3.8.3) — but even if it weren't, shared-3 must NOT seed it.
          assertTrue(
            priorA == Vector("type X = Int")
          )
      },
      test(
        "shared block is not duplicated in its own version's priorCode"
      ) {
        val shared = makeBlock("val s = 1", Set(Modifier.Shared))
        val next = makeBlock(
          "val n = s + 1",
          scopeConfig = ScopeConfig(scalaVersion = Some("3.8.3"))
        )
        val compiler = new TestCompiler(defaultScalaVersion = "3.8.3")

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(shared, next))
        yield
          // The shared block should not see itself in its priorCode (it was
          // pre-seeded but stripped before compile).
          val priorShared = compiler.compileCalls(0)._2
          val priorNext = compiler.compileCalls(1)._2
          assertTrue(
            result.isSuccess,
            priorShared.isEmpty,
            // The next block under the same version sees the shared once.
            priorNext == Vector("val s = 1")
          )
      }
    )
  ).provide(ScopeManager.layer) @@ TestAspect.sequential
