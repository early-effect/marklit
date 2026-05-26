package marklit.parser

import fastparse.*
import fastparse.NoWhitespace.*
import marklit.model.*
import zio.*

/** Represents a segment of markdown content */
enum MarkdownSegment:
  case Text(content: String, location: Location)
  case Code(block: CodeBlock)

/** Parsed markdown document */
final case class ParsedDocument(
    segments: Vector[MarkdownSegment],
    sourceFile: String
):
  def codeBlocks: Vector[CodeBlock] =
    segments.collect { case MarkdownSegment.Code(block) => block }

/** Parser for markdown documents with Scala code blocks.
  *
  * The parser is intentionally minimal: it recognizes CommonMark fenced code
  * blocks and treats everything else as opaque text. The CommonMark fence rules
  * implemented here:
  *
  *   - Opener may be indented 0–3 spaces (4+ would be an indented code block).
  *   - Fence character is `` ` `` or `~`, repeated at least 3 times.
  *   - Closer must use the same character as the opener, at length ≥ opener.
  *   - Closer line may have only whitespace after the fence; non-whitespace
  *     trailing content disqualifies it as a closer.
  *   - Backtick-fence info strings may not contain `` ` ``.
  *   - An unterminated fence is implicitly closed at EOF.
  *   - Content lines have up to opener-indent leading spaces stripped.
  *
  * Input line endings (`\r\n`, lone `\r`) are normalized to `\n` before parsing
  * so that line counts remain accurate and the grammar can speak in terms of
  * `\n` only.
  */
