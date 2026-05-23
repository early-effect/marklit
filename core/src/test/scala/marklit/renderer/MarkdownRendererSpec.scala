package marklit.renderer

import marklit.model.*
import marklit.parser.*
import marklit.processor.*
import zio.test.*

object MarkdownRendererSpec extends ZIOSpecDefault:

  val testLocation = Location("test.md", 1, 1)

  def makeBlock(
      code: String,
      modifiers: Set[Modifier] = Set.empty,
      scopeConfig: ScopeConfig = ScopeConfig.empty
  ): CodeBlock =
    CodeBlock(code, modifiers, scopeConfig, testLocation)

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
    )
  )
