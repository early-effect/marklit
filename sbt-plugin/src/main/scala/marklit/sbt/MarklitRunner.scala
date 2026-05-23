package marklit.sbt

import sbt._
import scala.sys.process._

/** Runner for marklit operations - thin wrapper around marklit CLI.
  */
object MarklitRunner {

  /** Run marklit CLI with given arguments. Returns exit code.
    */
  def run(
      marklitJar: File,
      args: Seq[String],
      log: Logger
  ): Int = {
    val cmd = Seq("java", "-jar", marklitJar.getAbsolutePath) ++ args

    log.debug(s"[marklit] Running: ${cmd.mkString(" ")}")

    val exitCode = Process(cmd) ! ProcessLogger(
      out => log.info(s"[marklit] $out"),
      err => log.error(s"[marklit] $err")
    )

    exitCode
  }

  private def scalaVersionArg(scalaVersion: String): Seq[String] =
    Seq("--scala-version", scalaVersion)

  private def classpathArg(classpath: Seq[File]): Seq[String] =
    if (classpath.nonEmpty)
      Seq(
        "--classpath",
        classpath.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator)
      )
    else Seq.empty

  /** Run marklit in check mode.
    */
  def check(
      marklitJar: File,
      sources: Seq[File],
      classpath: Seq[File],
      scalaVersion: String,
      verbose: Boolean,
      log: Logger
  ): Int = {
    val args = Seq("--check") ++
      scalaVersionArg(scalaVersion) ++
      classpathArg(classpath) ++
      (if (verbose) Seq("--verbose") else Seq.empty) ++
      sources.map(_.getAbsolutePath)

    run(marklitJar, args, log)
  }

  /** Run marklit to generate output.
    */
  def generate(
      marklitJar: File,
      sources: Seq[File],
      outputDir: File,
      classpath: Seq[File],
      scalaVersion: String,
      showVersion: Boolean,
      verbose: Boolean,
      log: Logger
  ): Int = {
    val args = Seq("--out", outputDir.getAbsolutePath) ++
      scalaVersionArg(scalaVersion) ++
      classpathArg(classpath) ++
      (if (showVersion) Seq.empty else Seq("--no-show-version")) ++
      (if (verbose) Seq("--verbose") else Seq.empty) ++
      sources.map(_.getAbsolutePath)

    run(marklitJar, args, log)
  }
}
