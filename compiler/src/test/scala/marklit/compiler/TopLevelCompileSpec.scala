package marklit.compiler

import marklit.model.*
import zio.*
import zio.test.*

/** Compiler-level coverage for the `top-level` feature: blocks that compile
  * verbatim as their own compilation unit (no `MarklitWrapper`), and the
  * hoisting of inherited top-level definitions above the wrapper for normal
  * blocks that depend on them.
  */
object TopLevelCompileSpec extends ZIOSpecDefault:

  // An `opaque type` definition. This is a genuine motivating construct: it is
  // a top-level-only modifier and is *rejected* inside a `def` body, so it can
  // only be demonstrated at the top level. (Verified empirically against the
  // bundled Scala 3.3.7 shim — see the project memory note on which constructs
  // actually diverge between the wrapper and file scope.)
  private val opaqueSource =
    """opaque type Celsius = Double
      |object Celsius:
      |  def apply(d: Double): Celsius = d
      |  extension (c: Celsius) def value: Double = c
      |""".stripMargin

  def spec = suite("ScalaCompiler top-level")(
    suite("positive")(
      test("opaque type compiles cleanly as a top-level unit") {
        val ctx = ScopeContext.empty.copy(topLevel = true)
        for result <- Compiler.compile(opaqueSource, ctx)
        yield assertTrue(
          result.success,
          result.errors.isEmpty
        )
      },
      test(
        "the same opaque type fails when wrapped in a def body (proves the bypass earns its keep)"
      ) {
        // Control: the non-top-level path wraps code in `def run()`, where an
        // `opaque type` modifier is not allowed. This pins *why* top-level
        // exists — without the wrapper bypass this source cannot compile.
        val ctx = ScopeContext.empty // topLevel = false
        for result <- Compiler.compile(opaqueSource, ctx)
        yield assertTrue(!result.success)
      },
      test(
        "a normal block hoists an inherited opaque type and executes against it"
      ) {
        // The motivating end-to-end shape at the compiler seam: the opaque type
        // is hoisted ABOVE the wrapper; the executable body uses it in run().
        val ctx = ScopeContext.empty.copy(
          topLevelPriorCode = Vector(opaqueSource)
        )
        val body = """val t = Celsius(20.0)
                     |println(s"temp = ${t.value}")""".stripMargin
        for result <- Compiler.compileAndExecute(body, ctx)
        yield assertTrue(
          result._1.success,
          result._2.output.contains("temp = 20.0")
        )
      }
    ),
    suite("negative")(
      test("malformed top-level source surfaces a real compile error") {
        val ctx = ScopeContext.empty.copy(topLevel = true)
        for result <- Compiler.compile("opaque type Broken =", ctx)
        yield assertTrue(
          !result.success,
          result.errors.nonEmpty
        )
      },
      test(
        "an outputMarker set on a top-level context is NOT injected at file scope"
      ) {
        // If `print(marker)` were emitted ahead of the code, it would land at
        // file scope (illegal) and fail to compile. A clean compile proves the
        // marker was correctly omitted for top-level blocks.
        val ctx = ScopeContext.empty.copy(
          topLevel = true,
          outputMarker = Some("__MARKLIT_MARKER__")
        )
        for result <- Compiler.compile(opaqueSource, ctx)
        yield assertTrue(result.success)
      }
    ),
    suite("pathological")(
      test("comment-only top-level code compiles") {
        val ctx = ScopeContext.empty.copy(topLevel = true)
        for result <- Compiler.compile("// just a comment", ctx)
        yield assertTrue(result.success)
      },
      test("hoisted code with CRLF and trailing whitespace still compiles") {
        val enumDef =
          "enum Color:\r\n  case Red, Green, Blue   \r\n"
        val ctx = ScopeContext.empty.copy(
          topLevelPriorCode = Vector(enumDef)
        )
        for result <- Compiler.compile("val c: Color = Color.Green", ctx)
        yield assertTrue(result.success)
      },
      test(
        "multiple hoisted top-level fragments are all visible to the body"
      ) {
        val colorDef = "enum Color:\n  case Red, Green, Blue"
        val sizeDef = "enum Size:\n  case Small, Large"
        val ctx = ScopeContext.empty.copy(
          topLevelPriorCode = Vector(colorDef, sizeDef)
        )
        val body =
          """val c: Color = Color.Red
            |val s: Size = Size.Small
            |println(s"$c $s")""".stripMargin
        for result <- Compiler.compileAndExecute(body, ctx)
        yield assertTrue(
          result._1.success,
          result._2.output.contains("Red Small")
        )
      }
    )
  ).provideShared(TestCompilerLayer.layer) @@ TestAspect.timeout(
    120.seconds
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential
