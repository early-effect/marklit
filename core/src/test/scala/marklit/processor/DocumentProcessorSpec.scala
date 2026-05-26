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
        location: Option[Location],
        scopeConfig: ScopeConfig,
        scopeMode: ScopeMode
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
      test("anonymous blocks do not see each other's code") {
        // README: each block is a fresh scope unless you opt in via id=.
        // Two anon blocks must compile independently; the second block must
        // NOT see the first block's `val x = 1` as prior code.
        val block1 = makeBlock("val x = 1")
        val block2 = makeBlock("val y = 2")
        val compiler = new TestCompiler()

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          _ <- processor.process(Vector(block1, block2))
        yield
          val (_, priorCode) = compiler.compileCalls(1)
          assertTrue(priorCode.isEmpty)
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

    suite("page scope (CLI/plugin opt-in)")(
      test("anonymous blocks share state under page scope") {
        // The opt-in is meant to be equivalent to the user manually writing
        // `id=__page__<v>` on the first block and `extends=__page__<v>,append`
        // on every subsequent block. Verify by compile-call inspection: the
        // second block must see the first block's code as prior.
        val block1 = makeBlock("val x = 1")
        val block2 = makeBlock("println(x)")
        val compiler = new TestCompiler()

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(
            scopeManager,
            compiler,
            scopeMode = ScopeMode.Page
          )
          _ <- processor.process(Vector(block1, block2))
        yield
          val (_, prior2) = compiler.compileCalls(1)
          assertTrue(
            compiler.compileCalls(0)._2.isEmpty,
            prior2 == Vector("val x = 1")
          )
      },

      test("page scope off keeps blocks isolated (regression)") {
        // Sanity check that the default (Isolated) path is unchanged
        // — same fixture as the one above but without the opt-in.
        val block1 = makeBlock("val x = 1")
        val block2 = makeBlock("println(x)")
        val compiler = new TestCompiler()

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          _ <- processor.process(Vector(block1, block2))
        yield
          val (_, prior2) = compiler.compileCalls(1)
          assertTrue(prior2.isEmpty)
      },

      test("explicit id= opts a block out of the page scope") {
        // A user-named scope must keep its own identity — page-scope only
        // rewrites blocks that have NO scope config of their own.
        val block1 = makeBlock("val x = 1")
        val block2 = makeBlock(
          "val y = 2",
          scopeConfig = ScopeConfig(id = Some("mine"))
        )
        val block3 = makeBlock("println(x)")
        val compiler = new TestCompiler()

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(
            scopeManager,
            compiler,
            scopeMode = ScopeMode.Page
          )
          _ <- processor.process(Vector(block1, block2, block3))
        yield
          val (_, prior2) = compiler.compileCalls(1)
          val (_, prior3) = compiler.compileCalls(2)
          assertTrue(
            // Named scope is fresh — does NOT pull in the page-scope's `val x`.
            prior2.isEmpty,
            // Block3 still sees the page scope's `val x`, NOT block2's `val y`.
            prior3 == Vector("val x = 1")
          )
      },

      test("fail / crash / warn / shared blocks bypass the page scope") {
        // These all carry semantics that would be wrong under shared scope:
        // a `fail` block's broken code must not pollute later blocks; a `warn`
        // block's deprecated def must not become reachable from elsewhere; a
        // `shared` block already lives in the per-version default. Verify by
        // confirming none of them affect the next anonymous block's prior
        // code.
        val anon = makeBlock("val first = 1")
        val failBlock = makeBlock(
          "val bad: Int = \"oops\"",
          modifiers = Set(Modifier.Fail)
        )
        val warnBlock = makeBlock(
          "@deprecated(\"x\", \"1\") def d() = (); d()",
          modifiers = Set(Modifier.Warn)
        )
        val tail = makeBlock("println(first)")
        val compiler = new TestCompiler(
          // The fail block needs a non-success result so the assertion logic
          // treats it as expected failure rather than surprise success.
          compileResults = Map(
            "val bad: Int = \"oops\"" -> CompileResult(
              success = false,
              diagnostics = Nil
            )
          )
        )

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(
            scopeManager,
            compiler,
            scopeMode = ScopeMode.Page
          )
          _ <- processor.process(Vector(anon, failBlock, warnBlock, tail))
        yield
          val (_, priorTail) = compiler.compileCalls(3)
          assertTrue(
            // tail sees only the first anon block's code, not fail's or warn's.
            priorTail == Vector("val first = 1")
          )
      },

      test("page-scoped blocks surface executionOutput in BlockResult") {
        // Regression: page-scope blocks were rendering with no output because
        // their executionOutput wasn't propagating to BlockResult. Each
        // anonymous block runs its own `println` and should yield the matching
        // output string.
        val block1 = makeBlock("val items = List(1,2,3)\nprintln(items.size)")
        val block2 = makeBlock("println(items.sum)")
        val compiler = new TestCompiler(
          executeResults = Map(
            "val items = List(1,2,3)\nprintln(items.size)" -> "3\n",
            "println(items.sum)" -> "6\n"
          )
        )

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(
            scopeManager,
            compiler,
            scopeMode = ScopeMode.Page
          )
          result <- processor.process(Vector(block1, block2))
        yield assertTrue(
          result.blockResults.size == 2,
          result.blockResults(0).executionOutput == Some("3\n"),
          result.blockResults(1).executionOutput == Some("6\n")
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
      test("same-version anon blocks do not share state") {
        // Same-version anon blocks each get their own fresh scope (parented
        // to the per-version default for shared-block inheritance only).
        // Two such blocks compile independently — the second must not see
        // the first block's `val a = 1`.
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
            priorB.isEmpty
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
          // The shared block fans out across versionsInUse, so look up calls
          // by code rather than by index.
          val priorShared =
            compiler.compileCalls.filter(_._1 == "import scala.util.Try")
          val priorA =
            compiler.compileCalls.find(_._1 == "Try(1)").map(_._2)
          val priorB =
            compiler.compileCalls.find(_._1 == "Try(2)").map(_._2)
          assertTrue(
            result.isSuccess,
            priorShared.forall(_._2.isEmpty),
            priorA == Some(Vector("import scala.util.Try")),
            priorB == Some(Vector("import scala.util.Try"))
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
          val priorA = compiler.compileCalls.find(_._1 == "Try(1)").map(_._2)
          assertTrue(
            result.isSuccess,
            priorA == Some(Vector("import scala.util.Try"))
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
          _ <- processor.process(Vector(s3, a, b))
        yield
          val priorA =
            compiler.compileCalls.find(_._1 == "val a: X = 1").map(_._2)
          val priorB =
            compiler.compileCalls.find(_._1 == "val b = 2").map(_._2)
          // shared-3 must NOT seed the 2.13 block.
          assertTrue(
            priorA == Some(Vector("type X = Int")),
            priorB == Some(Vector.empty[String])
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
    ),
    suite("scala=shared cross-version fan-out")(
      test(
        "scala=shared block is compiled+executed once per version in use"
      ) {
        val shared = makeBlock(
          "println(List(1,2,3).sum)",
          Set(Modifier.Shared)
        )
        val a = makeBlock(
          "val a = 1",
          scopeConfig = ScopeConfig(scalaVersion = Some("3.7.3"))
        )
        val b = makeBlock(
          "val b = 2",
          scopeConfig = ScopeConfig(scalaVersion = Some("2.13.16"))
        )
        val compiler = new TestCompiler(
          defaultScalaVersion = "3.8.3",
          executeResults = Map("println(List(1,2,3).sum)" -> "6\n")
        )

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(shared, a, b))
        yield
          val sharedResult = result.blockResults.head
          // Fan-out targets every version in use: 3.8.3 (default), 3.7.3, 2.13.16.
          val versions = sharedResult.crossExecutions.map(_.scalaVersion).toSet
          assertTrue(
            result.isSuccess,
            sharedResult.crossExecutions.size == 3,
            versions == Set("3.8.3", "3.7.3", "2.13.16"),
            sharedResult.crossExecutions
              .forall(_.executionOutput == Some("6\n"))
          )
      },
      test(
        "scala=shared-3 fans out only to 3.x versions in use"
      ) {
        val shared3 = makeBlock(
          "println(\"three\")",
          Set(Modifier.SharedMajor("3"))
        )
        val a = makeBlock(
          "val a = 1",
          scopeConfig = ScopeConfig(scalaVersion = Some("3.7.3"))
        )
        val b = makeBlock(
          "val b = 2",
          scopeConfig = ScopeConfig(scalaVersion = Some("2.13.16"))
        )
        val compiler = new TestCompiler(
          defaultScalaVersion = "3.8.3",
          executeResults = Map("println(\"three\")" -> "three\n")
        )

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(shared3, a, b))
        yield
          val sharedResult = result.blockResults.head
          val versions = sharedResult.crossExecutions.map(_.scalaVersion).toSet
          assertTrue(
            result.isSuccess,
            // Only 3.x versions in use: 3.8.3 + 3.7.3.
            sharedResult.crossExecutions.size == 2,
            versions == Set("3.8.3", "3.7.3")
          )
      },
      test(
        "compile failure on one cross version surfaces in DocumentResult.errors"
      ) {
        val shared = makeBlock("bad code", Set(Modifier.Shared))
        val a = makeBlock(
          "val a = 1",
          scopeConfig = ScopeConfig(scalaVersion = Some("2.13.16"))
        )
        // Shared compiles cleanly at 3.x default but fails at 2.13. Other
        // (non-shared) blocks compile cleanly regardless of version.
        val compiler = new TestCompiler(
          defaultScalaVersion = "3.8.3",
          compileResults = Map.empty
        ):
          override def compile(
              code: String,
              priorCode: Vector[String],
              isZIOApp: Boolean,
              scalaVersion: Option[String],
              location: Option[Location],
              scopeConfig: ScopeConfig,
              scopeMode: ScopeMode
          ): IO[MarklitError, CompileResult] =
            compileCalls += ((code, priorCode))
            compileCallsWithVersion += ((code, priorCode, scalaVersion))
            val isTwo = scalaVersion.exists(_.startsWith("2"))
            val sharedFailsHere = code == "bad code" && isTwo
            ZIO.succeed(
              if sharedFailsHere then
                CompileResult(
                  success = false,
                  diagnostics = List(
                    ScalaDiagnostic(
                      DiagnosticSeverity.Error,
                      "boom on 2.13",
                      1,
                      1,
                      None
                    )
                  )
                )
              else CompileResult(success = true, diagnostics = Nil)
            )

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(shared, a))
        yield
          val sharedResult = result.blockResults.head
          val perVersionSuccess = sharedResult.crossExecutions
            .map(x => x.scalaVersion -> x.compileResult.exists(_.success))
            .toMap
          assertTrue(
            !result.isSuccess,
            !sharedResult.isSuccess,
            perVersionSuccess.get("3.8.3").contains(true),
            perVersionSuccess.get("2.13.16").contains(false),
            result.compileErrors.size == 1,
            result.compileErrors.head._2.exists(_.message == "boom on 2.13")
          )
      },
      test(
        "scala=shared with no other versions in doc still fans out to default"
      ) {
        val shared = makeBlock("println(1)", Set(Modifier.Shared))
        val compiler = new TestCompiler(
          defaultScalaVersion = "3.8.3",
          executeResults = Map("println(1)" -> "1\n")
        )

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(shared))
        yield
          val sharedResult = result.blockResults.head
          assertTrue(
            result.isSuccess,
            sharedResult.crossExecutions.size == 1,
            sharedResult.crossExecutions.head.scalaVersion == "3.8.3",
            sharedResult.crossExecutions.head.executionOutput == Some("1\n")
          )
      },
      test(
        "non-shared blocks have empty crossExecutions"
      ) {
        val a = makeBlock("val a = 1")
        val compiler = new TestCompiler(defaultScalaVersion = "3.8.3")

        for
          scopeManager <- ZIO.service[ScopeManager]
          processor = DocumentProcessorLive(scopeManager, compiler)
          result <- processor.process(Vector(a))
        yield assertTrue(
          result.blockResults.head.crossExecutions.isEmpty
        )
      }
    )
  ).provide(ScopeManager.layer) @@ TestAspect.sequential
