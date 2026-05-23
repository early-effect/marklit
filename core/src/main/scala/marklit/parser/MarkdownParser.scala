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

/** Parser for markdown documents with Scala code blocks */
object MarkdownParser:

  /** Parse a markdown document */
  def parse(
      content: String,
      sourceFile: String
  ): IO[MarklitError, ParsedDocument] =
    parseDocument(content, sourceFile)

  // Parser that captures file context
  private class DocumentParser(sourceFile: String, content: String):

    private def indexToLineCol(idx: Int): (Int, Int) =
      val prefix = content.take(idx)
      val line = prefix.count(_ == '\n') + 1
      val lastNewline = prefix.lastIndexOf('\n')
      val col = if lastNewline < 0 then idx + 1 else idx - lastNewline
      (line, col)

    /** Parse entire document as sequence of segments */
    def document[$: P]: P[Vector[MarkdownSegment]] =
      P(segment.rep ~ End).map(_.toVector)

    /** Parse a single segment (code block or text) */
    def segment[$: P]: P[MarkdownSegment] =
      P(scalaCodeBlock | textSegment)

    /** Parse a Scala code block */
    def scalaCodeBlock[$: P]: P[MarkdownSegment] =
      P(Index ~ codeFenceStart ~ infoString ~ "\n" ~ codeContent ~ codeFenceEnd)
        .map { (tuple: (Int, String, String, String)) =>
          val (idx, _, info, code) = tuple
          val (line, col) = indexToLineCol(idx)
          val parsed = InfoStringParser.parse(info)
          MarkdownSegment.Code(
            CodeBlock(
              code = code.stripSuffix("\n"),
              modifiers = parsed.modifiers,
              scopeConfig = parsed.scopeConfig,
              location = Location(sourceFile, line, col)
            )
          )
        }

    /** Opening fence: ``` or ~~~ at start of line */
    def codeFenceStart[$: P]: P[String] =
      P(
        CharsWhileIn(" \t", 0) ~ (("```" ~ "`".rep(0)).! | ("~~~" ~ "~".rep(
          0
        )).!)
      )

    /** Closing fence: matching ``` or ~~~ */
    def codeFenceEnd[$: P]: P[Unit] =
      P(
        CharsWhileIn(" \t", 0) ~ ("```" ~ "`".rep(0) | "~~~" ~ "~".rep(
          0
        )) ~ CharsWhileIn(" \t", 0) ~ ("\n" | End)
      )

    /** Info string after fence: scala marklit:silent,id=foo */
    def infoString[$: P]: P[String] =
      P(CharsWhile(c => c != '\n' && c != '\r', 0).!)

    /** Code content between fences */
    def codeContent[$: P]: P[String] =
      P((!codeFenceEnd ~ AnyChar).rep.!)

    /** Text segment (everything that's not a code block) */
    def textSegment[$: P]: P[MarkdownSegment] =
      P(Index ~ (!scalaCodeBlock ~ AnyChar).rep(1).!).map {
        (tuple: (Int, String)) =>
          val (idx, text) = tuple
          val (line, col) = indexToLineCol(idx)
          MarkdownSegment.Text(text, Location(sourceFile, line, col))
      }

  /** Main entry point */
  def parseDocument(
      content: String,
      sourceFile: String
  ): IO[MarklitError, ParsedDocument] =
    ZIO
      .attempt {
        val parser = new DocumentParser(sourceFile, content)
        val result = fastparse.parse(content, p => parser.document(using p))
        result match
          case Parsed.Success(segs, _) =>
            ParsedDocument(segs, sourceFile)
          case f: Parsed.Failure =>
            throw new RuntimeException(
              s"Parse error at index ${f.index}: ${f.msg}"
            )
      }
      .mapError { e =>
        MarklitError.ParseError(Location(sourceFile, 1, 1), e.getMessage)
      }
