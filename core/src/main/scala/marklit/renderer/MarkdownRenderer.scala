package marklit.renderer

import marklit.model.*
import marklit.parser.*
import marklit.processor.*
import marklit.results.*

/** Configuration for markdown rendering */
final case class RenderConfig(
    showLineNumbers: Boolean = false,
    showCompileErrors: Boolean = true,
    showRuntimeErrors: Boolean = true,
    showCompileWarnings: Boolean = true,
    errorPrefix: String = "// ",
    outputFenceLanguage: String = "", // empty = plain code block
    includeSourceComments: Boolean = false,
    showScalaVersion: Boolean = true // show which Scala version produced output
)

object RenderConfig:
  val default: RenderConfig = RenderConfig()

/** Renders processed markdown documents back to markdown with evaluated outputs
  */
object MarkdownRenderer:

  /** Render a processed document back to markdown */
  def render(
      document: ParsedDocument,
      results: DocumentResult,
      config: RenderConfig = RenderConfig.default
  ): String =
    val sb = new StringBuilder
    val blockResults = results.blockResults.map(br => br.block -> br).toMap

    document.segments.foreach {
      case MarkdownSegment.Text(content, _) =>
        sb.append(content)

      case MarkdownSegment.Code(block) =>
        renderCodeBlock(sb, block, blockResults.get(block), config)
    }

    sb.toString

  private def renderCodeBlock(
      sb: StringBuilder,
      block: CodeBlock,
      result: Option[BlockResult],
      config: RenderConfig
  ): Unit =
    // Passthrough blocks render as-is (but might have different fence language)
    if block.isPassthrough then
      sb.append("```\n")
      sb.append(block.code)
      if !block.code.endsWith("\n") then sb.append("\n")
      sb.append("```\n")
      return

    // Invisible blocks are completely hidden
    if !block.showCode then return

    // Render the code block
    sb.append("```scala\n")

    if config.showLineNumbers then
      val lines = block.code.split("\n", -1)
      val width = lines.length.toString.length
      lines.zipWithIndex.foreach { case (line, idx) =>
        val lineNum = (idx + 1).toString.reverse.padTo(width, ' ').reverse
        sb.append(s"$lineNum | $line\n")
      }
    else
      sb.append(block.code)
      if !block.code.endsWith("\n") then sb.append("\n")

    sb.append("```\n")

    // Render output/errors based on block type and showOutput setting
    result.foreach { br =>
      // Cross-version (`scala=shared` / `scala=shared-{mv}`) blocks have one
      // BlockExecution per applicable version. Render dedup'd or per-version
      // labeled — the helper picks the format.
      if br.crossExecutions.nonEmpty then
        renderCrossExecutions(sb, block, br.crossExecutions, config)
      else
        val ver = br.effectiveScalaVersion
        renderSingleExecution(sb, block, br, ver, config)
    }

  private def renderSingleExecution(
      sb: StringBuilder,
      block: CodeBlock,
      br: BlockResult,
      ver: Option[String],
      config: RenderConfig
  ): Unit =
      // For fail blocks, show the expected compilation errors
      if block.expectsFailure then
        br.compileResult.foreach { cr =>
          if cr.diagnostics.nonEmpty && config.showCompileErrors then
            renderDiagnostics(
              sb,
              cr.diagnostics.filter(_.severity == DiagnosticSeverity.Error),
              config,
              ver
            )
        }
      // For warn blocks, show the expected warnings
      else if block.expectsWarnings then
        br.compileResult.foreach { cr =>
          val warnings =
            cr.diagnostics.filter(_.severity == DiagnosticSeverity.Warning)
          if warnings.nonEmpty && config.showCompileErrors then
            renderDiagnostics(sb, warnings, config, ver)
        }
        // Also show normal output if any
        if block.showOutput then
          br.executionOutput.filter(_.nonEmpty).foreach { output =>
            renderOutput(sb, output, config, ver)
          }
      // For crash blocks, show the exception
      else if block.expectsCrash then
        br.error.foreach {
          case MarklitError.RuntimeError(ex, output)
              if config.showRuntimeErrors =>
            if output.nonEmpty then renderOutput(sb, output, config, ver)
            sb.append("```\n")
            if config.showScalaVersion then
              ver.foreach(v => sb.append(s"// Scala $v\n"))
            sb.append(
              s"${config.errorPrefix}Exception: ${ex.getClass.getSimpleName}: ${ex.getMessage}\n"
            )
            sb.append("```\n")
          case _ => ()
        }
      // For normal blocks, show output if configured
      else if block.showOutput then
        // Handle compile errors
        if !br.compileResult.exists(_.success) && config.showCompileErrors then
          br.compileResult.foreach { cr =>
            renderDiagnostics(sb, cr.errors, config, ver)
          }
        // Handle execution output (and compile warnings if enabled)
        else
          if block.showWarnings(config.showCompileWarnings) then
            br.compileResult.foreach { cr =>
              val warnings =
                cr.diagnostics.filter(_.severity == DiagnosticSeverity.Warning)
              if warnings.nonEmpty then
                renderDiagnostics(sb, warnings, config, ver)
            }
          br.executionOutput.filter(_.nonEmpty).foreach { output =>
            renderOutput(sb, output, config, ver)
          }

  private def renderOutput(
      sb: StringBuilder,
      output: String,
      config: RenderConfig,
      scalaVersion: Option[String]
  ): Unit =
    renderOutputWithHeader(
      sb,
      output,
      config,
      scalaVersion.filter(_ => config.showScalaVersion).map(v => s"// Scala $v")
    )

  /** Emit a fenced output block with an optional pre-output header line (used
    * by both per-version `// Scala X` labels and the `// All cross versions:`
    * dedup'd header).
    */
  private def renderOutputWithHeader(
      sb: StringBuilder,
      output: String,
      config: RenderConfig,
      header: Option[String]
  ): Unit =
    if config.outputFenceLanguage.nonEmpty then
      sb.append(s"```${config.outputFenceLanguage}\n")
    else sb.append("```\n")

    header.foreach(h => sb.append(s"$h\n"))

    sb.append(output)
    if !output.endsWith("\n") then sb.append("\n")
    sb.append("```\n")

  /** Render a `scala=shared` / `scala=shared-{mv}` block's per-version
    * executions. When every execution succeeded with identical stdout, render
    * a single output block headed by `// All cross versions: Scala v1, v2, …`.
    * Otherwise emit one labeled output (or compile-error / runtime-error)
    * block per execution, in the order versions appear in [[crossExecutions]].
    */
  private def renderCrossExecutions(
      sb: StringBuilder,
      block: CodeBlock,
      execs: Vector[BlockExecution],
      config: RenderConfig
  ): Unit =
    if !block.showOutput || execs.isEmpty then return

    val allCleanlyCompiled = execs.forall(_.compileResult.exists(_.success))
    val allNoError = execs.forall(_.error.isEmpty)
    val outputs = execs.map(_.executionOutput.getOrElse(""))
    val outputsAgree = outputs.distinct.size == 1

    if allCleanlyCompiled && allNoError && outputsAgree then
      val header =
        if config.showScalaVersion then
          val versions = execs.map(_.scalaVersion).mkString(", ")
          Some(s"// All cross versions: Scala $versions")
        else None
      val output = outputs.head
      if output.nonEmpty then renderOutputWithHeader(sb, output, config, header)
    else
      // Divergent: render per-version. Each execution gets either its compile
      // errors or its (possibly empty) execution output, both labeled.
      execs.foreach { x =>
        val ver = Some(x.scalaVersion)
        x.compileResult match
          case Some(cr) if !cr.success =>
            if config.showCompileErrors then
              renderDiagnostics(sb, cr.errors, config, ver)
          case _ =>
            if block.showWarnings(config.showCompileWarnings) then
              x.compileResult.foreach { cr =>
                val warnings =
                  cr.diagnostics.filter(_.severity == DiagnosticSeverity.Warning)
                if warnings.nonEmpty then
                  renderDiagnostics(sb, warnings, config, ver)
              }
            x.executionOutput.filter(_.nonEmpty).foreach { out =>
              renderOutput(sb, out, config, ver)
            }
            x.error.foreach {
              case MarklitError.RuntimeError(ex, runOut)
                  if config.showRuntimeErrors =>
                if runOut.nonEmpty then renderOutput(sb, runOut, config, ver)
                sb.append("```\n")
                if config.showScalaVersion then
                  sb.append(s"// Scala ${x.scalaVersion}\n")
                sb.append(
                  s"${config.errorPrefix}Exception: ${ex.getClass.getSimpleName}: ${ex.getMessage}\n"
                )
                sb.append("```\n")
              case _ => ()
            }
      }

  private def renderDiagnostics(
      sb: StringBuilder,
      diagnostics: List[ScalaDiagnostic],
      config: RenderConfig,
      scalaVersion: Option[String]
  ): Unit =
    if diagnostics.isEmpty then return

    sb.append("```\n")
    // Show Scala version comment if enabled and version provided
    if config.showScalaVersion then
      scalaVersion.foreach { v =>
        sb.append(s"// Scala $v\n")
      }
    diagnostics.foreach { diag =>
      val severity = diag.severity match
        case DiagnosticSeverity.Error   => "error"
        case DiagnosticSeverity.Warning => "warning"
        case DiagnosticSeverity.Info    => "info"

      // Clean up the message - remove file paths, just show line/col
      val cleanMessage = diag.message
        .replaceAll("/tmp/[^:]+:", "")
        .trim

      sb.append(s"${config.errorPrefix}$severity: $cleanMessage\n")
    }
    sb.append("```\n")

  /** Render a document using merged results from multiple Scala version runs */
  def renderMerged(
      document: ParsedDocument,
      merged: MergedResults,
      config: RenderConfig = RenderConfig.default
  ): String =
    val sb = new StringBuilder

    document.segments.foreach {
      case MarkdownSegment.Text(content, _) =>
        sb.append(content)

      case MarkdownSegment.Code(block) =>
        val locationKey =
          s"${block.location.file}:${block.location.startLine}:${block.location.startColumn}"
        val blockResults =
          merged.blocksByLocation.getOrElse(locationKey, Vector.empty)
        renderCodeBlockMerged(sb, block, blockResults, config)
    }

    sb.toString

  private def renderCodeBlockMerged(
      sb: StringBuilder,
      block: CodeBlock,
      results: Vector[BlockResultEntry],
      config: RenderConfig
  ): Unit =
    // Passthrough blocks render as-is
    if block.isPassthrough then
      sb.append("```\n")
      sb.append(block.code)
      if !block.code.endsWith("\n") then sb.append("\n")
      sb.append("```\n")
      return

    // Invisible blocks are completely hidden
    if !block.showCode then return

    // Render the code block
    sb.append("```scala\n")

    if config.showLineNumbers then
      val lines = block.code.split("\n", -1)
      val width = lines.length.toString.length
      lines.zipWithIndex.foreach { case (line, idx) =>
        val lineNum = (idx + 1).toString.reverse.padTo(width, ' ').reverse
        sb.append(s"$lineNum | $line\n")
      }
    else
      sb.append(block.code)
      if !block.code.endsWith("\n") then sb.append("\n")

    sb.append("```\n")

    // Render output/errors from each Scala version if should be shown
    if block.showOutput && results.nonEmpty then
      val effectiveShowWarnings = block.showWarnings(config.showCompileWarnings)
      results.foreach { entry =>
        val versionTag =
          if config.showScalaVersion then Some(entry.scalaVersion) else None

        // Handle compile errors
        if entry.compileErrors.exists(
            _.severity == "error"
          ) && config.showCompileErrors
        then
          renderDiagnosticsFromEntries(
            sb,
            entry.compileErrors.filter(_.severity == "error"),
            config,
            versionTag
          )
        // Handle execution output (and compile warnings if enabled)
        else
          if effectiveShowWarnings then
            val warnings =
              entry.compileErrors.filter(_.severity == "warning")
            if warnings.nonEmpty then
              renderDiagnosticsFromEntries(sb, warnings, config, versionTag)
          entry.executionOutput.filter(_.nonEmpty).foreach { output =>
            renderOutput(sb, output, config, versionTag)
          }

          // Handle runtime errors
          entry.runtimeError.foreach { err =>
            if config.showRuntimeErrors then
              sb.append("```\n")
              if config.showScalaVersion then
                sb.append(s"// Scala ${entry.scalaVersion}\n")
              sb.append(s"${config.errorPrefix}$err\n")
              sb.append("```\n")
          }
      }

  private def renderDiagnosticsFromEntries(
      sb: StringBuilder,
      diagnostics: List[DiagnosticEntry],
      config: RenderConfig,
      scalaVersion: Option[String]
  ): Unit =
    if diagnostics.isEmpty then return

    sb.append("```\n")
    scalaVersion.foreach { v =>
      sb.append(s"// Scala $v\n")
    }
    diagnostics.foreach { diag =>
      val cleanMessage = diag.message
        .replaceAll("/tmp/[^:]+:", "")
        .trim

      sb.append(s"${config.errorPrefix}${diag.severity}: $cleanMessage\n")
    }
    sb.append("```\n")
