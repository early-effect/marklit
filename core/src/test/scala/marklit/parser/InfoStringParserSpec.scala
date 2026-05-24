package marklit.parser

import marklit.model.*
import zio.test.*

object InfoStringParserSpec extends ZIOSpecDefault:

  def spec = suite("InfoStringParser")(
    suite("show-warnings option")(
      test("show-warnings=true sets override to Some(true)") {
        val r = InfoStringParser.parse("scala marklit:show-warnings=true")
        assertTrue(
          r.isScalaBlock,
          r.showWarningsOverride == Some(true)
        )
      },
      test("show-warnings=false sets override to Some(false)") {
        val r = InfoStringParser.parse("scala marklit:show-warnings=false")
        assertTrue(
          r.isScalaBlock,
          r.showWarningsOverride == Some(false)
        )
      },
      test("show-warnings=TRUE is case-insensitive") {
        val r = InfoStringParser.parse("scala marklit:show-warnings=TRUE")
        assertTrue(r.showWarningsOverride == Some(true))
      },
      test("show-warnings=garbage is silently ignored") {
        val r = InfoStringParser.parse("scala marklit:show-warnings=garbage")
        assertTrue(
          r.isScalaBlock,
          r.showWarningsOverride == None
        )
      },
      test("absent show-warnings leaves override as None") {
        val r = InfoStringParser.parse("scala marklit:silent")
        assertTrue(r.showWarningsOverride == None)
      },
      test(
        "show-warnings combines with other modifiers and options"
      ) {
        val r = InfoStringParser.parse(
          "scala marklit:silent,show-warnings=false,id=foo"
        )
        assertTrue(
          r.isScalaBlock,
          r.modifiers == Set(Modifier.Silent),
          r.scopeConfig.id == Some("foo"),
          r.showWarningsOverride == Some(false)
        )
      }
    ),
    suite("non-scala blocks")(
      test("non-scala fences yield Passthrough with no override") {
        val r = InfoStringParser.parse("text")
        assertTrue(
          !r.isScalaBlock,
          r.modifiers == Set(Modifier.Passthrough),
          r.showWarningsOverride == None
        )
      }
    )
  )
