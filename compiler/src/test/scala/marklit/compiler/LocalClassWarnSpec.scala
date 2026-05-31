package marklit.compiler

import marklit.model.*
import zio.*
import zio.test.*

/** Regression coverage for the core value proposition of `top-level`: a
  * parameterized enum case matched against a non-local scrutinee type emits
  * `the type test for X cannot be checked at runtime because it's a local
  * class` when the enum is defined *inside* `def run()` (a local class). When
  * the enum is hoisted to file scope (a real top-level class), the type test is
  * checkable and the warning disappears.
  *
  * This is exactly the warning that forced `show-warnings=false` on the conduit
  * docs; `top-level` lets authors drop that workaround. Verified against the
  * bundled Scala shim.
  */
object LocalClassWarnSpec extends ZIOSpecDefault:

  private def warnings(r: CompileResult): List[String] =
    r.diagnostics
      .filter(_.severity == DiagnosticSeverity.Warning)
      .map(_.message)

  private val localClassWarning =
    "cannot be checked at runtime because it's a local class"

  private val enumDef =
    """enum CounterAction:
      |  case Inc
      |  case Set(v: Int)""".stripMargin

  // Scrutinee static type is the non-local `Any`; matching the parameterized
  // case `CounterAction.Set(v)` needs a runtime type test on the enum class.
  private val matchBody =
    """val a: Any = CounterAction.Set(10)
      |val s = a match
      |  case CounterAction.Set(v) => s"set $v"
      |  case _                    => "other"
      |println(s)""".stripMargin

  def spec = suite("LocalClassWarn")(
    test("wrapped in def run(): the enum is a local class and the match warns") {
      val all = enumDef + "\n" + matchBody
      for result <- Compiler.compile(all, ScopeContext.empty)
      yield assertTrue(
        result.success,
        warnings(result).exists(_.contains(localClassWarning))
      )
    },
    test(
      "enum hoisted as top-level prior code: no local-class warning, and it runs"
    ) {
      for result <- Compiler.compileAndExecute(
          matchBody,
          ScopeContext.empty.copy(topLevelPriorCode = Vector(enumDef))
        )
      yield assertTrue(
        result._1.success,
        !warnings(result._1).exists(_.contains(localClassWarning)),
        result._2.output.contains("set 10")
      )
    }
  ).provideShared(TestCompilerLayer.layer) @@ TestAspect.timeout(
    120.seconds
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential
