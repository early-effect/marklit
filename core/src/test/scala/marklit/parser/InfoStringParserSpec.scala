package marklit.parser

import marklit.model.*
import zio.test.*

object InfoStringParserSpec extends ZIOSpecDefault:

  def spec = suite("InfoStringParser")(
    suite("top-level modifier")(
      test("top-level parses to Modifier.TopLevel") {
        val r = InfoStringParser.parse("scala marklit:top-level")
        assertTrue(
          r.isScalaBlock,
          r.modifiers == Set(Modifier.TopLevel),
          r.scopeConfig == ScopeConfig.empty
        )
      },
      test("top-level,id=foo carries both the modifier and the scope id") {
        val r = InfoStringParser.parse("scala marklit:top-level,id=foo")
        assertTrue(
          r.isScalaBlock,
          r.modifiers == Set(Modifier.TopLevel),
          r.scopeConfig.id == Some("foo")
        )
      },
      test("top-level,extends=foo carries both the modifier and the parent") {
        val r = InfoStringParser.parse("scala marklit:top-level,extends=foo")
        assertTrue(
          r.isScalaBlock,
          r.modifiers == Set(Modifier.TopLevel),
          r.scopeConfig.extendsScope == Some("foo")
        )
      },
      test("top-level,scala=3 keeps the bare major and the modifier") {
        val r = InfoStringParser.parse("scala marklit:top-level,scala=3")
        assertTrue(
          r.isScalaBlock,
          r.modifiers == Set(Modifier.TopLevel),
          r.scopeConfig.scalaVersion == Some("3")
        )
      },
      test("Top-Level is recognized case-insensitively") {
        val r = InfoStringParser.parse("scala marklit:Top-Level")
        assertTrue(r.modifiers == Set(Modifier.TopLevel))
      },
      test("top-level alongside an unknown key still recognizes top-level") {
        val r = InfoStringParser.parse("scala marklit:top-level,bogus=1")
        assertTrue(
          r.isScalaBlock,
          r.modifiers == Set(Modifier.TopLevel)
        )
      },
      test("a non-scala fence with top-level stays passthrough") {
        val r = InfoStringParser.parse("text marklit:top-level")
        assertTrue(
          !r.isScalaBlock,
          r.modifiers == Set(Modifier.Passthrough)
        )
      }
    ),
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
