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

  private def blockWith(
      modifiers: Set[Modifier],
      scopeConfig: ScopeConfig = ScopeConfig.empty
  ): CodeBlock =
    CodeBlock(
      code = "",
      modifiers = modifiers,
      scopeConfig = scopeConfig,
      location = loc
    )

  def spec = suite("CodeBlock")(
    topLevelSuite,
    showWarningsSuite
  )

  private val topLevelSuite = suite("top-level")(
    test("isTopLevel reflects the TopLevel modifier") {
      assertTrue(
        blockWith(Set(Modifier.TopLevel)).isTopLevel,
        !blockWith(Set.empty).isTopLevel,
        !blockWith(Set(Modifier.Silent)).isTopLevel
      )
    },
    test("a top-level block does not execute (compile-only)") {
      assertTrue(!blockWith(Set(Modifier.TopLevel)).shouldExecute)
    },
    test("modifierConflicts is None for a bare top-level block") {
      assertTrue(blockWith(Set(Modifier.TopLevel)).modifierConflicts.isEmpty)
    },
    test("modifierConflicts is None for top-level with only scope options") {
      // id / extends / append / scala=version / show-warnings live on
      // ScopeConfig + showWarningsOverride, not in `modifiers`, so the
      // modifier set is still just {TopLevel}.
      val b = blockWith(
        Set(Modifier.TopLevel),
        ScopeConfig(id = Some("x"), scalaVersion = Some("3.7.3"))
      )
      assertTrue(b.modifierConflicts.isEmpty)
    },
    test(
      "modifierConflicts is Some(...) when top-level pairs with a behavioral modifier"
    ) {
      val bad = List(
        Modifier.Silent,
        Modifier.Invisible,
        Modifier.Fail,
        Modifier.Warn,
        Modifier.Crash,
        Modifier.CompileOnly,
        Modifier.Passthrough,
        Modifier.ZIOApp,
        Modifier.Shared,
        Modifier.SharedMajor("3")
      )
      assertTrue(
        bad.forall(m =>
          blockWith(Set(Modifier.TopLevel, m)).modifierConflicts.isDefined
        )
      )
    },
    test("modifierConflicts is None for a non-top-level block") {
      // Plain blocks never report a top-level conflict, even with several
      // modifiers — modifierConflicts only polices the top-level marker.
      assertTrue(
        blockWith(Set(Modifier.Silent)).modifierConflicts.isEmpty,
        blockWith(Set.empty).modifierConflicts.isEmpty
      )
    }
  )

  private val showWarningsSuite = suite("CodeBlock.showWarnings")(
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
