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
    ),

    suite("classFilesDir / executeFromDir")(
      test("compile populates classFilesDir with the wrapper class") {
        for result <- Compiler.compile(
            """val x = 1 + 2""",
            ScopeContext.empty
          )
        yield assertTrue(
          result.success,
          result.classFilesDir.isDefined,
          result.classFilesDir
            .exists(d => java.nio.file.Files.isDirectory(d)),
          result.classFilesDir.exists(d =>
            java.nio.file.Files.isRegularFile(
              d.resolve("MarklitWrapper$.class")
            )
          )
        )
      },

      test("a failed compile carries no classFilesDir") {
        for result <- Compiler.compile(
            "val x: String = 42",
            ScopeContext.empty
          )
        yield assertTrue(
          !result.success,
          result.classFilesDir.isEmpty
        )
      },

      test("executeFromDir reuses a prior compile's class files") {
        val code = """println("from-dir")"""
        for
          compiler <- ZIO.service[Compiler]
          compiled <- compiler.compile(code, ScopeContext.empty)
          dir = compiled.classFilesDir.getOrElse(
            sys.error("expected compile to populate classFilesDir")
          )
          // Snapshot the dir's mtime: executeFromDir must NOT recompile, so
          // mtimes of the existing class files should not move.
          beforeMtime = java.nio.file.Files
            .getLastModifiedTime(dir.resolve("MarklitWrapper$.class"))
            .toMillis
          _ <- Live.live(
            ZIO.sleep(50.millis)
          ) // ensure any rewrite would change mtime
          executed <- compiler.executeFromDir(dir, ScopeContext.empty)
          afterMtime = java.nio.file.Files
            .getLastModifiedTime(dir.resolve("MarklitWrapper$.class"))
            .toMillis
        yield assertTrue(
          executed.output.contains("from-dir"),
          beforeMtime == afterMtime
        )
      }
    )
  ).provideShared(TestCompilerLayer.layer) @@ TestAspect.timeout(
    60.seconds
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential
