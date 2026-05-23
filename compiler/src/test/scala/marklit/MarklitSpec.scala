package marklit

import marklit.model.*
import zio.*
import zio.test.*

object MarklitSpec extends ZIOSpecDefault:

  def spec = suite("Marklit")(
    suite("end-to-end processing")(
      test("processes simple document with single code block") {
        val content =
          """# Example
            |
            |```scala
            |val x = 1 + 1
            |println(x)
            |```
            |""".stripMargin

        for result <- Marklit.processContent(content, "test.md")
        yield assertTrue(
          result.isSuccess,
          result.document.codeBlocks.size == 1,
          result.processingResult.blockResults.size == 1,
          result.processingResult.blockResults.head.executionOutput
            .exists(_.contains("2"))
        )
      },

      test("processes document with multiple blocks in same scope") {
        val content =
          """```scala
            |val base = 10
            |```
            |
            |```scala
            |val doubled = base * 2
            |println(doubled)
            |```
            |""".stripMargin

        for result <- Marklit.processContent(content, "test.md")
        yield assertTrue(
          result.isSuccess,
          result.document.codeBlocks.size == 2,
          result.processingResult
            .blockResults(1)
            .executionOutput
            .exists(_.contains("20"))
        )
      },

      test("processes document with named scopes") {
        val content =
          """```scala marklit:id=scope1
            |val a = 100
            |```
            |
            |```scala marklit:id=scope2
            |val b = 200
            |```
            |
            |```scala marklit:extends=scope1
            |println(a)
            |```
            |""".stripMargin

        for result <- Marklit.processContent(content, "test.md")
        yield assertTrue(
          result.isSuccess,
          result.document.codeBlocks.size == 3,
          // Third block inherits from scope1, should see 'a'
          result.processingResult
            .blockResults(2)
            .executionOutput
            .exists(_.contains("100"))
        )
      },

      test("handles silent modifier") {
        val content =
          """```scala marklit:silent
            |val secret = 42
            |println("should execute but not show")
            |```
            |""".stripMargin

        for result <- Marklit.processContent(content, "test.md")
        yield assertTrue(
          result.isSuccess,
          // Code block has silent modifier
          result.document.codeBlocks.head.modifiers.contains(Modifier.Silent),
          // But it still executes
          result.processingResult.blockResults.head.executionOutput.isDefined
        )
      },

      test("handles compile-only modifier") {
        val content =
          """```scala marklit:compile-only
            |def helper(x: Int): Int = x * 2
            |```
            |""".stripMargin

        for result <- Marklit.processContent(content, "test.md")
        yield assertTrue(
          result.isSuccess,
          // Should compile but not execute
          result.processingResult.blockResults.head.compileResult
            .exists(_.success),
          result.processingResult.blockResults.head.executionOutput.isEmpty
        )
      },

      test("handles fail modifier with expected failure") {
        val content =
          """```scala marklit:fail
            |val x: Int = "not an int"
            |```
            |""".stripMargin

        for result <- Marklit.processContent(content, "test.md")
        yield assertTrue(
          result.isSuccess // fail block that fails = success
        )
      },

      test("reports error when fail block compiles successfully") {
        val content =
          """```scala marklit:fail
            |val x: Int = 42
            |```
            |""".stripMargin

        for result <- Marklit.processContent(content, "test.md")
        yield assertTrue(
          !result.isSuccess // fail block that succeeds = failure
        )
      },

      test("handles passthrough blocks") {
        val content =
          """```python
            |print("hello from python")
            |```
            |""".stripMargin

        for result <- Marklit.processContent(content, "test.md")
        yield assertTrue(
          result.isSuccess,
          result.document.codeBlocks.head.isPassthrough,
          // Passthrough blocks are not compiled
          result.processingResult.blockResults.head.compileResult.isEmpty
        )
      },

      test("handles invisible modifier") {
        val content =
          """```scala marklit:invisible
            |val setupValue = 999
            |```
            |
            |```scala
            |println(setupValue)
            |```
            |""".stripMargin

        for result <- Marklit.processContent(content, "test.md")
        yield assertTrue(
          result.isSuccess,
          result.document.codeBlocks(0).modifiers.contains(Modifier.Invisible),
          !result.document.codeBlocks(0).showCode,
          // Second block can see setupValue
          result.processingResult
            .blockResults(1)
            .executionOutput
            .exists(_.contains("999"))
        )
      },

      test("reports compile errors with location") {
        val content =
          """# Document
            |
            |```scala
            |val x: String = 123
            |```
            |""".stripMargin

        for result <- Marklit.processContent(content, "test.md")
        yield assertTrue(
          !result.isSuccess,
          result.processingResult.compileErrors.nonEmpty
        )
      },

      test("handles warn modifier with expected warnings") {
        val content =
          """```scala marklit:warn
            |@deprecated("use something else", "1.0") def oldMethod(): Unit = ()
            |oldMethod()
            |```
            |""".stripMargin

        for result <- Marklit.processContent(content, "test.md")
        yield assertTrue(
          result.isSuccess // warn block with warnings = success
        )
      },

      test("warn modifier fails when no warnings") {
        val content =
          """```scala marklit:warn
            |val x = 42
            |```
            |""".stripMargin

        for result <- Marklit.processContent(content, "test.md")
        yield assertTrue(
          !result.isSuccess // warn block without warnings = failure
        )
      },

      test("processes real-world example with Scala 3 indentation syntax") {
        val content =
          """# Getting Started with Scala
            |
            |Here's a simple example:
            |
            |```scala
            |case class Person(name: String, age: Int)
            |val alice = Person("Alice", 30)
            |println(s"Hello, ${alice.name}!")
            |```
            |
            |You can also use pattern matching with Scala 3 indentation syntax:
            |
            |```scala
            |alice match
            |  case Person(name, age) if age >= 18 =>
            |    println(s"$name is an adult")
            |  case Person(name, _) =>
            |    println(s"$name is a minor")
            |```
            |""".stripMargin

        for result <- Marklit.processContent(content, "test.md")
        yield assertTrue(
          result.isSuccess,
          result.document.codeBlocks.size == 2,
          result.processingResult
            .blockResults(0)
            .executionOutput
            .exists(_.contains("Hello, Alice!")),
          result.processingResult
            .blockResults(1)
            .executionOutput
            .exists(_.contains("adult"))
        )
      },

      test("handles Scala 3 for-comprehension indentation syntax") {
        val content =
          """```scala
            |val numbers = List(1, 2, 3)
            |val result = for
            |  n <- numbers
            |  if n > 1
            |yield n * 2
            |println(result)
            |```
            |""".stripMargin

        for result <- Marklit.processContent(content, "test.md")
        yield assertTrue(
          result.isSuccess,
          result.processingResult
            .blockResults(0)
            .executionOutput
            .exists(_.contains("List(4, 6)"))
        )
      },

      test("handles Scala 3 if-then-else indentation syntax") {
        val content =
          """```scala
            |val x = 10
            |val msg = if x > 5 then
            |  "big"
            |else
            |  "small"
            |println(msg)
            |```
            |""".stripMargin

        for result <- Marklit.processContent(content, "test.md")
        yield assertTrue(
          result.isSuccess,
          result.processingResult
            .blockResults(0)
            .executionOutput
            .exists(_.contains("big"))
        )
      }
    )
  ).provide(Marklit.layer) @@ TestAspect.sequential @@ TestAspect.timeout(
    60.seconds
  )
