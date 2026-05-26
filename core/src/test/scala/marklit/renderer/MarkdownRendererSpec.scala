package marklit.renderer

import marklit.model.*
import marklit.parser.*
import marklit.processor.*
import marklit.results.*
import zio.test.*

object MarkdownRendererSpec extends ZIOSpecDefault:

  val testLocation = Location("test.md", 1, 1)

  def makeBlock(
      code: String,
      modifiers: Set[Modifier] = Set.empty,
      scopeConfig: ScopeConfig = ScopeConfig.empty,
      showWarningsOverride: Option[Boolean] = None
  ): CodeBlock =
    CodeBlock(
      code,
      modifiers,
      scopeConfig,
      testLocation,
      showWarningsOverride
    )

  def makeBlockResult(
      block: CodeBlock,
      success: Boolean = true,
      output: Option[String] = None,
      diagnostics: List[ScalaDiagnostic] = Nil,
      effectiveScalaVersion: Option[String] = None
  ): BlockResult =
    BlockResult(
      block = block,
      compileResult = Some(CompileResult(success, diagnostics)),
      executionOutput = output,
      error = None,
      effectiveScalaVersion = effectiveScalaVersion
    )

  def spec = suite("MarkdownRenderer")(
    suite("basic rendering")(
      test("renders text segments unchanged") {
        val doc = ParsedDocument(
          segments = Vector(
            MarkdownSegment.Text("# Hello World\n\nSome text.", testLocation)
          ),
          sourceFile = "test.md"
        )
        val result = DocumentResult(Vector.empty, java.time.Duration.ZERO)
        val rendered = MarkdownRenderer.render(doc, result)

        assertTrue(rendered == "# Hello World\n\nSome text.")
      },

      test("renders code block without output") {
        val block = makeBlock("val x = 1")
        val doc = ParsedDocument(
          segments = Vector(MarkdownSegment.Code(block)),
          sourceFile = "test.md"
        )
        val blockResult = makeBlockResult(block)
        val result =
          DocumentResult(Vector(blockResult), java.time.Duration.ZERO)

        val rendered = MarkdownRenderer.render(doc, result)

        assertTrue(
          rendered.contains("```scala"),
          rendered.contains("val x = 1"),
          rendered.contains("```")
        )
      },

      test("renders code block with output") {
        val block = makeBlock("println(42)")
        val doc = ParsedDocument(
          segments = Vector(MarkdownSegment.Code(block)),
          sourceFile = "test.md"
        )
        val blockResult = makeBlockResult(block, output = Some("42\n"))
        val result =
          DocumentResult(Vector(blockResult), java.time.Duration.ZERO)

        val rendered = MarkdownRenderer.render(doc, result)

        assertTrue(
          rendered.contains("```scala"),
          rendered.contains("println(42)"),
          rendered.contains("42")
        )
      },

      test("renders output for page-scoped block (extends=...,append)") {
        // Regression: rendered tour.md from `marklitPageScope := true` showed
        // anonymous blocks with no output. After page-scope rewrite, blocks
        // 2..N have `scopeConfig = (extendsScope=__page__<v>, append=true)`;
        // the renderer must still emit their executionOutput.
        val block = makeBlock(
          "println(\"hello\")",
          scopeConfig = ScopeConfig(
            extendsScope = Some("__page__3.8.2"),
            append = true
          )
        )
        val doc = ParsedDocument(
          segments = Vector(MarkdownSegment.Code(block)),
          sourceFile = "test.md"
        )
        val blockResult = makeBlockResult(block, output = Some("hello\n"))
        val result =
          DocumentResult(Vector(blockResult), java.time.Duration.ZERO)

        val rendered = MarkdownRenderer.render(doc, result)

        assertTrue(rendered.contains("hello"))
      },

      test("annotates output block with effective Scala version when present") {
        val block = makeBlock("println(\"hi\")")
        val doc = ParsedDocument(
          segments = Vector(MarkdownSegment.Code(block)),
          sourceFile = "test.md"
        )
        val blockResult = makeBlockResult(
          block,
          output = Some("hi\n"),
          effectiveScalaVersion = Some("3.7.3")
        )
        val result =
          DocumentResult(Vector(blockResult), java.time.Duration.ZERO)

        val rendered = MarkdownRenderer.render(doc, result)

        assertTrue(rendered.contains("// Scala 3.7.3"))
      },

      test("omits version annotation when effective version is None") {
        val block = makeBlock("println(\"hi\")")
        val doc = ParsedDocument(
          segments = Vector(MarkdownSegment.Code(block)),
          sourceFile = "test.md"
        )
        val blockResult = makeBlockResult(block, output = Some("hi\n"))
        val result =
          DocumentResult(Vector(blockResult), java.time.Duration.ZERO)

        val rendered = MarkdownRenderer.render(doc, result)

        assertTrue(!rendered.contains("// Scala"))
      },

      test("preserves text and code ordering") {
        val block = makeBlock("val x = 1")
        val doc = ParsedDocument(
          segments = Vector(
            MarkdownSegment.Text("# Title\n\n", testLocation),
            MarkdownSegment.Code(block),
            MarkdownSegment.Text("\nMore text.", testLocation)
          ),
          sourceFile = "test.md"
        )
        val blockResult = makeBlockResult(block)
        val result =
          DocumentResult(Vector(blockResult), java.time.Duration.ZERO)

        val rendered = MarkdownRenderer.render(doc, result)

        assertTrue(
          rendered.startsWith("# Title"),
          rendered.contains("```scala"),
          rendered.endsWith("More text.")
        )
      }
    ),

    suite("modifier handling")(
      test("invisible blocks are completely hidden") {
        val block = makeBlock("val secret = 42", Set(Modifier.Invisible))
        val doc = ParsedDocument(
          segments = Vector(
            MarkdownSegment.Text("Before\n", testLocation),
            MarkdownSegment.Code(block),
            MarkdownSegment.Text("After", testLocation)
          ),
          sourceFile = "test.md"
        )
        val blockResult = makeBlockResult(block)
        val result =
          DocumentResult(Vector(blockResult), java.time.Duration.ZERO)

        val rendered = MarkdownRenderer.render(doc, result)

        assertTrue(
          rendered == "Before\nAfter",
          !rendered.contains("secret")
        )
      },

      test("silent blocks show code but not output") {
        val block = makeBlock("println(42)", Set(Modifier.Silent))
        val doc = ParsedDocument(
          segments = Vector(MarkdownSegment.Code(block)),
          sourceFile = "test.md"
        )
        val blockResult = makeBlockResult(block, output = Some("42\n"))
        val result =
          DocumentResult(Vector(blockResult), java.time.Duration.ZERO)

        val rendered = MarkdownRenderer.render(doc, result)

        assertTrue(
          rendered.contains("println(42)"),
          !rendered.contains("\n42\n") // output block not shown
        )
      },

      test("passthrough blocks render without scala language tag") {
        val block = makeBlock("not scala", Set(Modifier.Passthrough))
        val doc = ParsedDocument(
          segments = Vector(MarkdownSegment.Code(block)),
          sourceFile = "test.md"
        )
        val result = DocumentResult(
          Vector(BlockResult(block, None, None, None)),
          java.time.Duration.ZERO
        )

        val rendered = MarkdownRenderer.render(doc, result)

        assertTrue(
          rendered.contains("```\n"),
          !rendered.contains("```scala")
        )
      }
    ),

    suite("error rendering")(
      test("renders compile errors") {
        val block = makeBlock("val x: Int = \"bad\"")
        val doc = ParsedDocument(
          segments = Vector(MarkdownSegment.Code(block)),
          sourceFile = "test.md"
        )
        val diagnostic = ScalaDiagnostic(
          DiagnosticSeverity.Error,
          "type mismatch",
          1,
          1,
          None
        )
        val blockResult = makeBlockResult(
          block,
          success = false,
          diagnostics = List(diagnostic)
        )
        val result =
          DocumentResult(Vector(blockResult), java.time.Duration.ZERO)

        val rendered = MarkdownRenderer.render(doc, result)

        assertTrue(
          rendered.contains("val x: Int"),
          rendered.contains("error"),
          rendered.contains("type mismatch")
        )
      }
    ),

    suite("config options")(
      test("showLineNumbers adds line numbers") {
        val block = makeBlock("val x = 1\nval y = 2")
        val doc = ParsedDocument(
          segments = Vector(MarkdownSegment.Code(block)),
          sourceFile = "test.md"
        )
        val blockResult = makeBlockResult(block)
        val result =
          DocumentResult(Vector(blockResult), java.time.Duration.ZERO)
        val config = RenderConfig(showLineNumbers = true)

        val rendered = MarkdownRenderer.render(doc, result, config)

        assertTrue(
          rendered.contains("1 | val x"),
          rendered.contains("2 | val y")
        )
      },

      test("custom errorPrefix is used") {
        val block = makeBlock("bad code")
        val doc = ParsedDocument(
          segments = Vector(MarkdownSegment.Code(block)),
          sourceFile = "test.md"
        )
        val diagnostic =
          ScalaDiagnostic(DiagnosticSeverity.Error, "error msg", 1, 1, None)
        val blockResult = makeBlockResult(
          block,
          success = false,
          diagnostics = List(diagnostic)
        )
        val result =
          DocumentResult(Vector(blockResult), java.time.Duration.ZERO)
        val config = RenderConfig(errorPrefix = "# ")

        val rendered = MarkdownRenderer.render(doc, result, config)

        assertTrue(rendered.contains("# error:"))
      },

      test("outputFenceLanguage sets output block language") {
        val block = makeBlock("println(42)")
        val doc = ParsedDocument(
          segments = Vector(MarkdownSegment.Code(block)),
          sourceFile = "test.md"
        )
        val blockResult = makeBlockResult(block, output = Some("42\n"))
        val result =
          DocumentResult(Vector(blockResult), java.time.Duration.ZERO)
        val config = RenderConfig(outputFenceLanguage = "text")

        val rendered = MarkdownRenderer.render(doc, result, config)

        assertTrue(rendered.contains("```text"))
      }
    ),

    suite("compile warning rendering")(
      test(
        "normal block + warnings + global flag on (default) renders warning before output"
      ) {
        val block = makeBlock("oldMethod()")
        val doc = ParsedDocument(
          segments = Vector(MarkdownSegment.Code(block)),
          sourceFile = "test.md"
        )
        val warning = ScalaDiagnostic(
          DiagnosticSeverity.Warning,
          "method oldMethod is deprecated",
          1,
          1,
          None
        )
        val blockResult = makeBlockResult(
          block,
          output = Some("old\n"),
          diagnostics = List(warning)
        )
        val result =
          DocumentResult(Vector(blockResult), java.time.Duration.ZERO)

        val rendered = MarkdownRenderer.render(doc, result)

        val warningIdx = rendered.indexOf("method oldMethod is deprecated")
        val outputIdx = rendered.indexOf("old\n")
        assertTrue(
          rendered.contains("warning: method oldMethod is deprecated"),
          rendered.contains("old"),
          warningIdx >= 0,
          outputIdx > warningIdx
        )
      },
      test("normal block + warnings + global flag off omits the warning") {
        val block = makeBlock("oldMethod()")
        val doc = ParsedDocument(
          segments = Vector(MarkdownSegment.Code(block)),
          sourceFile = "test.md"
        )
        val warning = ScalaDiagnostic(
          DiagnosticSeverity.Warning,
          "method oldMethod is deprecated",
          1,
          1,
          None
        )
        val blockResult = makeBlockResult(
          block,
          output = Some("old\n"),
          diagnostics = List(warning)
        )
        val result =
          DocumentResult(Vector(blockResult), java.time.Duration.ZERO)
        val config = RenderConfig(showCompileWarnings = false)

        val rendered = MarkdownRenderer.render(doc, result, config)

        assertTrue(
          !rendered.contains("method oldMethod is deprecated"),
          rendered.contains("old")
        )
      },
      test("per-block show-warnings=true overrides global off") {
        val block = makeBlock(
          "oldMethod()",
          showWarningsOverride = Some(true)
        )
        val doc = ParsedDocument(
          segments = Vector(MarkdownSegment.Code(block)),
          sourceFile = "test.md"
        )
        val warning = ScalaDiagnostic(
          DiagnosticSeverity.Warning,
          "method oldMethod is deprecated",
          1,
          1,
          None
        )
        val blockResult = makeBlockResult(
          block,
          output = Some("old\n"),
          diagnostics = List(warning)
        )
        val result =
          DocumentResult(Vector(blockResult), java.time.Duration.ZERO)
        val config = RenderConfig(showCompileWarnings = false)

        val rendered = MarkdownRenderer.render(doc, result, config)

        assertTrue(
          rendered.contains("method oldMethod is deprecated")
        )
      },
      test("per-block show-warnings=false overrides global on") {
        val block = makeBlock(
          "oldMethod()",
          showWarningsOverride = Some(false)
        )
        val doc = ParsedDocument(
          segments = Vector(MarkdownSegment.Code(block)),
          sourceFile = "test.md"
        )
        val warning = ScalaDiagnostic(
          DiagnosticSeverity.Warning,
          "method oldMethod is deprecated",
          1,
          1,
          None
        )
        val blockResult = makeBlockResult(
          block,
          output = Some("old\n"),
          diagnostics = List(warning)
        )
        val result =
          DocumentResult(Vector(blockResult), java.time.Duration.ZERO)

        val rendered = MarkdownRenderer.render(doc, result)

        assertTrue(
          !rendered.contains("method oldMethod is deprecated"),
          rendered.contains("old")
        )
      },
      test("warn modifier renders warnings even when both layers are off") {
        val block = makeBlock(
          "oldMethod()",
          modifiers = Set(Modifier.Warn),
          showWarningsOverride = Some(false)
        )
        val doc = ParsedDocument(
          segments = Vector(MarkdownSegment.Code(block)),
          sourceFile = "test.md"
        )
        val warning = ScalaDiagnostic(
          DiagnosticSeverity.Warning,
          "method oldMethod is deprecated",
          1,
          1,
          None
        )
        val blockResult = makeBlockResult(
          block,
          output = Some("old\n"),
          diagnostics = List(warning)
        )
        val result =
          DocumentResult(Vector(blockResult), java.time.Duration.ZERO)
        val config = RenderConfig(showCompileWarnings = false)

        val rendered = MarkdownRenderer.render(doc, result, config)

        assertTrue(rendered.contains("method oldMethod is deprecated"))
      },
      test("normal block with no warnings renders no warning fence") {
        val block = makeBlock("println(42)")
        val doc = ParsedDocument(
          segments = Vector(MarkdownSegment.Code(block)),
          sourceFile = "test.md"
        )
        val blockResult = makeBlockResult(block, output = Some("42\n"))
        val result =
          DocumentResult(Vector(blockResult), java.time.Duration.ZERO)

        val rendered = MarkdownRenderer.render(doc, result)

        // Expect two fenced regions: the scala code block and the output.
        // Each region opens with ``` and closes with ```. Total = 4 fence
        // markers; an extra warning fence would push it to 6.
        val fenceCount = "```".r.findAllIn(rendered).length
        assertTrue(
          rendered.contains("42"),
          !rendered.contains("warning:"),
          fenceCount == 4
        )
      }
    ),

    suite("merged-path warning rendering")(
      test("renderMerged renders warnings before output by default") {
        val block = makeBlock("oldMethod()")
        val doc = ParsedDocument(
          segments = Vector(MarkdownSegment.Code(block)),
          sourceFile = "test.md"
        )
        val locKey =
          s"test.md:${block.location.startLine}:${block.location.startColumn}"
        val entry = BlockResultEntry(
          locationKey = locKey,
          scalaVersion = "3.7.3",
          code = block.code,
          success = true,
          skipped = false,
          executionOutput = Some("old\n"),
          compileErrors = List(
            DiagnosticEntry(
              severity = "warning",
              message = "method oldMethod is deprecated",
              line = 1,
              column = 1
            )
          ),
          runtimeError = None
        )
        val merged = MergedResults(
          sourceFile = "test.md",
          runs = Vector(
            RunResults(
              scalaVersion = "3.7.3",
              sourceFile = "test.md",
              timestamp = 0L,
              blocks = Vector(entry)
            )
          )
        )

        val rendered = MarkdownRenderer.renderMerged(doc, merged)
        val warningIdx = rendered.indexOf("method oldMethod is deprecated")
        val outputIdx = rendered.indexOf("old\n")
        assertTrue(
          rendered.contains("warning: method oldMethod is deprecated"),
          warningIdx >= 0,
          outputIdx > warningIdx
        )
      },
      test("renderMerged omits warnings when global flag is off") {
        val block = makeBlock("oldMethod()")
        val doc = ParsedDocument(
          segments = Vector(MarkdownSegment.Code(block)),
          sourceFile = "test.md"
        )
        val locKey =
          s"test.md:${block.location.startLine}:${block.location.startColumn}"
        val entry = BlockResultEntry(
          locationKey = locKey,
          scalaVersion = "3.7.3",
          code = block.code,
          success = true,
          skipped = false,
          executionOutput = Some("old\n"),
          compileErrors = List(
            DiagnosticEntry(
              severity = "warning",
              message = "method oldMethod is deprecated",
              line = 1,
              column = 1
            )
          ),
          runtimeError = None
        )
        val merged = MergedResults(
          sourceFile = "test.md",
          runs = Vector(
            RunResults(
              scalaVersion = "3.7.3",
              sourceFile = "test.md",
              timestamp = 0L,
              blocks = Vector(entry)
            )
          )
        )
        val config = RenderConfig(showCompileWarnings = false)

        val rendered = MarkdownRenderer.renderMerged(doc, merged, config)
        assertTrue(
          !rendered.contains("method oldMethod is deprecated"),
          rendered.contains("old")
        )
      },
      test(
        "renderMerged honors per-block show-warnings=true override against global off"
      ) {
        val block = makeBlock(
          "oldMethod()",
          showWarningsOverride = Some(true)
        )
        val doc = ParsedDocument(
          segments = Vector(MarkdownSegment.Code(block)),
          sourceFile = "test.md"
        )
        val locKey =
          s"test.md:${block.location.startLine}:${block.location.startColumn}"
        val entry = BlockResultEntry(
          locationKey = locKey,
          scalaVersion = "3.7.3",
          code = block.code,
          success = true,
          skipped = false,
          executionOutput = Some("old\n"),
          compileErrors = List(
            DiagnosticEntry(
              severity = "warning",
              message = "method oldMethod is deprecated",
              line = 1,
              column = 1
            )
          ),
          runtimeError = None
        )
        val merged = MergedResults(
          sourceFile = "test.md",
          runs = Vector(
            RunResults(
              scalaVersion = "3.7.3",
              sourceFile = "test.md",
              timestamp = 0L,
              blocks = Vector(entry)
            )
          )
        )
        val config = RenderConfig(showCompileWarnings = false)

        val rendered = MarkdownRenderer.renderMerged(doc, merged, config)
        assertTrue(rendered.contains("method oldMethod is deprecated"))
      }
    )
  )
