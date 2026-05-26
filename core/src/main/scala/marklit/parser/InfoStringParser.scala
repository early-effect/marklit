package marklit.parser

import fastparse.*
import fastparse.NoWhitespace.*
import marklit.model.*

/** Fastparse-based parser for code fence info strings.
  *
  * Parses the text after opening code fences, extracting:
  *   - Language identifier (e.g., "scala")
  *   - Modifiers (e.g., "silent", "fail", "crash")
  *   - Scope options (e.g., "id=foo", "extends=bar", "append")
  *   - Scala version (e.g., "scala=3")
  *
  * Example: "scala marklit:silent,id=foo,extends=bar" parses to:
  *   - isScalaBlock = true
  *   - modifiers = Set(Silent)
  *   - scopeConfig = ScopeConfig(id=Some("foo"), extendsScope=Some("bar"), ...)
  */
object InfoStringParser:

  // Character predicates
  private def isIdentStart(c: Char): Boolean = c.isLetter || c == '_'
  private def isIdentPart(c: Char): Boolean =
    c.isLetterOrDigit || c == '_' || c == '-' || c == '.'
  private def isValueChar(c: Char): Boolean =
    c != ',' && c != ' ' && c != '\t' && c != '\n'

  // Basic parsers
  private def ws[$: P]: P[Unit] = P(CharsWhile(c => c == ' ' || c == '\t', 0))

  private def identifier[$: P]: P[String] =
    P(CharPred(isIdentStart) ~ CharsWhile(isIdentPart, 0)).!

  private def value[$: P]: P[String] =
    P(CharsWhile(isValueChar, 1).!)

  // Key=value pair parser
  private def keyValue[$: P]: P[(String, String)] =
    P(identifier ~ "=" ~ value)

  // Single item: either an option (key=value) or a modifier (identifier)
  private def modifierOrOption[$: P]: P[Either[String, (String, String)]] =
    P(keyValue.map(Right(_)) | identifier.map(Left(_)))

  // Comma-separated list of modifiers and options
  private def modifierList[$: P]: P[Seq[Either[String, (String, String)]]] =
    P(modifierOrOption.rep(sep = ws ~ "," ~ ws))

  // The language token must be exactly "scala" (case-insensitive), bounded so
  // that "scala-cli", "scalafmt", "scalajs" etc. don't match. The boundary is:
  // end of input, or any character that is NOT an identifier-continuation
  // char (letter, digit, '_', '-', '.').
  private def isLangContinuation(c: Char): Boolean =
    c.isLetterOrDigit || c == '_' || c == '-' || c == '.'

  private def scalaLang[$: P]: P[Unit] =
    P(StringInIgnoreCase("scala") ~ !CharPred(isLangContinuation))

  // Full info string: "scala" followed by optional "marklit:" or "mdoc:" and modifiers
  // We prefer "marklit:" but support "mdoc:" for compatibility
  private def infoString[$: P]
      : P[(Boolean, Seq[Either[String, (String, String)]])] =
    P(
      ws ~
        (scalaLang ~ ws ~
          (("marklit" | "mdoc") ~ ws ~ ":".? ~ ws).? ~
          modifierList).map(mods => (true, mods)) |
        // Not a scala block
        CharsWhile(_ => true, 0).map(_ => (false, Seq.empty))
    )

  /** Parse result from info string */
  case class ParsedInfoString(
      isScalaBlock: Boolean,
      modifiers: Set[Modifier],
      scopeConfig: ScopeConfig,
      showWarningsOverride: Option[Boolean] = None
  )

  private def parseBool(s: String): Option[Boolean] =
    s.toLowerCase match
      case "true"  => Some(true)
      case "false" => Some(false)
      case _       => None

  /** Parse an info string into modifiers and scope config */
  def parse(info: String): ParsedInfoString =
    fastparse.parse(info.trim, infoString(using _)) match
      case Parsed.Success((false, _), _) =>
        ParsedInfoString(false, Set(Modifier.Passthrough), ScopeConfig.empty)

      case Parsed.Success((true, items), _) =>
        var modifiers = Set.empty[Modifier]
        var id: Option[String] = None
        var extendsScope: Option[String] = None
        var append: Boolean = false
        var scalaVersion: Option[String] = None
        var showWarningsOverride: Option[Boolean] = None

        items.foreach {
          case Left(name) =>
            // Simple identifier - check if it's a modifier or "append"
            if name == "append" then append = true
            else Modifier.parse(name).foreach(m => modifiers += m)

          case Right((key, value)) =>
            // Key=value pair
            key match
              case "id"      => id = Some(value)
              case "extends" => extendsScope = Some(value)
              case "scala"   =>
                // `scala=shared` / `scala=shared-{mv}` are *not* version
                // requests — they declare a cross-version shared block. Map
                // them to the corresponding Modifier and leave scalaVersion
                // unset so downstream code never sees "shared" as a version.
                value.toLowerCase match
                  case "shared" => modifiers += Modifier.Shared
                  case other if other.startsWith("shared-") =>
                    val mv = other.stripPrefix("shared-")
                    if mv.nonEmpty then
                      modifiers += Modifier.SharedMajor(mv)
                  case _ => scalaVersion = Some(value)
              case "show-warnings" => showWarningsOverride = parseBool(value)
              case _               => () // Ignore unknown keys
        }

        val scopeConfig =
          ScopeConfig(id, extendsScope, append, scalaVersion).validate
            .getOrElse(ScopeConfig.empty)

        ParsedInfoString(true, modifiers, scopeConfig, showWarningsOverride)

      case _: Parsed.Failure =>
        // Parse failure - treat as non-scala passthrough
        ParsedInfoString(false, Set(Modifier.Passthrough), ScopeConfig.empty)
