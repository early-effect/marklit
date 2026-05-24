package marklit.sbt

/** Minimal JSON encoder for daemon-RPC requests, plus a tiny extractor for the
  * response fields we care about. Hand-rolled to avoid pulling a JSON library
  * onto the sbt-plugin classpath (sbt 1.x is on Scala 2.12).
  *
  * Only handles the shapes used by [[DaemonProtocol]] in the CLI. Don't
  * generalize this — if the protocol grows past trivial types, add a real JSON
  * dep.
  */
private[sbt] object MarklitJson {

  /** Build a `compile-document` request line. */
  def compileDocumentRequest(
      inputFiles: Seq[String],
      outputDir: Option[String],
      verbose: Boolean,
      check: Boolean,
      showVersionInOutput: Boolean,
      classpath: Option[String],
      classpath2: Option[String],
      classpath3: Option[String],
      scalaVersion: Option[String],
      cacheDir: Option[String]
  ): String = {
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
    if (classpath.isDefined) {
      params.append(",")
      appendField(params, "classpath", str(classpath.get))
    }
    if (classpath2.isDefined) {
      params.append(",")
      appendField(params, "classpath2", str(classpath2.get))
    }
    if (classpath3.isDefined) {
      params.append(",")
      appendField(params, "classpath3", str(classpath3.get))
    }
    if (scalaVersion.isDefined) {
      params.append(",")
      appendField(params, "scalaVersion", str(scalaVersion.get))
    }
    if (cacheDir.isDefined) {
      params.append(",")
      appendField(params, "cacheDir", str(cacheDir.get))
    }
    params.append("}")

    s"""{"method":"compile-document","params":${params.toString}}"""
  }

  /** `clear-cache` RPC body. Caller supplies the directory to wipe. */
  def clearCacheRequest(cacheDir: String): String = {
    val params = new StringBuilder
    params.append("{")
    appendField(params, "cacheDir", str(cacheDir))
    params.append("}")
    s"""{"method":"clear-cache","params":${params.toString}}"""
  }

  def shutdownRequest: String = """{"method":"shutdown"}"""

  /** Extract `status` from a response JSON line. Returns `None` if the line
    * isn't well-formed enough to find a `"status"` field.
    */
  def extractStatus(line: String): Option[String] =
    extractStringField(line, "status")

  /** Extract `message` from an error response. */
  def extractMessage(line: String): Option[String] =
    extractStringField(line, "message")

  private def appendField(
      sb: StringBuilder,
      key: String,
      rawValue: String
  ): Unit = {
    sb.append('"').append(key).append('"').append(':').append(rawValue)
    ()
  }

  private def str(s: String): String = {
    val sb = new StringBuilder(s.length + 2)
    sb.append('"')
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      c match {
        case '"'             => sb.append("\\\"")
        case '\\'            => sb.append("\\\\")
        case '\n'            => sb.append("\\n")
        case '\r'            => sb.append("\\r")
        case '\t'            => sb.append("\\t")
        case ch if ch < 0x20 =>
          sb.append("\\u%04x".format(ch.toInt))
        case ch => sb.append(ch)
      }
      i += 1
    }
    sb.append('"')
    sb.toString
  }

  private def bool(b: Boolean): String = if (b) "true" else "false"

  private def arr(parts: Seq[String]): String = parts.mkString("[", ",", "]")

  /** Naive `"key":"value"` extractor. Doesn't handle escapes inside the value
    * (good enough for `status: "ok" | "error"` and short messages the daemon
    * sends back).
    */
  private def extractStringField(line: String, key: String): Option[String] = {
    val needle = "\"" + key + "\":\""
    val idx = line.indexOf(needle)
    if (idx < 0) None
    else {
      val start = idx + needle.length
      val end = line.indexOf('"', start)
      if (end < 0) None else Some(line.substring(start, end))
    }
  }
}
