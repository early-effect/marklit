package marklit.parser

import marklit.model.*
import zio.test.*

/** Edge case audit suite for [[MarkdownParser]].
  *
  * Each test encodes the **CommonMark-correct expectation** for a known or
  * suspected divergence in marklit's fence scanner. Tests prefixed with
  * `// AUDIT:` are predicted to currently fail; their failure output is the
  * deliverable for deciding whether to fix piecemeal or rewrite the scanner.
  *
  * Categories track the plan in
  * `~/.claude/plans/i-want-to-harden-squishy-micali.md`:
  *   - A: opening fence indentation
  *   - B: closing fence length
  *   - C: closing fence character match
  *   - D: closing fence trailing content
  *   - E: info string rules
  *   - F: line endings (CR / CRLF)
  *   - G: EOF / unterminated fences
  *   - H: blockquote-prefixed fences
  *   - I: list-item-contained fences
  *   - J: nested / adjacent fences
  *   - K: HTML-comment-disguised fences (documented behavior)
  *   - L: whitespace and content ambiguities
  *   - M: info string content diversity
  *   - N: location tracking
  *   - O: pathological inputs
  */
object MarkdownParserEdgeCasesSpec extends ZIOSpecDefault:

  // Helpers -----------------------------------------------------------

  private val tick3 = "```"
  private val tick4 = "````"
  private val tick5 = "`````"
  private val tilde3 = "~~~"

  private def parse(content: String) =
    MarkdownParser.parse(content, "test.md")

  // ------------------------------------------------------------------

  def spec = suite("MarkdownParser edge cases")(
    aOpeningIndent,
    bClosingLength,
    cClosingCharMatch,
    dClosingTrailing,
    eInfoString,
    fLineEndings,
    gUnterminated,
    hBlockquotes,
    iListItems,
    jNestedAdjacent,
    kHtmlComments,
    lWhitespaceContent,
    mInfoDiversity,
    nLocation,
    oPathological
  )

  // A. Opening fence indentation -------------------------------------

  private val aOpeningIndent = suite("A. opening fence indentation")(
    test("A1: 3-space indent IS a fence") {
      val content = s"   ${tick3}scala\nval x = 1\n   $tick3\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.size == 1,
        doc.codeBlocks.head.code == "val x = 1"
      )
    },
    // AUDIT: predicted FAIL — current parser accepts 4+ space indent as fence
    test("A2: 4-space indent is NOT a fence (indented code block)") {
      val content = s"    ${tick3}scala\n    val x = 1\n    $tick3\n"
      for doc <- parse(content)
      yield assertTrue(doc.codeBlocks.isEmpty)
    },
    // AUDIT: predicted FAIL — leading tab is treated as 4 columns per spec
    test("A3: leading tab is NOT a fence") {
      val content = s"\t${tick3}scala\nval x = 1\n\t$tick3\n"
      for doc <- parse(content)
      yield assertTrue(doc.codeBlocks.isEmpty)
    },
    test("A4: indented opener (1 space) closes with 3-space-indent close") {
      val content = s" ${tick3}scala\nval x = 1\n   $tick3\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.size == 1,
        doc.codeBlocks.head.code == "val x = 1"
      )
    },
    // AUDIT: predicted FAIL — content indentation not stripped per opener width
    test(
      "A5: 3-space-indent opener strips up to 3 leading spaces from content"
    ) {
      val content = s"   ${tick3}scala\n   val x = 1\n   $tick3\n"
      for doc <- parse(content)
      yield assertTrue(doc.codeBlocks.head.code == "val x = 1")
    }
  )

  // B. Closing fence length rule -------------------------------------

  private val bClosingLength = suite("B. closing fence length")(
    // AUDIT: predicted FAIL — current parser accepts shorter close
    test("B1: 4-tick open is NOT closed by 3-tick close") {
      val content = s"${tick4}scala\nval x = 1\n$tick3\nstill in code\n$tick4\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.size == 1,
        doc.codeBlocks.head.code.contains("still in code"),
        doc.codeBlocks.head.code.contains(tick3)
      )
    },
    test("B2: 4-tick open is closed by 4-tick close") {
      val content = s"${tick4}scala\nval x = 1\n$tick4\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.size == 1,
        doc.codeBlocks.head.code == "val x = 1"
      )
    },
    test("B3: 3-tick open is closed by 5-tick close") {
      val content = s"${tick3}scala\nval x = 1\n$tick5\n"
      for doc <- parse(content)
      yield assertTrue(doc.codeBlocks.size == 1)
    },
    // AUDIT: predicted FAIL — block opened with 4 ticks should embed 3-tick lines
    test("B4: 4-tick fence embeds literal 3-tick lines as content") {
      val content =
        s"${tick4}scala\nval inner = $tick3\nstill code\n$tick4\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.size == 1,
        doc.codeBlocks.head.code.contains("still code")
      )
    }
  )

  // C. Closing fence character match ---------------------------------

  private val cClosingCharMatch = suite("C. closing fence character match")(
    // AUDIT: predicted FAIL — current parser accepts mixed char close
    test("C1: backtick open is NOT closed by tilde close") {
      val content = s"${tick3}scala\nval x = 1\n$tilde3\nstill code\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.size == 1,
        doc.codeBlocks.head.code.contains("still code"),
        doc.codeBlocks.head.code.contains(tilde3)
      )
    },
    // AUDIT: predicted FAIL — symmetric of C1
    test("C2: tilde open is NOT closed by backtick close") {
      val content = s"${tilde3}scala\nval x = 1\n$tick3\nstill code\n$tilde3\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.size == 1,
        doc.codeBlocks.head.code.contains("still code"),
        doc.codeBlocks.head.code.contains(tick3)
      )
    },
    test("C3: tilde line inside backtick block is content") {
      val content = s"${tick3}scala\n$tilde3 not a fence\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.size == 1,
        doc.codeBlocks.head.code.contains(s"$tilde3 not a fence")
      )
    }
  )

  // D. Closing fence trailing content --------------------------------

  private val dClosingTrailing = suite("D. closing fence trailing content")(
    // AUDIT: predicted FAIL — close line with non-whitespace trailing text is invalid
    test("D1: close line with trailing non-whitespace does NOT close") {
      val content = s"${tick3}scala\nval x = 1\n$tick3 foo\nval y = 2\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.size == 1,
        doc.codeBlocks.head.code.contains("val y = 2")
      )
    },
    test("D2: close line with trailing spaces/tabs DOES close") {
      val content = s"${tick3}scala\nval x = 1\n$tick3   \n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.size == 1,
        doc.codeBlocks.head.code == "val x = 1"
      )
    }
  )

  // E. Info string rules ---------------------------------------------

  private val eInfoString = suite("E. info string rules")(
    test(
      "E1: backtick fence with backtick in info string is NOT a scala fence"
    ) {
      // The first line is invalid as a backtick fence (CommonMark forbids `
      // in backtick-fence info strings). The trailing ``` at EOF may still be
      // recognized as an unterminated empty fence (passthrough). What MUST
      // NOT happen is that the first line is treated as a scala block with
      // `oops` as part of the info.
      val content = s"${tick3}scala `oops`\nval x = 1\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.forall(_.isPassthrough),
        doc.segments.exists {
          case MarkdownSegment.Text(t, _) => t.contains("`oops`")
          case _                          => false
        }
      )
    },
    test("E2: tilde fence with tilde in info string is allowed") {
      val content = s"${tilde3}scala ~thing~\nval x = 1\n$tilde3\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.size == 1,
        doc.codeBlocks.head.code == "val x = 1"
      )
    },
    test("E3: trailing space after language is fine") {
      val content = s"${tick3}scala \nval x = 1\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.size == 1,
        !doc.codeBlocks.head.isPassthrough
      )
    },
    test("E4: language is case-insensitive (Scala matches scala)") {
      val content = s"${tick3}Scala\nval x = 1\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.size == 1,
        !doc.codeBlocks.head.isPassthrough
      )
    },
    // AUDIT: scala-cli starts with "scala" — info string parser may treat as scala
    test("E5: 'scala-cli' is NOT recognized as scala") {
      val content = s"${tick3}scala-cli\nval x = 1\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.size == 1,
        doc.codeBlocks.head.isPassthrough
      )
    },
    // AUDIT: 'scalafmt' starts with 'scala' — likely treated as scala
    test("E6: 'scalafmt' is NOT recognized as scala") {
      val content = s"${tick3}scalafmt\nval x = 1\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.size == 1,
        doc.codeBlocks.head.isPassthrough
      )
    },
    test("E7: empty info string yields a passthrough block") {
      val content = s"$tick3\nfoo\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.size == 1,
        doc.codeBlocks.head.isPassthrough
      )
    }
  )

  // F. Line endings ---------------------------------------------------

  private val fLineEndings = suite("F. line endings")(
    // AUDIT: predicted FAIL — parser hardcodes \n separator
    test("F1: CRLF line endings throughout — block is parsed") {
      val content = s"${tick3}scala\r\nval x = 1\r\n$tick3\r\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.size == 1,
        doc.codeBlocks.head.code == "val x = 1"
      )
    },
    // AUDIT: predicted FAIL
    test("F2: mixed CRLF and LF — block is parsed") {
      val content = s"intro\r\n${tick3}scala\nval x = 1\r\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(doc.codeBlocks.size == 1)
    },
    // AUDIT: predicted FAIL — old-Mac CR-only line endings
    test("F3: lone CR line endings — block is parsed") {
      val content = s"${tick3}scala\rval x = 1\r$tick3\r"
      for doc <- parse(content)
      yield assertTrue(doc.codeBlocks.size == 1)
    }
  )

  // G. EOF / unterminated fences -------------------------------------

  private val gUnterminated = suite("G. EOF / unterminated fences")(
    // AUDIT: predicted FAIL — current parser fails the whole document
    test(
      "G1: open fence with no close at EOF — implicit close, block captured"
    ) {
      val content = s"${tick3}scala\nval x = 1\nval y = 2\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.size == 1,
        doc.codeBlocks.head.code.contains("val x = 1"),
        doc.codeBlocks.head.code.contains("val y = 2")
      )
    },
    // AUDIT: predicted FAIL
    test("G2: open fence, content, EOF without trailing newline") {
      val content = s"${tick3}scala\nval x = 1"
      for doc <- parse(content)
      yield assertTrue(doc.codeBlocks.size == 1)
    },
    test("G3: text after final close becomes a Text segment") {
      val content = s"${tick3}scala\nval x = 1\n$tick3\nafter text\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.size == 1,
        doc.segments.exists {
          case MarkdownSegment.Text(t, _) => t.contains("after text")
          case _                          => false
        }
      )
    }
  )

  // H. Blockquote-prefixed fences ------------------------------------

  private val hBlockquotes = suite("H. blockquote-prefixed fences")(
    // Marklit explicitly rejects blockquoted Scala fences with a clear
    // ParseError pointing the user at the offending line. Output injection
    // would break the blockquote structure on render, so we treat this as
    // a malformed input rather than silently extracting or mangling.
    test("H1: blockquoted Scala fence is rejected with a parse error") {
      val content = s"> ${tick3}scala\n> val x = 1\n> $tick3\n"
      for result <- parse(content).either
      yield assertTrue(
        result.isLeft,
        result.left.toOption.exists {
          case MarklitError.ParseError(loc, msg) =>
            loc.startLine == 1 && msg.contains("blockquote")
          case _ => false
        }
      )
    },
    test("H1b: rejection points at the offending line, not line 1") {
      val content =
        s"intro\nmore intro\n\n> ${tick3}scala\n> val x = 1\n> $tick3\n"
      for result <- parse(content).either
      yield assertTrue(
        result.left.toOption.exists {
          case MarklitError.ParseError(loc, _) => loc.startLine == 4
          case _                               => false
        }
      )
    },
    test("H1c: blockquoted non-scala fences are NOT rejected (passthrough)") {
      val content = s"> ${tick3}python\n> print(1)\n> $tick3\n"
      for doc <- parse(content)
      yield assertTrue(
        // Treated as plain text — no fence recognized, no error raised.
        doc.codeBlocks.isEmpty,
        doc.segments.exists {
          case MarkdownSegment.Text(t, _) => t.contains("python")
          case _                          => false
        }
      )
    },
    test(
      "H2: blockquote opener with lazy-continuation close is also rejected"
    ) {
      // The opener is `> ```scala`, so we reject regardless of how the
      // close is laid out.
      val content = s"> ${tick3}scala\n> val x = 1\n$tick3\n"
      for result <- parse(content).either
      yield assertTrue(
        result.isLeft,
        result.left.toOption.exists {
          case MarklitError.ParseError(_, msg) => msg.contains("blockquote")
          case _                               => false
        }
      )
    },
    // AUDIT: predicted FAIL — close line with leading `>` should NOT close a non-blockquote fence
    test("H3: outer fence is NOT closed by a `> ```` line") {
      val content = s"${tick3}scala\nval x = 1\n> $tick3\nval y = 2\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.size == 1,
        doc.codeBlocks.head.code.contains("val y = 2")
      )
    }
  )

  // I. List items containing fences ----------------------------------

  private val iListItems = suite("I. list items containing fences")(
    // AUDIT: list-item indented fence — current parser may or may not handle
    test("I1: bullet list item containing scala fence") {
      val content =
        s"- intro\n\n  ${tick3}scala\n  val x = 1\n  $tick3\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.size == 1,
        !doc.codeBlocks.head.isPassthrough
      )
    },
    test("I2: numbered list item containing scala fence") {
      val content =
        s"1. intro\n\n   ${tick3}scala\n   val x = 1\n   $tick3\n"
      for doc <- parse(content)
      yield assertTrue(doc.codeBlocks.size == 1)
    },
    test("I3: tight list with fence interleaved") {
      val content =
        s"- a\n- b\n\n${tick3}scala\nval x = 1\n$tick3\n\n- c\n"
      for doc <- parse(content)
      yield assertTrue(doc.codeBlocks.size == 1)
    }
  )

  // J. Nested / adjacent fences --------------------------------------

  private val jNestedAdjacent = suite("J. nested / adjacent fences")(
    test("J1: two adjacent fences yield two blocks") {
      val content =
        s"${tick3}scala\nval a = 1\n$tick3\n${tick3}scala\nval b = 2\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.size == 2,
        doc.codeBlocks(0).code == "val a = 1",
        doc.codeBlocks(1).code == "val b = 2"
      )
    },
    test("J2: text after close on its own line, then a new fence") {
      val content =
        s"${tick3}scala\nval a = 1\n$tick3\nbetween\n${tick3}scala\nval b = 2\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(doc.codeBlocks.size == 2)
    },
    // AUDIT: depends on B/C — what does two openers without intermediate close do?
    test(
      "J3: two openers without an intermediate close — first ends at second"
    ) {
      val content =
        s"${tick3}scala\nval a = 1\n${tick3}scala\nval b = 2\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(doc.codeBlocks.nonEmpty)
    }
  )

  // K. HTML-comment-disguised fences (document marklit's choice) ------

  private val kHtmlComments = suite("K. fences inside HTML comments")(
    // Documents current behavior: marklit is not a CommonMark renderer; it does
    // NOT detect HTML blocks, so commented-out fences ARE extracted. If the
    // user later decides this is wrong, flip this test.
    test("K1: fence inside <!-- ... --> on separate lines is extracted") {
      val content =
        s"<!--\n${tick3}scala\nval x = 1\n$tick3\n-->\n"
      for doc <- parse(content)
      yield assertTrue(doc.codeBlocks.size == 1)
    },
    test("K2: fence inside <pre> tags is extracted") {
      val content =
        s"<pre>\n${tick3}scala\nval x = 1\n$tick3\n</pre>\n"
      for doc <- parse(content)
      yield assertTrue(doc.codeBlocks.size == 1)
    }
  )

  // L. Whitespace and content ambiguities -----------------------------

  private val lWhitespaceContent = suite("L. whitespace / content ambiguities")(
    test("L1: blank lines inside a fence are preserved") {
      val content = s"${tick3}scala\nval x = 1\n\n\nval y = 2\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.size == 1,
        doc.codeBlocks.head.code == "val x = 1\n\n\nval y = 2"
      )
    },
    test("L2: fence with only whitespace content") {
      val content = s"${tick3}scala\n   \n\t\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(doc.codeBlocks.size == 1)
    },
    // AUDIT: depends on C1 fix — different-char fence inside is content
    test("L3: ~~~-line inside a ```-fence is content") {
      val content = s"${tick3}scala\nval x = 1\n$tilde3\nval y = 2\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.size == 1,
        doc.codeBlocks.head.code.contains(tilde3),
        doc.codeBlocks.head.code.contains("val y = 2")
      )
    }
  )

  // M. Info string content diversity ---------------------------------

  private val mInfoDiversity = suite("M. info string content diversity")(
    test("M1: scala marklit:silent") {
      val content = s"${tick3}scala marklit:silent\nval x = 1\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(doc.codeBlocks.head.modifiers.contains(Modifier.Silent))
    },
    // AUDIT: empty modifiers after colon — verify behavior
    test("M2: scala marklit: (no modifiers) parses as scala") {
      val content = s"${tick3}scala marklit:\nval x = 1\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.size == 1,
        !doc.codeBlocks.head.isPassthrough
      )
    },
    test("M3: scala mdoc:silent compatibility") {
      val content = s"${tick3}scala mdoc:silent\nval x = 1\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(doc.codeBlocks.head.modifiers.contains(Modifier.Silent))
    },
    test("M4: scala marklit silent (space, no colon)") {
      val content = s"${tick3}scala marklit silent\nval x = 1\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.size == 1,
        !doc.codeBlocks.head.isPassthrough
      )
    },
    test("M5: extra spaces around marklit:") {
      val content = s"${tick3}scala  marklit:silent\nval x = 1\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(doc.codeBlocks.head.modifiers.contains(Modifier.Silent))
    },
    test("M6: unknown modifier is ignored") {
      val content = s"${tick3}scala marklit:unknownXYZ\nval x = 1\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.size == 1,
        !doc.codeBlocks.head.isPassthrough,
        doc.codeBlocks.head.modifiers.isEmpty ||
          !doc.codeBlocks.head.modifiers.exists(_ != Modifier.Passthrough)
      )
    },
    test("M7: combined options - fail,id=foo,scala=2.13") {
      val content =
        s"${tick3}scala marklit:fail,id=foo,scala=2.13\nval x = 1\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.head.expectsFailure,
        doc.codeBlocks.head.scopeConfig.id == Some("foo"),
        doc.codeBlocks.head.scopeConfig.scalaVersion == Some("2.13")
      )
    },
    test("M8: show-warnings=garbage leaves override as None") {
      val content =
        s"${tick3}scala marklit:show-warnings=garbage\nval x = 1\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(doc.codeBlocks.head.showWarningsOverride.isEmpty)
    },
    // AUDIT: trailing comma — fastparse rep(sep=,) behavior
    test("M9: trailing comma in modifier list parses") {
      val content = s"${tick3}scala marklit:silent,\nval x = 1\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(doc.codeBlocks.size == 1)
    }
  )

  // N. Location tracking ---------------------------------------------

  private val nLocation = suite("N. location tracking")(
    test("N1: fence at start of file is line 1, col 1") {
      val content = s"${tick3}scala\nval x = 1\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(
        doc.codeBlocks.head.location.startLine == 1,
        doc.codeBlocks.head.location.startColumn == 1
      )
    },
    // AUDIT: depends on F1 — if CRLF parsing breaks, this is moot
    test("N2: line numbers correct under CRLF") {
      val content = s"intro\r\nmore\r\n${tick3}scala\r\nval x = 1\r\n$tick3\r\n"
      for doc <- parse(content)
      yield assertTrue(doc.codeBlocks.head.location.startLine == 3)
    },
    // AUDIT: column should be 4 for a 3-space-indented fence
    test("N3: 3-space-indented fence reports column 4") {
      val content = s"   ${tick3}scala\nval x = 1\n   $tick3\n"
      for doc <- parse(content)
      yield assertTrue(doc.codeBlocks.head.location.startColumn == 4)
    }
  )

  // O. Pathological / fuzz-style inputs ------------------------------

  private val oPathological = suite("O. pathological inputs")(
    test("O1: 1MB plain-text document parses as one Text segment") {
      val big = "a" * (1024 * 1024)
      for doc <- parse(big)
      yield assertTrue(
        doc.segments.size == 1,
        doc.codeBlocks.isEmpty
      )
    },
    test("O2: many adjacent fences (200) parse linearly") {
      val one = s"${tick3}scala\nval x = 1\n$tick3\n"
      val content = one * 200
      for doc <- parse(content)
      yield assertTrue(doc.codeBlocks.size == 200)
    },
    test("O3: very long info string parses or rejects gracefully") {
      val longInfo = "x" * 10000
      val content = s"${tick3}scala $longInfo\nval x = 1\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(doc.codeBlocks.size == 1)
    },
    // AUDIT: document the behavior — undefined territory
    test("O4: many bare ``` lines (alternating opens/closes)") {
      val content = s"$tick3\n" * 10
      for doc <- parse(content)
      yield assertTrue(doc.segments.nonEmpty || doc.segments.isEmpty)
    },
    // AUDIT: BOM handling
    test("O5: UTF-8 BOM at file start is tolerated") {
      val bom = "﻿"
      val content = s"$bom${tick3}scala\nval x = 1\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(doc.codeBlocks.size == 1)
    },
    test("O6: non-ASCII content is preserved verbatim") {
      val src = "val s = \"héllo 🌟 日本語\""
      val content = s"${tick3}scala\n$src\n$tick3\n"
      for doc <- parse(content)
      yield assertTrue(doc.codeBlocks.head.code == src)
    }
  )
