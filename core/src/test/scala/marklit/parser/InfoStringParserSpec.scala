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
    ),
    suite("shared modifier (scala=shared syntax)")(
      test("scala=shared adds Modifier.Shared and leaves scalaVersion empty") {
        val r = InfoStringParser.parse("scala marklit:scala=shared")
        assertTrue(
          r.isScalaBlock,
          r.modifiers == Set(Modifier.Shared),
          r.scopeConfig.scalaVersion == None
        )
      },
      test(
        "scala=shared-2 adds Modifier.SharedMajor(\"2\") and leaves scalaVersion empty"
      ) {
        val r = InfoStringParser.parse("scala marklit:scala=shared-2")
        assertTrue(
          r.isScalaBlock,
          r.modifiers == Set(Modifier.SharedMajor("2")),
          r.scopeConfig.scalaVersion == None
        )
      },
      test(
        "scala=shared-3 adds Modifier.SharedMajor(\"3\") and leaves scalaVersion empty"
      ) {
        val r = InfoStringParser.parse("scala marklit:scala=shared-3")
        assertTrue(
          r.isScalaBlock,
          r.modifiers == Set(Modifier.SharedMajor("3")),
          r.scopeConfig.scalaVersion == None
        )
      },
      test("bare 'shared' modifier no longer parses as Modifier.Shared") {
        val r = InfoStringParser.parse("scala marklit:shared")
        assertTrue(
          r.isScalaBlock,
          !r.modifiers.contains(Modifier.Shared)
        )
      },
      test("bare 'shared-3' modifier no longer parses as SharedMajor") {
        val r = InfoStringParser.parse("scala marklit:shared-3")
        assertTrue(
          r.isScalaBlock,
          !r.modifiers.exists(_.isInstanceOf[Modifier.SharedMajor])
        )
      },
      test("scala=shared combines with other modifiers and options") {
        val r = InfoStringParser.parse(
          "scala marklit:silent,scala=shared"
        )
        assertTrue(
          r.isScalaBlock,
          r.modifiers == Set(Modifier.Silent, Modifier.Shared),
          r.scopeConfig.scalaVersion == None
        )
      },
      test("scala=3.7.3 still parses as a specific version (no regression)") {
        val r = InfoStringParser.parse("scala marklit:scala=3.7.3")
        assertTrue(
          r.isScalaBlock,
          r.modifiers == Set.empty[Modifier],
          r.scopeConfig.scalaVersion == Some("3.7.3")
        )
      },
      test("scala=3 still parses as a bare major (no regression)") {
        val r = InfoStringParser.parse("scala marklit:scala=3")
        assertTrue(
          r.isScalaBlock,
          r.modifiers == Set.empty[Modifier],
          r.scopeConfig.scalaVersion == Some("3")
        )
      }
    )
  )
