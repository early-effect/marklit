package marklit.compiler

import marklit.model.*
import zio.*

import java.nio.file.Path

import ch.epfl.scala.bsp4j.*

/** Combined interface for BuildServer with Scala and JVM extensions */
trait CombinedBuildServer
    extends BuildServer
    with ScalaBuildServer
    with JvmBuildServer

/** BSP connection details from a .bsp JSON file */
final case class BspServerConfig(
    name: String,
    argv: Vector[String],
    version: String,
    bspVersion: String,
    languages: Vector[String]
)

/** Active BSP connection with server process and client */
final case class BspConnection(
    process: Process,
    server: CombinedBuildServer,
    initResult: InitializeBuildResult
)

/** BSP client for discovering and connecting to build servers */
trait BspClient:
  /** Discover available BSP servers in the workspace */
  def discover(workspaceRoot: Path): IO[MarklitError, Vector[BspServerConfig]]

  /** Connect to a BSP server */
  def connect(
      workspaceRoot: Path,
      details: BspServerConfig
  ): IO[MarklitError, BspConnection]

  /** Get build targets from connected server */
  def buildTargets(conn: BspConnection): IO[MarklitError, Vector[BuildTarget]]

  /** Get classpath for a build target */
  def dependencyClasspath(
      conn: BspConnection,
      target: BuildTargetIdentifier
  ): IO[MarklitError, Vector[String]]

  /** Get scalac options for a build target */
  def scalacOptions(
      conn: BspConnection,
      target: BuildTargetIdentifier
  ): IO[MarklitError, ScalacOptionsItem]

  /** Disconnect from server */
  def disconnect(conn: BspConnection): UIO[Unit]

object BspClient:
  def discover(
      workspaceRoot: Path
  ): ZIO[BspClient, MarklitError, Vector[BspServerConfig]] =
    ZIO.serviceWithZIO[BspClient](_.discover(workspaceRoot))

  def connect(
      workspaceRoot: Path,
      details: BspServerConfig
  ): ZIO[BspClient, MarklitError, BspConnection] =
    ZIO.serviceWithZIO[BspClient](_.connect(workspaceRoot, details))

  def buildTargets(
      conn: BspConnection
  ): ZIO[BspClient, MarklitError, Vector[BuildTarget]] =
    ZIO.serviceWithZIO[BspClient](_.buildTargets(conn))

  def dependencyClasspath(
      conn: BspConnection,
      target: BuildTargetIdentifier
  ): ZIO[BspClient, MarklitError, Vector[String]] =
    ZIO.serviceWithZIO[BspClient](_.dependencyClasspath(conn, target))

  def scalacOptions(
      conn: BspConnection,
      target: BuildTargetIdentifier
  ): ZIO[BspClient, MarklitError, ScalacOptionsItem] =
    ZIO.serviceWithZIO[BspClient](_.scalacOptions(conn, target))

  def disconnect(conn: BspConnection): ZIO[BspClient, Nothing, Unit] =
    ZIO.serviceWithZIO[BspClient](_.disconnect(conn))
