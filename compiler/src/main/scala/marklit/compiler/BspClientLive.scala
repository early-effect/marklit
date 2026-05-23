package marklit.compiler

import marklit.model.*
import zio.*
import zio.json.*

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

import ch.epfl.scala.bsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher

/** JSON codec for BSP connection files */
private final case class BspConnectionJson(
    name: String,
    argv: List[String],
    version: String,
    bspVersion: String,
    languages: List[String]
)

private object BspConnectionJson:
  given JsonDecoder[BspConnectionJson] =
    DeriveJsonDecoder.gen[BspConnectionJson]

/** Live implementation of BspClient */
final case class BspClientLive() extends BspClient:

  override def discover(
      workspaceRoot: Path
  ): IO[MarklitError, Vector[BspServerConfig]] =
    val bspDir = workspaceRoot.resolve(".bsp")
    ZIO
      .attempt {
        if !Files.exists(bspDir) || !Files.isDirectory(bspDir) then Vector.empty
        else
          Files
            .list(bspDir)
            .filter(p => p.toString.endsWith(".json"))
            .toList
            .asScala
            .toVector
            .flatMap { path =>
              val content = Files.readString(path)
              content.fromJson[BspConnectionJson].toOption.map { json =>
                BspServerConfig(
                  name = json.name,
                  argv = json.argv.toVector,
                  version = json.version,
                  bspVersion = json.bspVersion,
                  languages = json.languages.toVector
                )
              }
            }
      }
      .mapError(e =>
        MarklitError.BspConnectionError(
          s"Failed to discover BSP servers: ${e.getMessage}",
          Some(e)
        )
      )

  override def connect(
      workspaceRoot: Path,
      details: BspServerConfig
  ): IO[MarklitError, BspConnection] =
    ZIO
      .attempt {
        // Start the BSP server process
        val processBuilder = new ProcessBuilder(details.argv.asJava)
          .directory(workspaceRoot.toFile)
          .redirectErrorStream(false)

        val process = processBuilder.start()

        // Create the JSON-RPC launcher with combined server interface
        val localClient = new MarklistBuildClient()
        val launcher = new Launcher.Builder[CombinedBuildServer]()
          .setLocalService(localClient)
          .setRemoteInterface(classOf[CombinedBuildServer])
          .setInput(process.getInputStream)
          .setOutput(process.getOutputStream)
          .create()

        // Start listening in background
        launcher.startListening()

        val server = launcher.getRemoteProxy

        // Initialize the connection
        val initParams = new InitializeBuildParams(
          "marklit",
          "0.1.0",
          "2.1.0", // BSP version
          workspaceRoot.toUri.toString,
          new BuildClientCapabilities(java.util.List.of("scala"))
        )

        val initResult =
          server.buildInitialize(initParams).get(120, TimeUnit.SECONDS)
        server.onBuildInitialized()

        BspConnection(process, server, initResult)
      }
      .mapError(e =>
        MarklitError.BspConnectionError(
          s"Failed to connect to BSP server: ${e.getMessage}",
          Some(e)
        )
      )

  override def buildTargets(
      conn: BspConnection
  ): IO[MarklitError, Vector[BuildTarget]] =
    ZIO
      .attempt {
        val result =
          conn.server.workspaceBuildTargets().get(120, TimeUnit.SECONDS)
        result.getTargets.asScala.toVector
      }
      .mapError(e =>
        MarklitError.BspProtocolError(
          s"Failed to get build targets: ${e.getMessage}"
        )
      )

  override def dependencyClasspath(
      conn: BspConnection,
      target: BuildTargetIdentifier
  ): IO[MarklitError, Vector[String]] =
    ZIO
      .attempt {
        val params = new DependencySourcesParams(java.util.List.of(target))
        val result = conn.server
          .buildTargetDependencySources(params)
          .get(30, TimeUnit.SECONDS)
        result.getItems.asScala.toVector.flatMap { item =>
          item.getSources.asScala.toVector
        }
      }
      .mapError(e =>
        MarklitError.BspProtocolError(
          s"Failed to get dependency classpath: ${e.getMessage}"
        )
      )

  override def scalacOptions(
      conn: BspConnection,
      target: BuildTargetIdentifier
  ): IO[MarklitError, ScalacOptionsItem] =
    ZIO
      .attempt {
        val params = new ScalacOptionsParams(java.util.List.of(target))
        val result =
          conn.server.buildTargetScalacOptions(params).get(30, TimeUnit.SECONDS)
        result.getItems.asScala.headOption.getOrElse {
          throw new RuntimeException(
            s"No scalac options found for target ${target.getUri}"
          )
        }
      }
      .mapError(e =>
        MarklitError.BspProtocolError(
          s"Failed to get scalac options: ${e.getMessage}"
        )
      )

  override def disconnect(conn: BspConnection): UIO[Unit] =
    ZIO.attempt {
      conn.server.buildShutdown().get(10, TimeUnit.SECONDS)
      conn.server.onBuildExit()
      conn.process.destroyForcibly()
    }.ignore

object BspClientLive:
  val layer: ULayer[BspClient] = ZLayer.succeed(BspClientLive())

/** Minimal BSP client implementation for receiving server notifications */
private class MarklistBuildClient extends BuildClient:
  override def onBuildShowMessage(params: ShowMessageParams): Unit = ()
  override def onBuildLogMessage(params: LogMessageParams): Unit = ()
  override def onBuildTaskStart(params: TaskStartParams): Unit = ()
  override def onBuildTaskProgress(params: TaskProgressParams): Unit = ()
  override def onBuildTaskFinish(params: TaskFinishParams): Unit = ()
  override def onBuildPublishDiagnostics(
      params: PublishDiagnosticsParams
  ): Unit = ()
  override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit = ()
  override def onRunPrintStdout(params: PrintParams): Unit = ()
  override def onRunPrintStderr(params: PrintParams): Unit = ()
