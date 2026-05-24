package marklit.model

import java.nio.file.Path

/** Result of compiling a code block.
  *
  * @param classFilesDir
  *   directory holding the per-block class files emitted by dotc, when the
  *   compile succeeded and the underlying compiler retained them. Used by the
  *   executor to skip a redundant recompile on the same inputs. `None` for
  *   synthesized results (cached entries that don't carry classes, expected-
  *   failure stand-ins, etc.).
  */
final case class CompileResult(
    success: Boolean,
    diagnostics: List[ScalaDiagnostic],
    classFilesDir: Option[Path] = None
):
  def errors: List[ScalaDiagnostic] =
    diagnostics.filter(_.severity == DiagnosticSeverity.Error)

  def warnings: List[ScalaDiagnostic] =
    diagnostics.filter(_.severity == DiagnosticSeverity.Warning)

/** Result of executing compiled code */
final case class ExecutionResult(
    output: String,
    values: Map[String, String] // variable name -> rendered value
)
