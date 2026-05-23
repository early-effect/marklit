package marklit.compiler

import marklit.model.*
import zio.*
import zio.test.*

object ScalaCompilerSpec extends ZIOSpecDefault:

  def spec = suite("ScalaCompiler")(
    suite("compile")(
      test("compiles simple expression") {
        for result <- Compiler.compile("val x = 1 + 1", ScopeContext.empty)
        yield assertTrue(
          result.success,
          result.errors.isEmpty
        )
      },

      test("compiles with println") {
        for result <- Compiler.compile(
            """println("hello")""",
            ScopeContext.empty
          )
        yield assertTrue(result.success)
      },

      test("reports compilation errors") {
        for result <- Compiler.compile("val x: String = 42", ScopeContext.empty)
        yield assertTrue(
          !result.success,
          result.errors.nonEmpty,
          result.errors.exists(
            _.message.contains("mismatch") || result.errors.exists(
              _.message.contains("Int")
            )
          )
        )
      },

      test("compiles code using standard library") {
        val code = """
          |val xs = List(1, 2, 3)
          |val doubled = xs.map(_ * 2)
          |""".stripMargin
        for result <- Compiler.compile(code, ScopeContext.empty)
        yield assertTrue(result.success)
      },

      test("compiles with scope context from prior code") {
        val priorCode = "val x = 10"
        val newCode = "val y = x + 5"
        val context = ScopeContext.empty.append(priorCode)
        for result <- Compiler.compile(newCode, context)
        yield assertTrue(result.success)
      },

      test("fails when referencing undefined variable") {
        val code = "val y = undefinedVar + 5"
        for result <- Compiler.compile(code, ScopeContext.empty)
        yield assertTrue(
          !result.success,
          result.errors.nonEmpty
        )
      }
    ),

    suite("execute")(
      test("captures println output") {
        val code = """println("hello world")"""
        for result <- Compiler.execute(code, ScopeContext.empty)
        yield assertTrue(
          result.output.contains("hello world")
        )
      },

      test("executes multiple statements") {
        val code = """
          |val x = 1
          |val y = 2
          |println(s"sum = ${x + y}")
          |""".stripMargin
        for result <- Compiler.execute(code, ScopeContext.empty)
        yield assertTrue(
          result.output.contains("sum = 3")
        )
      }
    )
  ).provideShared(TestCompilerLayer.layer) @@ TestAspect.timeout(
    60.seconds
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential
