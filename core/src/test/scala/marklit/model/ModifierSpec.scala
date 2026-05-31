package marklit.model

import zio.test.*

object ModifierSpec extends ZIOSpecDefault:

  def spec = suite("Modifier.parse")(
    suite("top-level")(
      test("parses 'top-level' to Modifier.TopLevel") {
        assertTrue(Modifier.parse("top-level") == Some(Modifier.TopLevel))
      },
      test("is case-insensitive") {
        assertTrue(
          Modifier.parse("Top-Level") == Some(Modifier.TopLevel),
          Modifier.parse("TOP-LEVEL") == Some(Modifier.TopLevel)
        )
      }
    ),
    suite("existing modifiers (no regression)")(
      test("parses the known simple modifiers") {
        assertTrue(
          Modifier.parse("silent") == Some(Modifier.Silent),
          Modifier.parse("invisible") == Some(Modifier.Invisible),
          Modifier.parse("fail") == Some(Modifier.Fail),
          Modifier.parse("warn") == Some(Modifier.Warn),
          Modifier.parse("crash") == Some(Modifier.Crash),
          Modifier.parse("compile-only") == Some(Modifier.CompileOnly),
          Modifier.parse("passthrough") == Some(Modifier.Passthrough),
          Modifier.parse("zio-app") == Some(Modifier.ZIOApp)
        )
      },
      test("returns None for unknown tokens") {
        assertTrue(
          Modifier.parse("toplevel") == None,
          Modifier.parse("top_level") == None,
          Modifier.parse("nonsense") == None
        )
      }
    )
  )
