package marklit.mill

/** Minimal JSON encoder for daemon-RPC requests, plus a tiny extractor for the
  * response fields we care about. Hand-rolled to avoid pulling a JSON library
  * onto the Mill plugin classpath. Mirrors the sbt-plugin twin in
  * `marklit.sbt.MarklitJson`; if either needs to grow much past trivial shapes,
  * swap in a real JSON dep instead of duplicating again.
  */
private[mill] object MarklitJson:

  def compileDocumentRequest(
      inputFiles: Seq[String],
      outputDir: Option[String],
      verbose: Boolean,
      check: Boolean,
      showVersionInOutput: Boolean,
      showWarningsInOutput: Boolean,
      classpath: Option[String],
      classpath2: Option[String],
      classpath3: Option[String],
      scalaVersion: Option[String],
      cacheDir: Option[String],
      pageScope: Boolean
  ): String =
    val params = new StringBuilder
    params.append("{")
    appendField(params, "inputFiles", arr(inputFiles.map(str)))
    params.append(",")
    appendField(params, "outputDir", outputDir.map(str).getOrElse("null"))
    params.append(",")
    appendField(params, "verbose", bool(verbose))
    params.append(",")
    appendField(params, "check", bool(check))
    params.append(",")
    appendField(params, "showVersionInOutput", bool(showVersionInOutput))
    params.append(",")
    appendField(params, "showWarningsInOutput", bool(showWarningsInOutput))
    classpath.foreach { v =>
      params.append(",")
      appendField(params, "classpath", str(v))
    }
    classpath2.foreach { v =>
      params.append(",")
      appendField(params, "classpath2", str(v))
    }
    classpath3.foreach { v =>
      params.append(",")
      appendField(params, "classpath3", str(v))
    }
    scalaVersion.foreach { v =>
      params.append(",")
      appendField(params, "scalaVersion", str(v))
    }
    cacheDir.foreach { v =>
      params.append(",")
      appendField(params, "cacheDir", str(v))
    }
    params.append(",")
    appendField(params, "pageScope", bool(pageScope))
    params.append("}")

    s"""{"method":"compile-document","params":${params.toString}}"""

  def clearCacheRequest(cacheDir: String): String =
    val params = new StringBuilder
    params.append("{")
    appendField(params, "cacheDir", str(cacheDir))
    params.append("}")
    s"""{"method":"clear-cache","params":${params.toString}}"""

  def shutdownRequest: String = """{"method":"shutdown"}"""

  def extractStatus(line: String): Option[String] =
    extractStringField(line, "status")

  def extractMessage(line: String): Option[String] =
    extractStringField(line, "message")

  private def appendField(
      sb: StringBuilder,
      key: String,
      rawValue: String
  ): Unit =
    sb.append('"').append(key).append('"').append(':').append(rawValue)

  private def str(s: String): String =
    val sb = new StringBuilder(s.length + 2)
    sb.append('"')
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      c match
        case '"'             => sb.append("\\\"")
        case '\\'            => sb.append("\\\\")
        case '\n'            => sb.append("\\n")
        case '\r'            => sb.append("\\r")
        case '\t'            => sb.append("\\t")
        case ch if ch < 0x20 => sb.append(f"\\u${ch.toInt}%04x")
        case ch              => sb.append(ch)
      i += 1
    sb.append('"')
    sb.toString

  private def bool(b: Boolean): String = if b then "true" else "false"

  private def arr(parts: Seq[String]): String = parts.mkString("[", ",", "]")

  private def extractStringField(line: String, key: String): Option[String] =
    val needle = "\"" + key + "\":\""
    val idx = line.indexOf(needle)
    if idx < 0 then None
    else
      val start = idx + needle.length
      val end = line.indexOf('"', start)
      if end < 0 then None else Some(line.substring(start, end))
