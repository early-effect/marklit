package marklit.docs

import ascent.ast.UI
import ascent.domtypes.AttrValue
import marklit.compiler.Compiler
import marklit.parser.MarkdownParser
import mermoid.{LayoutConfig, Mermaid, RenderConfig}
import mermoid.ascent.MermoidAscent
import specular.*
import specular.ziotest.DocSpecSuite
import zio.test.*

/** Install coordinate. Judge the raw build version. Do not trust a version
  * after `-ci` was stripped.
  */
object DocsVersion:
  /** Last published tag (`v0.1.0`). ShipGroup `0.1.1` is the next line and is
    * not published.
    */
  val published = "0.1.0"

  /** A dynver distance (`-ci`, `+`, `SNAPSHOT`) is the next line. Anything else
    * is advertised as written.
    */
  def advertise(raw: String): String =
    val trimmed = raw.trim
    val distance =
      trimmed.contains("-ci") || trimmed.contains("+") || trimmed.contains(
        "SNAPSHOT"
      )
    if trimmed.nonEmpty && !distance then trimmed else published

  /** `specular.meta.version` is the raw build version. Absent in unit tests, so
    * those use [[published]].
    */
  def version: String =
    sys.props.get("specular.meta.version").map(advertise).getOrElse(published)
end DocsVersion

/** The site front. Specular still emits a summary index; [[BuildSite]] copies
  * this page over it.
  *
  * Not a sidebar item.
  */
object Front extends DocSpecSuite:

  private val coordinate: String =
    s"""addSbtPlugin("rocks.earlyeffect" % "sbt-marklit" % "${DocsVersion.version}")"""

  /** Narrow enough for a 390px column after the content padding. */
  private val figure = RenderConfig(layout =
    LayoutConfig(hSpacing = 32, padding = 8, vSpacing = 36)
  )

  private val pipeline =
    Mermaid("""flowchart TB
              |    md[markdown file] --> parse[parse]
              |    parse --> s3[Scala 3]
              |    parse --> s2[Scala 2]
              |    s3 --> e3[execute]
              |    s2 --> e2[execute]
              |    e3 --> render[render]
              |    e2 --> render
              |    render --> out[rendered file]
              |""".stripMargin)

  private def plainText(ui: UI[Any]): String = ui match
    case UI.Text(value)        => value
    case e: UI.Element[Any]    => e.children.map(plainText).mkString
    case UI.Fragment(children) => children.map(plainText).mkString
    case _                     => ""

  private def svgWidths(ui: UI[Any]): List[Double] = ui match
    case e: UI.Element[Any] =>
      val here =
        if e.tag == "svg" then
          e.attrs.collect {
            case ascent.ast.Attr.StaticAttr("width", AttrValue.Str(raw)) =>
              raw.toDoubleOption
          }.flatten
        else Nil
      here.toList ++ e.children.toList.flatMap(svgWidths)
    case UI.Fragment(children) => children.toList.flatMap(svgWidths)
    case _                     => Nil

  def doc = page("The build breaks")(
    md"""
If the doc claims something works, the build breaks when it does not.

marklit compiles each Scala fence in a Markdown file against the real project classpath, runs it, and writes the output back. One file can hold more than one Scala version. Each fence is compiled by its own compiler.
""",
    section("Two compilers")(
      md"""
`MarkdownParser.parse` reads the file. Each fence is compiled by the compiler for its Scala version (`Compiler.compile`) and then executed. `MarkdownRenderer.render` produces the markdown, and the run writes that one file.
""",
      illustration(MermoidAscent.svgDiagram(pipeline, figure)).assert { ui =>
        val text = plainText(ui)
        val widths = svgWidths(ui)
        assertTrue(
          text.contains("markdown file"),
          text.contains("parse"),
          text.contains("Scala 3"),
          text.contains("Scala 2"),
          text.contains("execute"),
          text.contains("render"),
          text.contains("rendered file"),
          widths.nonEmpty,
          widths.forall(_ > 0),
          widths.forall(_ <= 320)
        ).label(s"widths ${widths.mkString(",")}")
      }
    ),
    section("Install")(
      md"""
The coordinate is the last published release. A build whose version contains `-ci`, `+`, or `SNAPSHOT` still shows that release.

```scala
${coordinate}
```
"""
    ),
    section("Compile")(
      md"""
`Compiler.compile` on the companion takes the fence and its scope and asks the `Compiler` service to compile. It is not inline. Two parameters, so the cite is `cite(Compiler.compile(_, _))`. The panel is that method. The footer is the definition.
""",
      cite(Compiler.compile(_, _))
    ),
    section("One fence")(
      md"""
This fence is compiled by the docs build, against marklit. It parses one markdown file and checks that the two fences name two Scala versions. If they do not, the build breaks.
""",
      exampleZIO {
        val source =
          """```scala marklit:scala=3.3.7
            |println(1 + 1)
            |```
            |
            |```scala marklit:scala=2.13.16
            |println(2 + 2)
            |```
            |""".stripMargin
        MarkdownParser.parse(source, "two-compilers.md").map { doc =>
          doc.codeBlocks.map(_.requestedSpecificScalaVersion)
        }
      }.assert { versions =>
        assertTrue(versions == Vector(Some("3.3.7"), Some("2.13.16")))
      }
    )
  )

  override def spec =
    suite("The build breaks")(
      super.spec,
      test("install coordinate is the published release") {
        val version = DocsVersion.version
        assertTrue(
          coordinate.contains(version),
          coordinate.contains("addSbtPlugin"),
          coordinate.contains("rocks.earlyeffect"),
          coordinate.contains("% \"sbt-marklit\""),
          !coordinate.contains("%%"),
          !coordinate.contains("<version>"),
          !version.contains("-ci"),
          !version.contains("+"),
          !version.contains("SNAPSHOT"),
          !coordinate.contains("-ci"),
          !coordinate.contains("SNAPSHOT"),
          DocsVersion.advertise("0.1.1-ci") == DocsVersion.published,
          DocsVersion.advertise("0.1.0+23-gc0c806d") == DocsVersion.published,
          DocsVersion.advertise("0.1.1-SNAPSHOT") == DocsVersion.published,
          DocsVersion.advertise("0.1.0") == "0.1.0"
        )
      }
    )
end Front
