package marklit

import marklit.compiler.CompilerFactory
import marklit.model.*
import zio.*
import zio.test.*

object MarklitSpec extends ZIOSpecDefault:

  /** Layer that configures Marklit with `ScopeMode.Page` — the CLI/plugin
    * opt-in that makes anonymous blocks share state per file. The default
    * [[Marklit.layer]] uses `ScopeMode.Isolated`.
    */
  private val pageScopeLayer: ZLayer[Any, Throwable, Marklit] =
    CompilerFactory.layer >>> Marklit.liveWithFactory(
      defaultScalaVersion = CompilerFactory.defaultScalaVersion,
      scopeMode = ScopeMode.Page
    )

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
        // Default (isolated) mode requires explicit id/extends to share —
        // anonymous blocks no longer carry state to each other. This test
        // pins the named-scope sharing path.
        val content =
          """```scala marklit:id=base
            |val base = 10
            |```
            |
            |```scala marklit:extends=base,append
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
      test("output of a block following another block has no marker leak") {
        // Regression: when compile and execute run as separate adapter calls,
        // they must agree on the priorCode-replay marker. A fresh marker on
        // each call would leak the literal "__MARKLIT_<...>__" into the
        // rendered output of every non-first block. Uses named-scope chaining
        // so the second block has non-empty priorCode (the marker only fires
        // when priorCode is non-empty).
        val content =
          """```scala marklit:id=ctx
            |val first = 1
            |println("first")
            |```
            |
            |```scala marklit:extends=ctx,append
            |println(s"second sees $first")
            |```
            |""".stripMargin

        for result <- Marklit.processContent(content, "test.md")
        yield
          val outputs = result.processingResult.blockResults
            .flatMap(_.executionOutput)
          assertTrue(
            result.isSuccess,
            outputs.forall(!_.contains("__MARKLIT_")),
            outputs.exists(_.contains("second sees 1"))
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
          """```scala marklit:invisible,id=setup
            |val setupValue = 999
            |```
            |
            |```scala marklit:extends=setup,append
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
            |```scala marklit:id=alice
            |case class Person(name: String, age: Int)
            |val alice = Person("Alice", 30)
            |println(s"Hello, ${alice.name}!")
            |```
            |
            |You can also use pattern matching with Scala 3 indentation syntax:
            |
            |```scala marklit:extends=alice,append
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
    ).provide(Marklit.layer),
    suite("top-level (real compiler, isolated)")(
      test(
        "top-level block: enum + match renders code-only with NO local-class warning"
      ) {
        // The motivating case. A normal block would warn ("the type test ...
        // cannot be checked at runtime because it's a local class") under the
        // wrapper; a top-level block hoists the enum to file scope, so the
        // warning is gone — and being compile-only, it renders just the code.
        val content =
          """```scala marklit:top-level
            |enum CounterAction:
            |  case Inc
            |  case Set(v: Int)
            |
            |val a: Any = CounterAction.Set(10)
            |val label = a match
            |  case CounterAction.Set(v) => s"set $v"
            |  case _                    => "other"
            |```
            |""".stripMargin
        for
          result <- Marklit.processContent(content, "test.md")
          rendered = marklit.renderer.MarkdownRenderer.render(
            result.document,
            result.processingResult,
            marklit.renderer.RenderConfig.default
          )
        yield
          val br = result.processingResult.blockResults.head
          assertTrue(
            result.isSuccess,
            br.compileResult.exists(_.success),
            // no local-class warning leaked
            br.compileResult.forall(
              !_.warnings.exists(_.message.contains("local class"))
            ),
            // compile-only: no execution output, code preserved in render
            br.executionOutput.isEmpty,
            rendered.contains("enum CounterAction"),
            !rendered.contains("local class")
          )
      },
      test(
        "control: the same enum match WITHOUT top-level warns about a local class"
      ) {
        // Pins WHY the feature exists: identical source, wrapped, warns.
        val content =
          """```scala
            |enum CounterAction:
            |  case Inc
            |  case Set(v: Int)
            |
            |val a: Any = CounterAction.Set(10)
            |val label = a match
            |  case CounterAction.Set(v) => s"set $v"
            |  case _                    => "other"
            |```
            |""".stripMargin
        for result <- Marklit.processContent(content, "test.md")
        yield
          val br = result.processingResult.blockResults.head
          assertTrue(
            br.compileResult.exists(
              _.warnings.exists(_.message.contains("local class"))
            )
          )
      },
      test(
        "top-level definition consumed by a normal executable block: code-only + output"
      ) {
        // enum defined top-level (id=actions), then a normal block matches on
        // it and prints. The normal block compiles WITHOUT the local-class
        // warning (the enum is hoisted) and DOES produce output.
        val content =
          """```scala marklit:top-level,id=actions
            |enum CounterAction:
            |  case Inc
            |  case Set(v: Int)
            |```
            |
            |```scala marklit:extends=actions
            |val a: Any = CounterAction.Set(10)
            |a match
            |  case CounterAction.Set(v) => println(s"set $v")
            |  case _                    => println("other")
            |```
            |""".stripMargin
        for
          result <- Marklit.processContent(content, "test.md")
          rendered = marklit.renderer.MarkdownRenderer.render(
            result.document,
            result.processingResult,
            marklit.renderer.RenderConfig.default
          )
        yield
          val defB = result.processingResult.blockResults(0)
          val useB = result.processingResult.blockResults(1)
          assertTrue(
            result.isSuccess,
            // definition is compile-only
            defB.executionOutput.isEmpty,
            // consumer compiled cleanly (no local-class warning) and ran
            useB.compileResult.exists(_.success),
            useB.compileResult.forall(
              !_.warnings.exists(_.message.contains("local class"))
            ),
            useB.executionOutput.exists(_.contains("set 10")),
            rendered.contains("set 10"),
            !rendered.contains("local class")
          )
      },
      test(
        "top-level combined with a behavioral modifier fails the run"
      ) {
        val content =
          """```scala marklit:top-level,silent
            |opaque type C = Int
            |```
            |""".stripMargin
        for result <- Marklit.processContent(content, "test.md")
        yield assertTrue(
          !result.isSuccess,
          result.processingResult.blockResults.head.error.exists {
            case MarklitError.ValidationError(_, msg) =>
              msg.contains("top-level")
            case _ => false
          }
        )
      },
      test(
        "cross-version: a top-level block pinned to a specific version compiles there"
      ) {
        // opaque type only compiles at the top level; pin it to a specific 3.x
        // version to exercise the version-specific top-level path end-to-end.
        val v = CompilerFactory.defaultScalaVersion
        val content =
          s"""```scala marklit:top-level,scala=$v
             |opaque type Celsius = Double
             |object Celsius:
             |  def apply(d: Double): Celsius = d
             |```
             |""".stripMargin
        for result <- Marklit.processContent(content, "test.md")
        yield
          val br = result.processingResult.blockResults.head
          assertTrue(
            result.isSuccess,
            br.compileResult.exists(_.success),
            br.effectiveScalaVersion == Some(v)
          )
      }
    ).provide(Marklit.layer),
    suite("page scope (real compiler)")(
      test("two anonymous page-scoped blocks both produce executionOutput") {
        // Regression: rendered tour.md showed page-scoped blocks with no
        // output. Each block has its own println; both outputs must reach
        // BlockResult.executionOutput.
        val content =
          """```scala
            |val items = List(1, 2, 3)
            |println(s"first sees ${items.size} items")
            |```
            |
            |```scala
            |println(s"second sees ${items.sum} as the sum")
            |```
            |""".stripMargin

        for result <- Marklit.processContent(content, "test.md")
        yield
          val outputs =
            result.processingResult.blockResults.flatMap(_.executionOutput)
          assertTrue(
            result.isSuccess,
            outputs.exists(_.contains("first sees 3 items")),
            outputs.exists(_.contains("second sees 6 as the sum"))
          )
      },
      test(
        "second page-scoped block introducing a new val produces output"
      ) {
        // Pin: page-scoped block 1 introduces `val totalLetters = ...` —
        // mirroring tour.md exactly. Block 1's output must reach
        // BlockResult.executionOutput.
        val content =
          """```scala
            |val items = List("apples", "pears", "plums")
            |println(s"have ${items.size} kinds of fruit")
            |```
            |
            |```scala
            |val totalLetters = items.map(_.length).sum
            |println(s"$totalLetters letters across ${items.size} items")
            |```
            |""".stripMargin

        for result <- Marklit.processContent(content, "test.md")
        yield
          val outs = result.processingResult.blockResults.map(_.executionOutput)
          assertTrue(
            result.isSuccess,
            outs(0).exists(_.contains("have 3 kinds of fruit")),
            outs(1).exists(_.contains("16 letters across 3 items"))
          )
      },
      test("page-scoped block followed by explicit-id block: both render") {
        // Reproducing the bug seen in examples/sbt/page-docs/target/page-docs/
        // tour.md: pages with a page-scoped block followed by an
        // explicit-id (page-scope-opt-out) block. The opt-out renders, but
        // the page-scoped block above it does not. This test pins down which
        // executionOutputs exist on BlockResult.
        val content =
          """```scala
            |val items = List(1, 2, 3)
            |println(s"have ${items.size} items")
            |```
            |
            |```scala marklit:id=isolated
            |val x = 99
            |println(s"isolated: $x")
            |```
            |
            |```scala
            |println(s"back to page; items = $items")
            |```
            |""".stripMargin

        for result <- Marklit.processContent(content, "test.md")
        yield
          val outs = result.processingResult.blockResults.map(_.executionOutput)
          assertTrue(
            result.isSuccess,
            outs(0).exists(_.contains("have 3 items")),
            outs(1).exists(_.contains("isolated: 99")),
            outs(2).exists(_.contains("back to page; items = List(1, 2, 3)"))
          )
      },
      test(
        "rendered output contains println results for tour.md-style content"
      ) {
        // Pin: this is the exact shape of examples/.../tour.md that is
        // currently rendering with no output. Reproduce it through the
        // renderer that the CLI/plugin uses and assert the println results
        // appear in the rendered markdown.
        val content =
          """## A running tally across blocks
            |
            |```scala
            |val items = List("apples", "pears", "plums")
            |println(s"have ${items.size} kinds of fruit")
            |```
            |
            |```scala
            |val totalLetters = items.map(_.length).sum
            |println(s"$totalLetters letters across ${items.size} items")
            |```
            |
            |```scala marklit:id=independent
            |val isolated = "I do not see items"
            |println(isolated)
            |```
            |
            |```scala
            |println(s"back to the page scope; items = $items")
            |```
            |""".stripMargin

        for
          result <- Marklit.processContent(content, "tour.md")
          rendered = marklit.renderer.MarkdownRenderer.render(
            result.document,
            result.processingResult,
            marklit.renderer.RenderConfig.default
          )
        yield assertTrue(
          result.isSuccess,
          rendered.contains("have 3 kinds of fruit"),
          rendered.contains("16 letters across 3 items"),
          rendered.contains("I do not see items"),
          rendered.contains(
            "back to the page scope; items = List(apples, pears, plums)"
          )
        )
      },
      test(
        "BlockResults for tour.md-style content all have executionOutput"
      ) {
        // Same content as the rendered-output test above, but assert at the
        // BlockResult layer rather than the rendered markdown. If this fails,
        // the bug is in DocumentProcessor / scope resolution. If this passes
        // and the rendered test still fails, the bug is in MarkdownRenderer.
        val content =
          """## A running tally across blocks
            |
            |```scala
            |val items = List("apples", "pears", "plums")
            |println(s"have ${items.size} kinds of fruit")
            |```
            |
            |```scala
            |val totalLetters = items.map(_.length).sum
            |println(s"$totalLetters letters across ${items.size} items")
            |```
            |
            |```scala marklit:id=independent
            |val isolated = "I do not see items"
            |println(isolated)
            |```
            |
            |```scala
            |println(s"back to the page scope; items = $items")
            |```
            |""".stripMargin

        for result <- Marklit.processContent(content, "tour.md")
        yield
          val outs = result.processingResult.blockResults.map(_.executionOutput)
          assertTrue(
            result.isSuccess,
            outs(0).exists(_.contains("have 3 kinds of fruit")),
            outs(1).exists(_.contains("16 letters across 3 items")),
            outs(2).exists(_.contains("I do not see items")),
            outs(3).exists(_.contains("List(apples, pears, plums)"))
          )
      },
      test("page-scoped block does not leak the priorCode marker") {
        // When the second block compiles with priorCode=block1, the marker
        // mechanism must strip the replay output cleanly. A leaked
        // __MARKLIT_<hex>__ in the output would break rendered docs.
        val content =
          """```scala
            |val x = 41
            |```
            |
            |```scala
            |println(s"answer = ${x + 1}")
            |```
            |""".stripMargin

        for result <- Marklit.processContent(content, "test.md")
        yield
          val outputs =
            result.processingResult.blockResults.flatMap(_.executionOutput)
          assertTrue(
            result.isSuccess,
            outputs.forall(!_.contains("__MARKLIT_")),
            outputs.exists(_.contains("answer = 42"))
          )
      },
      test(
        "top-level def + extends consumer composes with --page-scope"
      ) {
        // Under page scope, anonymous blocks share a run-body chain. A
        // top-level definition block opts out (it's hoisted, not run-body), and
        // an explicit `extends=` consumer also opts out of the page chain while
        // still inheriting the hoisted enum. An anonymous page-scoped block
        // sitting between them must not disturb the hoist.
        val content =
          """```scala marklit:top-level,id=actions
            |enum CounterAction:
            |  case Inc
            |  case Set(v: Int)
            |```
            |
            |```scala
            |val pageLocal = "page scope still works"
            |println(pageLocal)
            |```
            |
            |```scala marklit:extends=actions
            |val a: Any = CounterAction.Set(7)
            |a match
            |  case CounterAction.Set(v) => println(s"set $v")
            |  case _                    => println("other")
            |```
            |""".stripMargin
        for
          result <- Marklit.processContent(content, "test.md")
          rendered = marklit.renderer.MarkdownRenderer.render(
            result.document,
            result.processingResult,
            marklit.renderer.RenderConfig.default
          )
        yield
          val useB = result.processingResult.blockResults(2)
          assertTrue(
            result.isSuccess,
            // consumer inherited the hoisted enum, compiled cleanly, and ran
            useB.compileResult.forall(
              !_.warnings.exists(_.message.contains("local class"))
            ),
            useB.executionOutput.exists(_.contains("set 7")),
            // page scope unaffected by the top-level block between them
            rendered.contains("page scope still works"),
            rendered.contains("set 7"),
            !rendered.contains("local class")
          )
      }
    ).provide(pageScopeLayer)
  ) @@ TestAspect.sequential @@ TestAspect.timeout(
    60.seconds
  )
