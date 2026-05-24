package marklit.model

import zio.test.*

object CodeBlockSpec extends ZIOSpecDefault:

  private val loc = Location("test.md", 1, 1)

  private def block(override_ : Option[Boolean]): CodeBlock =
    CodeBlock(
      code = "",
      modifiers = Set.empty,
      scopeConfig = ScopeConfig.empty,
      location = loc,
      showWarningsOverride = override_
    )

  def spec = suite("CodeBlock.showWarnings")(
    test("returns the default when override is None") {
      val b = block(None)
      assertTrue(
        b.showWarnings(true) == true,
        b.showWarnings(false) == false
      )
    },
    test("returns true when override is Some(true), regardless of default") {
      val b = block(Some(true))
      assertTrue(
        b.showWarnings(true) == true,
        b.showWarnings(false) == true
      )
    },
    test("returns false when override is Some(false), regardless of default") {
      val b = block(Some(false))
      assertTrue(
        b.showWarnings(true) == false,
        b.showWarnings(false) == false
      )
    },
    test("default constructor leaves override as None") {
      val b = CodeBlock(
        code = "",
        modifiers = Set.empty,
        scopeConfig = ScopeConfig.empty,
        location = loc
      )
      assertTrue(b.showWarningsOverride == None)
    }
  )
