import zipx.*

/** Typed catalog: every library and plugin this build may use. `zipxDepUpdate`
  * rewrites constructors here. sbt-zipx is not a row: generate emits it from
  * the loaded plugin (`zipxSelfPlugins`). Action pins stay on jar defaults.
  * sbt-pgp is not a row: zipx already brings it in.
  */
object MyVersions extends ZipxVersions:
  val sbt: SbtVersion = SbtVersion("2.0.6")
  val scala: ScalaVersion = ScalaVersion("3.8.4")

  /** Oldest supported 3.x for the dotc shim. Not a catalog row. */
  val shimScala: ScalaVersion = ScalaVersion("3.3.8")

  /** 2.13 nsc shim compile version. Not a catalog row. */
  val shim2Scala: ScalaVersion = ScalaVersion("2.13.18")

  val zio = Lib("dev.zio", "zio", "2.1.26")
  val zioStreams = zio.mod("zio-streams")
  val zioTest = zio.mod("zio-test")
  val zioTestSbt = zio.mod("zio-test-sbt")
  val zioJson = Lib("dev.zio", "zio-json", "0.10.0")
  val zioCli = Lib("dev.zio", "zio-cli", "0.8.1")

  val fastparse = Lib("com.lihaoyi", "fastparse", "3.1.1")
  val coursierInterface = Lib("io.get-coursier", "interface", "1.0.9").java
  val junit = Lib("junit", "junit", "4.13.2").java
  val junitInterface = Lib("com.github.sbt", "junit-interface", "0.13.3").java
  val scala3Compiler = Lib("org.scala-lang", "scala3-compiler", "3.3.8")
  val scalaCompiler = Lib("org.scala-lang", "scala-compiler", "2.13.18").java

  val assembly = Plugin("com.eed3si9n", "sbt-assembly", "2.4.1")
  val scalafmt = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
  val dynver = Plugin("com.github.sbt", "sbt-dynver", "5.1.1")

  def junitTests = library(junit.test, junitInterface.test)
  def zioTests = library(zioTest.test, zioTestSbt.test)
  def shimTests = library(zioTest.test, zioTestSbt.test, coursierInterface.test)
  def coreLib = library(zio, zioStreams, zioJson, fastparse, coursierInterface)
  def compilerLib = library(zio, zioStreams, zioJson)
  def cliLib = library(zio, zioCli, coursierInterface, fastparse)
end MyVersions
