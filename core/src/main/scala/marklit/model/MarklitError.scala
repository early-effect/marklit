package marklit.model

/** Compilation diagnostic from the Scala compiler */
final case class ScalaDiagnostic(
    severity: DiagnosticSeverity,
    message: String,
    line: Int,
    column: Int,
    file: Option[String] = None
):
  def pretty: String =
    val loc = file.map(f => s"$f:$line:$column: ").getOrElse(s"$line:$column: ")
    s"$loc${severity.toString.toLowerCase}: $message"

enum DiagnosticSeverity:
  case Error, Warning, Info

/** Typed error hierarchy for marklit operations */
enum MarklitError:
  case ParseError(location: Location, message: String)
  case CompileError(diagnostics: List[ScalaDiagnostic])
  case RuntimeError(exception: Throwable, output: String)
  case ValidationError(location: Location, message: String)

  def pretty: String = this match
    case ParseError(loc, msg)     => s"Parse error at ${loc.pretty}: $msg"
    case CompileError(diags)      => diags.map(_.pretty).mkString("\n")
    case RuntimeError(ex, output) =>
      s"Runtime error: ${ex.getMessage}\nOutput: $output"
    case ValidationError(loc, msg) => s"Validation error at ${loc.pretty}: $msg"
