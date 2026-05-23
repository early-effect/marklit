package marklit.compiler

import marklit.model.*
import zio.*
import zio.test.*

object BspIntegrationSpec extends ZIOSpecDefault:

  /** BspClient that uses host sbt launcher against the mounted container
    * workspace
    */
  class HostSbtBspClient(delegate: BspClientLive) extends BspClient:
    override def discover(
        workspaceRoot: java.nio.file.Path
    ): IO[MarklitError, Vector[BspServerConfig]] =
      delegate.discover(workspaceRoot).map { configs =>
        configs.map { config =>
          // The argv from container points to container's java/sbt paths
          // We need to use host's sbt launcher with -bsp flag
          // sbt generates: java -cp sbt-launch.jar ... -bsp
          // We can use the simpler approach: sbt's --client mode or direct launcher
          val hostArgv = Vector(
            "java",
            "-Xms100m",
            "-Xmx100m",
            "-Dsbt.log.noformat=true",
            "-jar",
            sys.props.getOrElse("sbt.launcher", findSbtLauncher),
            "-bsp"
          )
          config.copy(argv = hostArgv)
        }
      }

    private def findSbtLauncher: String =
      // Try common locations
      val candidates = Vector(
        sys.env.get("SBT_HOME").map(_ + "/bin/sbt-launch.jar"),
        Some(
          sys.props.getOrElse(
            "user.home",
            ""
          ) + "/.sdkman/candidates/sbt/current/bin/sbt-launch.jar"
        ),
        Some("/usr/share/sbt/bin/sbt-launch.jar")
      ).flatten
      candidates
        .find(p => java.nio.file.Files.exists(java.nio.file.Paths.get(p)))
        .getOrElse(throw new RuntimeException("Could not find sbt-launch.jar"))

    override def connect(
        workspaceRoot: java.nio.file.Path,
        details: BspServerConfig
    ) =
      delegate.connect(workspaceRoot, details)

    override def buildTargets(conn: BspConnection) =
      delegate.buildTargets(conn)

    override def dependencyClasspath(
        conn: BspConnection,
        target: ch.epfl.scala.bsp4j.BuildTargetIdentifier
    ) =
      delegate.dependencyClasspath(conn, target)

    override def scalacOptions(
        conn: BspConnection,
        target: ch.epfl.scala.bsp4j.BuildTargetIdentifier
    ) =
      delegate.scalacOptions(conn, target)

    override def disconnect(conn: BspConnection) =
      delegate.disconnect(conn)

  val hostBspClientLayer: ULayer[BspClient] =
    ZLayer.succeed(new HostSbtBspClient(BspClientLive()))

  def spec = suite("BSP Integration")(
    suite("Container setup")(
      test("container starts and generates BSP config") {
        for
          container <- ZIO.service[SbtBspContainer]
          bspDir = container.workspace.resolve(".bsp")
          exists <- ZIO.attempt(java.nio.file.Files.exists(bspDir))
          files <- ZIO.attempt {
            java.nio.file.Files
              .list(bspDir)
              .toArray
              .map(_.toString)
              .filter(_.endsWith(".json"))
              .toVector
          }
        yield assertTrue(exists, files.nonEmpty)
      },

      test("discovers BSP server from container workspace") {
        for
          container <- ZIO.service[SbtBspContainer]
          servers <- BspClient.discover(container.workspace)
        yield assertTrue(
          servers.nonEmpty,
          servers.exists(_.name == "sbt")
        )
      },

      test("can execute sbt compile in container") {
        for
          container <- ZIO.service[SbtBspContainer]
          output <- ZIO.attempt(container.exec("sbt", "--batch", "compile"))
        yield assertTrue(output.contains("success"))
      }
    ).provide(
      SbtBspContainer.default,
      BspClientLive.layer
    ) @@ TestAspect.timeout(2.minutes) @@ TestAspect.withLiveClock,

    // BSP connection tests against the actual marklit project
    suite("BSP connection")(
      test("connects to BSP server and retrieves build targets") {
        val workspaceRoot = java.nio.file.Paths.get(sys.props("user.dir"))
        for
          servers <- BspClient.discover(workspaceRoot)
          sbt <- ZIO
            .fromOption(servers.find(_.name == "sbt"))
            .orElseFail(MarklitError.BspConnectionError("sbt not found", None))
          _ <- ZIO.logInfo(s"Connecting to BSP at ${workspaceRoot}")
          conn <- BspClient.connect(workspaceRoot, sbt)
          targets <- BspClient
            .buildTargets(conn)
            .ensuring(BspClient.disconnect(conn))
          _ <- ZIO.logInfo(
            s"Got ${targets.size} build targets: ${targets.map(_.getDisplayName)}"
          )
        yield assertTrue(
          targets.nonEmpty,
          targets.exists(t =>
            Option(t.getDisplayName).exists(_.contains("compiler"))
          )
        )
      },

      test("retrieves scalac options with classpath") {
        val workspaceRoot = java.nio.file.Paths.get(sys.props("user.dir"))
        for
          servers <- BspClient.discover(workspaceRoot)
          sbt <- ZIO
            .fromOption(servers.find(_.name == "sbt"))
            .orElseFail(MarklitError.BspConnectionError("sbt not found", None))
          conn <- BspClient.connect(workspaceRoot, sbt)
          targets <- BspClient.buildTargets(conn)
          scalaTarget <- ZIO
            .fromOption(
              targets.find(t =>
                t.getLanguageIds.contains("scala") &&
                  !Option(t.getDisplayName)
                    .exists(_.toLowerCase.contains("test"))
              )
            )
            .orElseFail(
              MarklitError.BspConnectionError("No Scala target found", None)
            )
          _ <- ZIO.logInfo(
            s"Getting scalac options for: ${scalaTarget.getDisplayName}"
          )
          scalacOpts <- BspClient
            .scalacOptions(conn, scalaTarget.getId)
            .ensuring(BspClient.disconnect(conn))
        yield assertTrue(
          scalacOpts.getClasspath != null,
          !scalacOpts.getClasspath.isEmpty,
          scalacOpts.getClasspath.toString.contains("zio")
        )
      }
    ).provide(BspClientLive.layer)
      @@ TestAspect.timeout(90.seconds)
      @@ TestAspect.withLiveClock
  ) @@ TestAspect.sequential
