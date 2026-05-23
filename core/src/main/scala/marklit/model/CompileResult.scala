package marklit.model

/** Result of compiling a code block */
final case class CompileResult(
    success: Boolean,
    diagnostics: List[ScalaDiagnostic],
    bytecode: Option[Array[Byte]] = None
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
