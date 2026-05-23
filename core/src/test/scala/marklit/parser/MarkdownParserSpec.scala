package marklit.parser

import marklit.model.*
import zio.*
import zio.test.*

object MarkdownParserSpec extends ZIOSpecDefault:

  def spec = suite("MarkdownParser")(
    suite("basic parsing")(
      test("parses empty document") {
        for doc <- MarkdownParser.parse("", "test.md")
        yield assertTrue(
          doc.segments.isEmpty,
          doc.codeBlocks.isEmpty
        )
      },

      test("parses text-only document") {
        val content = "# Hello World\n\nSome text here."
        for doc <- MarkdownParser.parse(content, "test.md")
        yield assertTrue(
          doc.segments.size == 1,
          doc.codeBlocks.isEmpty
        )
      },

      test("parses single scala code block") {
        val content =
          """# Example
            |
            |```scala
            |val x = 1
            |```
            |""".stripMargin
        for doc <- MarkdownParser.parse(content, "test.md")
        yield assertTrue(
          doc.codeBlocks.size == 1,
          doc.codeBlocks.head.code == "val x = 1"
        )
      },

      test("parses code block with tilde fence") {
        val content =
          """~~~scala
            |val y = 2
            |~~~
            |""".stripMargin
        for doc <- MarkdownParser.parse(content, "test.md")
        yield assertTrue(
          doc.codeBlocks.size == 1,
          doc.codeBlocks.head.code == "val y = 2"
        )
      },

      test("parses multiple code blocks") {
        val content =
          """```scala
            |val a = 1
            |```
            |
            |Some text
            |
            |```scala
            |val b = 2
            |```
            |""".stripMargin
        for doc <- MarkdownParser.parse(content, "test.md")
        yield assertTrue(
          doc.codeBlocks.size == 2,
          doc.codeBlocks(0).code == "val a = 1",
          doc.codeBlocks(1).code == "val b = 2"
        )
      },

      test("preserves multiline code") {
        val content =
          """```scala
            |val x = 1
            |val y = 2
            |val z = x + y
            |```
            |""".stripMargin
        for doc <- MarkdownParser.parse(content, "test.md")
        yield assertTrue(
          doc.codeBlocks.head.code == "val x = 1\nval y = 2\nval z = x + y"
        )
      }
    ),

    suite("non-scala blocks")(
      test("marks non-scala blocks as passthrough") {
        val content =
          """```python
            |print("hello")
            |```
            |""".stripMargin
        for doc <- MarkdownParser.parse(content, "test.md")
        yield assertTrue(
          doc.codeBlocks.size == 1,
          doc.codeBlocks.head.isPassthrough
        )
      },

      test("marks blocks without language as passthrough") {
        val content =
          """```
            |some text
            |```
            |""".stripMargin
        for doc <- MarkdownParser.parse(content, "test.md")
        yield assertTrue(
          doc.codeBlocks.size == 1,
          doc.codeBlocks.head.isPassthrough
        )
      }
    ),

    suite("modifier parsing")(
      test("parses marklit-style silent modifier") {
        val content =
          """```scala marklit:silent
            |val x = 1
            |```
            |""".stripMargin
        for doc <- MarkdownParser.parse(content, "test.md")
        yield assertTrue(
          doc.codeBlocks.head.modifiers.contains(Modifier.Silent)
        )
      },

      test("parses multiple modifiers") {
        val content =
          """```scala marklit:silent,compile-only
            |val x = 1
            |```
            |""".stripMargin
        for doc <- MarkdownParser.parse(content, "test.md")
        yield assertTrue(
          doc.codeBlocks.head.modifiers.contains(Modifier.Silent),
          doc.codeBlocks.head.modifiers.contains(Modifier.CompileOnly)
        )
      },

      test("parses fail modifier") {
        val content =
          """```scala marklit:fail
            |invalid code
            |```
            |""".stripMargin
        for doc <- MarkdownParser.parse(content, "test.md")
        yield assertTrue(
          doc.codeBlocks.head.expectsFailure
        )
      },

      test("parses crash modifier") {
        val content =
          """```scala marklit:crash
            |throw new Exception
            |```
            |""".stripMargin
        for doc <- MarkdownParser.parse(content, "test.md")
        yield assertTrue(
          doc.codeBlocks.head.expectsCrash
        )
      },

      test("parses invisible modifier") {
        val content =
          """```scala marklit:invisible
            |val setup = true
            |```
            |""".stripMargin
        for doc <- MarkdownParser.parse(content, "test.md")
        yield assertTrue(
          doc.codeBlocks.head.modifiers.contains(Modifier.Invisible),
          !doc.codeBlocks.head.showCode,
          !doc.codeBlocks.head.showOutput
        )
      },

      test("parses passthrough modifier") {
        val content =
          """```scala marklit:passthrough
            |// This is shown as-is
            |```
            |""".stripMargin
        for doc <- MarkdownParser.parse(content, "test.md")
        yield assertTrue(
          doc.codeBlocks.head.isPassthrough
        )
      }
    ),

    suite("scope config parsing")(
      test("parses id= scope config") {
        val content =
          """```scala marklit:id=myScope
            |val x = 1
            |```
            |""".stripMargin
        for doc <- MarkdownParser.parse(content, "test.md")
        yield assertTrue(
          doc.codeBlocks.head.scopeConfig.id == Some("myScope")
        )
      },

      test("parses extends= scope config") {
        val content =
          """```scala marklit:extends=parent
            |val y = x + 1
            |```
            |""".stripMargin
        for doc <- MarkdownParser.parse(content, "test.md")
        yield assertTrue(
          doc.codeBlocks.head.scopeConfig.extendsScope == Some("parent")
        )
      },

      test("parses append with extends") {
        val content =
          """```scala marklit:extends=base,append
            |val z = 3
            |```
            |""".stripMargin
        for doc <- MarkdownParser.parse(content, "test.md")
        yield assertTrue(
          doc.codeBlocks.head.scopeConfig.extendsScope == Some("base"),
          doc.codeBlocks.head.scopeConfig.append
        )
      },

      test("parses scala version config") {
        val content =
          """```scala marklit:scala=2.13
            |val x = 1
            |```
            |""".stripMargin
        for doc <- MarkdownParser.parse(content, "test.md")
        yield assertTrue(
          doc.codeBlocks.head.scopeConfig.scalaVersion == Some("2.13")
        )
      },

      test("parses combined modifiers and scope config") {
        val content =
          """```scala marklit:silent,id=setup,scala=3
            |val base = 100
            |```
            |""".stripMargin
        for doc <- MarkdownParser.parse(content, "test.md")
        yield assertTrue(
          doc.codeBlocks.head.modifiers.contains(Modifier.Silent),
          doc.codeBlocks.head.scopeConfig.id == Some("setup"),
          doc.codeBlocks.head.scopeConfig.scalaVersion == Some("3")
        )
      }
    ),

    suite("location tracking")(
      test("tracks code block line numbers") {
        val content =
          """# Title
            |
            |Some intro text.
            |
            |```scala
            |val x = 1
            |```
            |""".stripMargin
        for doc <- MarkdownParser.parse(content, "test.md")
        yield assertTrue(
          doc.codeBlocks.head.location.file == "test.md",
          doc.codeBlocks.head.location.startLine == 5
        )
      }
    ),

    suite("edge cases")(
      test("handles code block at start of file") {
        val content =
          """```scala
            |val x = 1
            |```
            |""".stripMargin
        for doc <- MarkdownParser.parse(content, "test.md")
        yield assertTrue(
          doc.codeBlocks.size == 1,
          doc.codeBlocks.head.location.startLine == 1
        )
      },

      test("handles code block at end of file without trailing newline") {
        val content = "```scala\nval x = 1\n```"
        for doc <- MarkdownParser.parse(content, "test.md")
        yield assertTrue(
          doc.codeBlocks.size == 1
        )
      },

      test("handles empty code block") {
        val content =
          """```scala
            |```
            |""".stripMargin
        for doc <- MarkdownParser.parse(content, "test.md")
        yield assertTrue(
          doc.codeBlocks.size == 1,
          doc.codeBlocks.head.code == ""
        )
      },

      test("handles indented code fence") {
        val content =
          """  ```scala
            |  val x = 1
            |  ```
            |""".stripMargin
        for doc <- MarkdownParser.parse(content, "test.md")
        yield assertTrue(
          doc.codeBlocks.size == 1
        )
      }
    )
  )