object MarkdownParser:

  /** Internal block-level AST. Public callers see [[MarkdownSegment]]; this
    * intermediate AST exists so the parser can preserve fence metadata (indent,
    * char, length) for richer error reporting and future renderer fidelity. The
    * renderer currently normalizes fences to ``` so most of this metadata is
    * unused downstream — but it's a free byproduct of the spec-correct parser
    * and useful for diagnostics.
    */
  private enum Block:
    case Fenced(
        indent: Int,
        fenceChar: Char,
        fenceLen: Int,
        info: String,
        content: String,
        location: Location
    )
    case RawText(content: String, location: Location)

  /** Parse a markdown document */
  def parse(
      content: String,
      sourceFile: String
  ): IO[MarklitError, ParsedDocument] =
    parseDocument(content, sourceFile)

  /** Normalize line endings: CRLF and lone CR become LF. Preserves line count
    * so `Location` reporting stays correct.
    */
  private def normalizeLineEndings(s: String): String =
    s.replace("\r\n", "\n").replace('\r', '\n')

  /** Compute (line, column) for a 0-based byte index in `content`. */
  private def indexToLineCol(content: String, idx: Int): (Int, Int) =
    val safeIdx = math.min(idx, content.length)
    val prefix = content.substring(0, safeIdx)
    val line = prefix.count(_ == '\n') + 1
    val lastNewline = prefix.lastIndexOf('\n')
    val col = if lastNewline < 0 then safeIdx + 1 else safeIdx - lastNewline
    (line, col)

  // --- fastparse grammar ------------------------------------------------

  // A line of zero or more non-newline chars, consumed without the trailing newline.
  private def restOfLine[$: P]: P[String] =
    P(CharsWhile(_ != '\n', 0).!)

  // 0..3 spaces of indentation (CommonMark fence-opener cap).
  private def fenceIndent[$: P]: P[Int] =
    P(" ".rep(min = 0, max = 3).!).map(_.length)

  // A run of backticks of length ≥ 3, returns the run length.
  private def backtickRun[$: P]: P[Int] =
    P(CharsWhileIn("`").!).flatMap { s =>
      if s.length >= 3 then Pass(s.length) else Fail
    }

  // A run of tildes of length ≥ 3, returns the run length.
  private def tildeRun[$: P]: P[Int] =
    P(CharsWhileIn("~").!).flatMap { s =>
      if s.length >= 3 then Pass(s.length) else Fail
    }

  // Opening fence: returns (fenceStartIdx, indent, fenceChar, fenceLen, info).
  // `fenceStartIdx` is the byte offset of the first fence char (after indent),
  // so the location reflects the fence column rather than line start.
  private def fenceOpen[$: P]: P[(Int, Int, Char, Int, String)] =
    P(
      fenceIndent.flatMap { indent =>
        Index.flatMap { startIdx =>
          (backtickRun.map(n => ('`', n)) | tildeRun.map(n => ('~', n)))
            .flatMap { case (ch, len) =>
              // Info string: rest of line, with constraint for backtick fences.
              restOfLine.flatMap { info =>
                if ch == '`' && info.contains('`') then Fail
                else Pass((startIdx, indent, ch, len, info))
              }
            }
        }
      } ~ "\n"
    )

  // Close-fence line for a backtick-opened fence of length `openLen`.
  private def backtickCloseLine[$: P](openLen: Int): P[Unit] =
    P(
      " ".rep(min = 0, max = 3) ~
        CharsWhileIn("`").!.flatMap { run =>
          if run.length >= openLen then Pass else Fail
        } ~
        CharsWhileIn(" \t", 0) ~ ("\n" | End)
    )

  // Close-fence line for a tilde-opened fence of length `openLen`.
  private def tildeCloseLine[$: P](openLen: Int): P[Unit] =
    P(
      " ".rep(min = 0, max = 3) ~
        CharsWhileIn("~").!.flatMap { run =>
          if run.length >= openLen then Pass else Fail
        } ~
        CharsWhileIn(" \t", 0) ~ ("\n" | End)
    )

  // Dispatch on fence char.
  private def fenceCloseLine[$: P](ch: Char, openLen: Int): P[Unit] =
    if ch == '`' then backtickCloseLine(openLen) else tildeCloseLine(openLen)

  // A non-closing content line: any line that's NOT a close fence. Each line
  // must consume at least one character so that `rep` makes progress. A line
  // is either: (a) "\n" alone (empty line), (b) ≥1 non-newline chars
  // optionally followed by "\n", or (c) at EOF, the call site uses `End`
  // instead of this parser.
  private def contentLine[$: P](ch: Char, openLen: Int): P[String] =
    P(
      !fenceCloseLine(ch, openLen) ~
        (("\n".!) |
          (CharsWhile(_ != '\n', 1).! ~ ("\n".! | Pass(""))).map {
            case (line, nl) => line + nl
          })
    )

  // Body of a fenced block: zero or more content lines, ended by either an
  // explicit close-fence line OR end of input (CommonMark implicit close).
  private def fenceBody[$: P](ch: Char, openLen: Int): P[String] =
    P(
      contentLine(ch, openLen).rep.map(_.mkString) ~
        (fenceCloseLine(ch, openLen) | End)
    )

  // A complete fenced block. Returns (fenceStartIdx, AST node). The index is
  // the offset of the first fence char (post-indent), used for column-correct
  // location reporting.
  private def fencedBlock[$: P]: P[(Int, Block.Fenced)] =
    P(
      fenceOpen.flatMap { case (startIdx, indent, ch, len, info) =>
        fenceBody(ch, len).map { body =>
          (startIdx, indent, ch, len, info, body)
        }
      }
    ).map { case (startIdx, indent, ch, len, info, body) =>
      (
        startIdx,
        Block.Fenced(
          indent = indent,
          fenceChar = ch,
          fenceLen = len,
          info = info,
          content = body,
          // Placeholder; replaced after we know the source file & line.
          location = Location("", 0, 0)
        )
      )
    }

  // A raw-text run line: one line that does NOT begin a fence. Each line must
  // consume at least one character so `rep` makes progress.
  private def rawLine[$: P]: P[String] =
    P(
      !fenceOpen ~
        (("\n".!) |
          (CharsWhile(_ != '\n', 1).! ~ ("\n".! | Pass("")))
            .map { case (line, nl) => line + nl })
    )

  private def rawTextRun[$: P]: P[(Int, String)] =
    P(Index ~ rawLine.rep(1).map(_.mkString))

  // Top-level: alternation of fenced block or raw-text run, until End.
  private def document[$: P]: P[Vector[(Int, Either[Block.Fenced, String])]] =
    P(
      (fencedBlock.map { case (i, fb) => (i, Left(fb)) }
        | rawTextRun.map { case (i, txt) => (i, Right(txt)) }).rep ~ End
    ).map(_.toVector)

  // --- driver -----------------------------------------------------------

  /** Strip up to `n` leading spaces from each line of `s`. Used to match
    * CommonMark's indent-stripping for indented fence openers.
    */
  private def stripLeadingIndent(s: String, n: Int): String =
    if n <= 0 then s
    else
      s.split("\n", -1)
        .map { line =>
          var i = 0
          while i < line.length && i < n && line.charAt(i) == ' ' do i += 1
          line.substring(i)
        }
        .mkString("\n")

  /** Project the internal AST into the public segment view. */
  private def toSegment(
      sourceFile: String,
      idx: Int,
      content: String,
      block: Either[Block.Fenced, String]
  ): MarkdownSegment =
    val (line, col) = indexToLineCol(content, idx)
    val location = Location(sourceFile, line, col)
    block match
      case Right(text) => MarkdownSegment.Text(text, location)
      case Left(fb)    =>
        val info = InfoStringParser.parse(fb.info)
        // Drop trailing newline that fenceBody may leave on content; also
        // strip up to `indent` leading spaces from each content line.
        val rawCode = fb.content.stripSuffix("\n")
        val code = stripLeadingIndent(rawCode, fb.indent)
        MarkdownSegment.Code(
          CodeBlock(
            code = code,
            modifiers = info.modifiers,
            scopeConfig = info.scopeConfig,
            location = location,
            showWarningsOverride = info.showWarningsOverride
          )
        )

  /** Detects a line that starts with `>` (a blockquote prefix) and then opens a
    * Scala fence. We reject these explicitly: marklit does not extract code
    * from inside blockquotes, since output injection would break the quote
    * structure. Returns Some((lineNumber, columnOfGreaterThan)) for the first
    * offending line, or None if no offense is found.
    */
  private def detectBlockquotedScalaFence(
      normalized: String
  ): Option[(Int, Int)] =
    // Line-by-line scan. We don't reuse the InfoStringParser to keep this
    // detector cheap and self-contained — a regex is plenty.
    val pattern =
      """^(\s{0,3})(>+\s*)(`{3,}|~{3,})\s*[Ss]cala(\b|$)""".r
    val lines = normalized.split("\n", -1)
    var i = 0
    while i < lines.length do
      val line = lines(i)
      pattern.findFirstMatchIn(line) match
        case Some(m) =>
          // Column is 1-based; group 1 is the leading indent.
          val col = m.start(2) + 1
          return Some((i + 1, col))
        case None => ()
      i += 1
    None

  /** Main entry point */
  def parseDocument(
      content: String,
      sourceFile: String
  ): IO[MarklitError, ParsedDocument] =
    ZIO
      .attempt {
        val normalized = normalizeLineEndings(content)
        detectBlockquotedScalaFence(normalized) match
          case Some((line, col)) =>
            throw new BlockquoteFenceException(line, col)
          case None => ()
        fastparse.parse(normalized, p => document(using p)) match
          case Parsed.Success(items, _) =>
            val segs = items.map { case (idx, block) =>
              toSegment(sourceFile, idx, normalized, block)
            }
            ParsedDocument(segs, sourceFile)
          case f: Parsed.Failure =>
            val (line, col) = indexToLineCol(normalized, f.index)
            throw new RuntimeException(
              s"Parse error at $sourceFile:$line:$col: ${f.msg}"
            )
      }
      .mapError {
        case e: BlockquoteFenceException =>
          MarklitError.ParseError(
            Location(sourceFile, e.line, e.col),
            "Scala code fences inside blockquotes are not supported. " +
              "Move the fence outside the `>` block (or remove the `>` " +
              "prefix on the fence and its content lines) so marklit can " +
              "extract and compile it."
          )
        case e: RuntimeException =>
          MarklitError.ParseError(Location(sourceFile, 1, 1), e.getMessage)
        case e =>
          MarklitError.ParseError(Location(sourceFile, 1, 1), e.getMessage)
      }

  // Internal sentinel for the blockquoted-fence rejection path.
  private class BlockquoteFenceException(val line: Int, val col: Int)
      extends RuntimeException
