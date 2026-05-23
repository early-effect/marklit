package marklit.compiler

import marklit.model.*
import zio.*
import zio.test.*

import java.nio.file.{Path, Paths}

object BspClientSpec extends ZIOSpecDefault:

  // Use this project's own workspace for testing
  val workspaceRoot: Path = Paths.get(sys.props("user.dir"))

  def spec = suite("BspClient")(
    suite("discover")(
      test("finds BSP servers in .bsp directory") {
        for servers <- BspClient.discover(workspaceRoot)
        yield assertTrue(
          servers.nonEmpty,
          servers.exists(_.name == "sbt")
        )
      },

      test("returns empty vector when .bsp directory doesn't exist") {
        val nonExistentPath = Paths.get("/tmp/non-existent-project-12345")
        for servers <- BspClient.discover(nonExistentPath)
        yield assertTrue(servers.isEmpty)
      },

      test("parses connection details correctly") {
        for
          servers <- BspClient.discover(workspaceRoot)
          sbt <- ZIO
            .fromOption(servers.find(_.name == "sbt"))
            .orElseFail(MarklitError.BspConnectionError("sbt not found", None))
        yield assertTrue(
          sbt.languages.contains("scala"),
          sbt.argv.nonEmpty,
          sbt.bspVersion.nonEmpty
        )
      }
    ),

    suite("connect")(
      test("connects to sbt BSP server and retrieves build targets") {
        for
          servers <- BspClient.discover(workspaceRoot)
          sbt <- ZIO
            .fromOption(servers.find(_.name == "sbt"))
            .orElseFail(MarklitError.BspConnectionError("sbt not found", None))
          conn <- BspClient.connect(workspaceRoot, sbt)
          targets <- BspClient
            .buildTargets(conn)
            .ensuring(BspClient.disconnect(conn))
        yield assertTrue(
          targets.nonEmpty,
          targets.exists(t => t.getDisplayName != null)
        )
      } @@ TestAspect.timeout(60.seconds) @@ TestAspect.withLiveClock,

      test("retrieves scalac options for a target") {
        for
          servers <- BspClient.discover(workspaceRoot)
          sbt <- ZIO
            .fromOption(servers.find(_.name == "sbt"))
            .orElseFail(MarklitError.BspConnectionError("sbt not found", None))
          conn <- BspClient.connect(workspaceRoot, sbt)
          targets <- BspClient.buildTargets(conn)
          // Find a Scala target (not a test target for simplicity)
          scalaTarget <- ZIO
            .fromOption(
              targets.find(t =>
                t.getLanguageIds.contains("scala") &&
                  !t.getDisplayName.contains("test")
              )
            )
            .orElseFail(
              MarklitError.BspConnectionError("No Scala target found", None)
            )
          scalacOpts <- BspClient
            .scalacOptions(conn, scalaTarget.getId)
            .ensuring(BspClient.disconnect(conn))
        yield assertTrue(
          scalacOpts.getClasspath != null,
          !scalacOpts.getClasspath.isEmpty
        )
      } @@ TestAspect.timeout(60.seconds) @@ TestAspect.withLiveClock
    )
  ).provide(BspClientLive.layer) @@ TestAspect.sequential
