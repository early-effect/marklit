package marklit.compiler

import marklit.model.*
import zio.*
import zio.test.*

object OutputIsolationSpec extends ZIOSpecDefault:

  def spec = suite("Output Isolation")(
    suite("sequential blocks")(
      test("first block shows its output") {
        for
          compiler <- ZIO.service[Compiler]
          result <- compiler.execute(
            """println("Hello")""",
            ScopeContext.empty
          )
        yield assertTrue(result.output.trim == "Hello")
      },

      test("second block only shows new output, not prior output") {
        for
          compiler <- ZIO.service[Compiler]
          // First block
          _ <- compiler.execute(
            """println("First")""",
            ScopeContext.empty
          )
          // Second block with prior code
          result <- compiler.execute(
            """println("Second")""",
            ScopeContext(
              priorCode = Vector("""println("First")"""),
              outputMarker = Some("__MARKER__")
            )
          )
        yield assertTrue(
          result.output.trim == "Second",
          !result.output.contains("First")
        )
      },

      test("third block only shows its output") {
        for
          compiler <- ZIO.service[Compiler]
          result <- compiler.execute(
            """println("Third")""",
            ScopeContext(
              priorCode = Vector(
                """println("First")""",
                """println("Second")"""
              ),
              outputMarker = Some("__MARKER__")
            )
          )
        yield assertTrue(
          result.output.trim == "Third",
          !result.output.contains("First"),
          !result.output.contains("Second")
        )
      }
    ),

    suite("definitions and values")(
      test("definitions from prior blocks are available") {
        for
          compiler <- ZIO.service[Compiler]
          result <- compiler.execute(
            """println(x * 2)""",
            ScopeContext(
              priorCode = Vector("val x = 21"),
              outputMarker = Some("__MARKER__")
            )
          )
        yield assertTrue(result.output.trim == "42")
      },

      test("classes from prior blocks are available") {
        for
          compiler <- ZIO.service[Compiler]
          result <- compiler.execute(
            """println(Person("Alice", 30).name)""",
            ScopeContext(
              priorCode = Vector("case class Person(name: String, age: Int)"),
              outputMarker = Some("__MARKER__")
            )
          )
        yield assertTrue(result.output.trim == "Alice")
      },

      test("inner objects work correctly") {
        for
          compiler <- ZIO.service[Compiler]
          result <- compiler.execute(
            """println(Config.value)""",
            ScopeContext(
              priorCode = Vector(
                """object Config:
                  |  val value = "configured"
                  |""".stripMargin
              ),
              outputMarker = Some("__MARKER__")
            )
          )
        yield assertTrue(result.output.trim == "configured")
      },

      test("inner classes with methods work") {
        for
          compiler <- ZIO.service[Compiler]
          result <- compiler.execute(
            """
              |val calc = new Calculator
              |println(calc.add(2, 3))
            """.stripMargin,
            ScopeContext(
              priorCode = Vector(
                """class Calculator:
                  |  def add(a: Int, b: Int): Int = a + b
                  |""".stripMargin
              ),
              outputMarker = Some("__MARKER__")
            )
          )
        yield assertTrue(result.output.trim == "5")
      }
    ),

    suite("edge cases")(
      test("empty output block") {
        for
          compiler <- ZIO.service[Compiler]
          result <- compiler.execute(
            """val x = 1""", // No println
            ScopeContext(
              priorCode = Vector("""println("Prior")"""),
              outputMarker = Some("__MARKER__")
            )
          )
        yield assertTrue(result.output.trim.isEmpty)
      },

      test("multi-line output") {
        for
          compiler <- ZIO.service[Compiler]
          result <- compiler.execute(
            """
              |println("Line 1")
              |println("Line 2")
              |println("Line 3")
            """.stripMargin,
            ScopeContext(
              priorCode = Vector("""println("Prior")"""),
              outputMarker = Some("__MARKER__")
            )
          )
        yield assertTrue(
          result.output.contains("Line 1"),
          result.output.contains("Line 2"),
          result.output.contains("Line 3"),
          !result.output.contains("Prior")
        )
      },

      test("output containing special characters") {
        for
          compiler <- ZIO.service[Compiler]
          result <- compiler.execute(
            """println("Special: $test \"quotes\" \\backslash")""",
            ScopeContext(
              priorCode = Vector("""println("Prior output")"""),
              outputMarker = Some("__MARKER__")
            )
          )
        yield assertTrue(
          result.output.contains("Special:"),
          result.output.contains("quotes"),
          !result.output.contains("Prior")
        )
      },

      test("enums work correctly (Scala 3)") {
        for
          compiler <- ZIO.service[Compiler]
          result <- compiler.execute(
            """println(Color.Red)""",
            ScopeContext(
              priorCode = Vector(
                """enum Color:
                  |  case Red, Green, Blue
                  |""".stripMargin
              ),
              outputMarker = Some("__MARKER__")
            )
          )
        yield assertTrue(result.output.trim == "Red")
      },

      test("extension methods work (Scala 3)") {
        for
          compiler <- ZIO.service[Compiler]
          result <- compiler.execute(
            """println("hello".shout)""",
            ScopeContext(
              priorCode = Vector(
                """extension (s: String)
                  |  def shout: String = s.toUpperCase + "!"
                  |""".stripMargin
              ),
              outputMarker = Some("__MARKER__")
            )
          )
        yield assertTrue(result.output.trim == "HELLO!")
      },

      test("nested objects work correctly") {
        for
          compiler <- ZIO.service[Compiler]
          result <- compiler.execute(
            """println(Outer.Inner.deepValue)""",
            ScopeContext(
              priorCode = Vector(
                """object Outer:
                  |  object Inner:
                  |    val deepValue = "nested!"
                  |""".stripMargin
              ),
              outputMarker = Some("__MARKER__")
            )
          )
        yield assertTrue(result.output.trim == "nested!")
      },

      test("deeply nested class hierarchies work") {
        for
          compiler <- ZIO.service[Compiler]
          result <- compiler.execute(
            """
              |val app = new App
              |println(app.service.repo.findById(42))
            """.stripMargin,
            ScopeContext(
              priorCode = Vector(
                """class Repository:
                  |  def findById(id: Int): String = s"Found: $id"
                  |
                  |class Service:
                  |  val repo = new Repository
                  |
                  |class App:
                  |  val service = new Service
                  |""".stripMargin
              ),
              outputMarker = Some("__MARKER__")
            )
          )
        yield assertTrue(result.output.trim == "Found: 42")
      }
    )
    // TODO: ZIO service pattern tests require external dependency support
    // See: https://github.com/russwyte/marklit/issues/XX - add classpath config for CLI and plugins
  ).provideShared(
    TestCompilerLayer.layer
  ) @@ TestAspect.sequential
