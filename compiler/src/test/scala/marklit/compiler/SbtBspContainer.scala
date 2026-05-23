package marklit.compiler

import zio.*
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.BindMode
import org.testcontainers.images.builder.ImageFromDockerfile

import java.nio.file.{Files, Path}
import java.time.Duration

/** Configuration for the sbt BSP test container */
final case class SbtBspContainerConfig(
    scalaVersion: String = "3.3.3",
    sbtVersion: String = "1.10.6",
    zioVersion: String = "2.1.24"
)

object SbtBspContainerConfig:
  val default: ULayer[SbtBspContainerConfig] =
    ZLayer.succeed(SbtBspContainerConfig())

/** Test container running sbt with a sample project for BSP testing */
final case class SbtBspContainer(
    config: SbtBspContainerConfig,
    container: GenericContainer[?],
    workspaceDir: Path
):
  /** Start the container */
  def start: SbtBspContainer =
    container.start()
    this

  /** Stop the container and clean up workspace */
  def stop: UIO[Unit] =
    ZIO.succeed {
      try container.stop()
      catch case _: Exception => ()

      // Clean up temp workspace
      try
        Files
          .walk(workspaceDir)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p => Files.deleteIfExists(p))
      catch case _: Exception => ()
    }

  /** Get the workspace path (mounted in container) */
  def workspace: Path = workspaceDir

  /** Execute a command in the container */
  def exec(command: String*): String =
    println(s"[Container] Executing: ${command.mkString(" ")}")
    val result = container.execInContainer(command*)
    if result.getStdout.nonEmpty then
      println(s"[Container] stdout: ${result.getStdout.take(500)}")
    if result.getStderr.nonEmpty then
      println(s"[Container] stderr: ${result.getStderr.take(500)}")
    if result.getExitCode != 0 then
      throw new RuntimeException(
        s"Command failed (exit ${result.getExitCode}): ${command.mkString(" ")}\n${result.getStderr}"
      )
    result.getStdout

  /** Start the BSP server and return connection details */
  def startBspServer(): Unit =
    println("[Container] Generating BSP config...")
    // Generate BSP connection files - use bloopInstall which is faster than bspConfig
    exec("sbt", "--batch", "bspConfig")
    println("[Container] BSP config generated")

object SbtBspContainer:

  /** Create the workspace directory with a sample sbt project */
  private def createWorkspace(config: SbtBspContainerConfig): Path =
    val dir = Files.createTempDirectory("marklit-bsp-test-")

    // Create build.sbt
    Files.writeString(
      dir.resolve("build.sbt"),
      s"""|val scala3Version = "${config.scalaVersion}"
          |
          |lazy val root = project
          |  .in(file("."))
          |  .settings(
          |    name := "test-project",
          |    version := "0.1.0",
          |    scalaVersion := scala3Version,
          |    libraryDependencies += "dev.zio" %% "zio" % "${config.zioVersion}"
          |  )
          |""".stripMargin
    )

    // Create project/build.properties
    val projectDir = Files.createDirectories(dir.resolve("project"))
    Files.writeString(
      projectDir.resolve("build.properties"),
      s"sbt.version=${config.sbtVersion}"
    )

    // Create plugins.sbt for BSP support (sbt 1.x has built-in BSP)
    Files.writeString(
      projectDir.resolve("plugins.sbt"),
      ""
    )

    // Create a simple source file
    val srcDir = Files.createDirectories(dir.resolve("src/main/scala"))
    Files.writeString(
      srcDir.resolve("Main.scala"),
      """|import zio.*
         |
         |object Main extends ZIOAppDefault:
         |  def run = Console.printLine("Hello from test project!")
         |""".stripMargin
    )

    dir

  /** Create the Docker container with sbt installed */
  private def createContainer(
      workspaceDir: Path,
      config: SbtBspContainerConfig
  ): GenericContainer[?] =
    val dockerfile = new ImageFromDockerfile()
      .withDockerfileFromBuilder { builder =>
        builder
          .from("eclipse-temurin:21-jdk")
          .run("apt-get update && apt-get install -y curl bash")
          .run(
            s"curl -fL https://github.com/sbt/sbt/releases/download/v${config.sbtVersion}/sbt-${config.sbtVersion}.tgz | tar xz -C /opt"
          )
          .env("PATH", "/opt/sbt/bin:$PATH")
          .env("SBT_OPTS", "-Xmx2G -Xms512M")
          .workDir("/workspace")
          // Pre-warm sbt by running a simple command
          .run(
            "mkdir -p /tmp/sbt-warmup && cd /tmp/sbt-warmup && echo 'name := \"warmup\"' > build.sbt && sbt --batch exit && rm -rf /tmp/sbt-warmup"
          )
          .build()
      }

    val container: GenericContainer[?] = new GenericContainer(dockerfile)
    @annotation.nowarn("msg=deprecated")
    val _ = container.withFileSystemBind(
      workspaceDir.toString,
      "/workspace",
      BindMode.READ_WRITE
    )
    container.withCommand("tail", "-f", "/dev/null") // Keep container running
    container.withStartupTimeout(Duration.ofMinutes(10))
    container

  /** Create a container instance (not started) */
  def make(config: SbtBspContainerConfig): SbtBspContainer =
    val workspaceDir = createWorkspace(config)
    val container = createContainer(workspaceDir, config)
    SbtBspContainer(config, container, workspaceDir)

  /** ZLayer that manages the container lifecycle */
  val layer: ZLayer[SbtBspContainerConfig, Throwable, SbtBspContainer] =
    ZLayer.scoped {
      for
        config <- ZIO.service[SbtBspContainerConfig]
        container <- ZIO.acquireRelease(
          ZIO.attempt(make(config).start)
        )(_.stop)
        // Generate BSP config after container starts
        _ <- ZIO.attempt(container.startBspServer())
      yield container
    }

  /** Default layer with standard config */
  val default: ZLayer[Any, Throwable, SbtBspContainer] =
    SbtBspContainerConfig.default >>> layer
