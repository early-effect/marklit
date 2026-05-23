package marklit.sbt

import sbt._
import sbt.Keys._

object MarklitPlugin extends AutoPlugin {

  object autoImport {
    // Settings
    val marklitSourceDirectory =
      settingKey[File]("Directory containing markdown source files")
    val marklitTargetDirectory =
      settingKey[File]("Directory for generated markdown output")
    val marklitShowVersion =
      settingKey[Boolean]("Show Scala version in output blocks")
    val marklitVerbose = settingKey[Boolean]("Enable verbose output")

    // Tasks
    val marklitCompile =
      taskKey[Unit]("Compile and verify markdown code blocks")
    val marklitGenerate = taskKey[Seq[File]]("Generate output markdown files")
    val marklitClean = taskKey[Unit]("Clean marklit output directory")
  }

  import autoImport._

  override def requires: Plugins = plugins.JvmPlugin
  override def trigger: PluginTrigger = allRequirements

  // Extract the embedded CLI jar on first use
  private lazy val extractedJar: File = {
    val tempDir = IO.createTemporaryDirectory
    val jarFile = tempDir / "marklit-cli.jar"

    val resourceStream = getClass.getResourceAsStream("/marklit-cli.jar")
    if (resourceStream == null) {
      sys.error(
        "marklit-cli.jar not found in plugin resources. Plugin may not be packaged correctly."
      )
    }

    try {
      IO.transfer(resourceStream, jarFile)
    } finally {
      resourceStream.close()
    }

    // Mark for cleanup on JVM exit
    tempDir.deleteOnExit()
    jarFile.deleteOnExit()

    jarFile
  }

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    // Default settings
    marklitSourceDirectory := (Compile / sourceDirectory).value / "markdown",
    marklitTargetDirectory := target.value / "marklit",
    marklitShowVersion := true,
    marklitVerbose := false,

    // Compile task - check markdown files compile successfully
    marklitCompile := {
      val log = streams.value.log
      val sourceDir = marklitSourceDirectory.value
      val verbose = marklitVerbose.value
      // Get the project's full classpath (includes dependencies)
      val cp = (Compile / fullClasspath).value.files
      val scalaVer = scalaVersion.value

      if (!sourceDir.exists()) {
        log.info(s"[marklit] No source directory: $sourceDir")
      } else {
        val sources = (sourceDir ** "*.md").get
        if (sources.isEmpty) {
          log.info(s"[marklit] No markdown files in $sourceDir")
        } else {
          log.info(s"[marklit] Checking ${sources.size} file(s)...")

          val exitCode =
            MarklitRunner.check(
              extractedJar,
              sources,
              cp,
              scalaVer,
              verbose,
              log
            )
          if (exitCode != 0) {
            throw new MessageOnlyException(
              s"marklit compilation failed with exit code $exitCode"
            )
          }
        }
      }
    },

    // Generate task - render output markdown files
    marklitGenerate := {
      val log = streams.value.log
      val sourceDir = marklitSourceDirectory.value
      val targetDir = marklitTargetDirectory.value
      val showVersion = marklitShowVersion.value
      val verbose = marklitVerbose.value
      // Get the project's full classpath (includes dependencies)
      val cp = (Compile / fullClasspath).value.files
      val scalaVer = scalaVersion.value

      if (!sourceDir.exists()) {
        log.info(s"[marklit] No source directory: $sourceDir")
        Seq.empty
      } else {
        val sources = (sourceDir ** "*.md").get
        if (sources.isEmpty) {
          log.info(s"[marklit] No markdown files in $sourceDir")
          Seq.empty
        } else {
          IO.createDirectory(targetDir)
          log.info(s"[marklit] Generating ${sources.size} file(s)...")
          log.info(s"[marklit] Scala version: $scalaVer")

          val exitCode = MarklitRunner.generate(
            extractedJar,
            sources,
            targetDir,
            cp,
            scalaVer,
            showVersion,
            verbose,
            log
          )
          if (exitCode != 0) {
            throw new MessageOnlyException(
              s"marklit generation failed with exit code $exitCode"
            )
          }

          // Return generated files
          sources.map { source =>
            targetDir / source.getName
          }
        }
      }
    },

    // Clean task
    marklitClean := {
      val log = streams.value.log
      val targetDir = marklitTargetDirectory.value

      if (targetDir.exists()) {
        IO.delete(targetDir)
        log.info(s"[marklit] Cleaned: $targetDir")
      }
    }
  )
}
