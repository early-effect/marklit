// Scala version used to compile marklit's own modules (core, compiler, cli,
// the Mill plugin trait — everything except the shim and the sbt plugin).
val marklitScalaVersion = "3.8.3"

// Scala version the compiler-shim is built against. Pinned to the oldest
// supported 3.x to keep the shim's dotc API surface compatible at runtime
// against any user-requested 3.x compiler. Bump only when we drop support
// for a 3.x line.
val shimScalaVersion = "3.3.7"

val zioVersion = "2.1.26"
val coursierInterfaceVersion = "1.0.9"

ThisBuild / scalaVersion := marklitScalaVersion
ThisBuild / organization := "io.github.russwyte"
ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-Wunused:all"
)

lazy val root = project
  .in(file("."))
  .aggregate(compilerApi, compilerShim, core, compiler, cli, plugin)
  .settings(
    name := "marklit-root",
    publish / skip := true
  )

// Java-only, version-neutral interfaces shared between the marklit orchestrator
// and the per-version compiler shim. Must not depend on Scala or any Scala
// library, so it can be loaded from a custom parent classloader visible to
// both the orchestrator's classloader and each per-version compiler classloader.
lazy val compilerApi = project
  .in(file("compiler-api"))
  .settings(
    name := "marklit-compiler-api",
    crossPaths := false,
    autoScalaLibrary := false,
    Compile / doc / sources := Seq.empty,
    libraryDependencies ++= Seq(
      "junit" % "junit" % "4.13.2" % Test,
      "com.github.sbt" % "junit-interface" % "0.13.3" % Test
    ),
    testOptions += Tests.Argument(TestFrameworks.JUnit, "-v")
  )

// The bridge between marklit and dotc. The ONLY module that imports
// dotty.tools.dotc.*. Compiled against a known scala3-compiler version, but
// at runtime its dotc references are resolved against whatever scala3-compiler
// the orchestrator loaded into its per-version URLClassLoader. The dotc API
// surface used here (Driver, Reporter, Diagnostic) is stable across 3.x.
//
// The shim's compiled jar is bundled as a resource inside the CLI fat jar
// and extracted at runtime alongside the per-version compiler jars.
lazy val compilerShim = project
  .in(file("compiler-shim"))
  .dependsOn(compilerApi)
  .settings(
    name := "marklit-compiler-shim",
    publish / skip := true,
    scalaVersion := shimScalaVersion,
    libraryDependencies ++= Seq(
      "org.scala-lang" %% "scala3-compiler" % shimScalaVersion % Provided,
      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
      // Coursier (Test only) so the shim test can resolve scala3-library
      // independently of the sbt classpath.
      "io.get-coursier" % "interface" % coursierInterfaceVersion % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    // Embed the shim's compile-time scala3 version as a plain text resource
    // so CompilerFactory.defaultScalaVersion can read it WITHOUT loading any
    // shim classes (which would transitively need scala3-compiler on the
    // classpath — defeating the whole point of the per-version classloader).
    Compile / resourceGenerators += Def.task {
      val target =
        (Compile / resourceManaged).value / "marklit-shim-version.txt"
      IO.write(target, shimScalaVersion)
      Seq(target)
    }.taskValue
  )

// The shim jar is a thin jar (compiled classes only — `scala3-compiler` is
// Provided, and `compiler-api` is loaded via its own classloader at runtime).
// We package only the shim's own compile output, not its transitive deps.

// Alias to rebuild CLI assembly and publish plugin locally
addCommandAlias(
  "publishAll",
  "; cli/clean; cli/assembly; plugin/clean; plugin/publishLocal"
)

lazy val core = project
  .in(file("core"))
  .settings(
    name := "marklit-core",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion,
      "dev.zio" %% "zio-json" % "0.9.2",
      "com.lihaoyi" %% "fastparse" % "3.1.1",
      // Coursier for dependency resolution
      "io.get-coursier" % "interface" % coursierInterfaceVersion,
      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )

lazy val compiler = project
  .in(file("compiler"))
  .dependsOn(core, compilerApi)
  .settings(
    name := "marklit-compiler",
    libraryDependencies ++= Seq(
      // ZIO
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion,
      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,

      // BSP client
      "ch.epfl.scala" % "bsp4j" % "2.2.0-M2",

      // JSON parsing
      "dev.zio" %% "zio-json" % "0.9.2",

      // Test containers for integration tests
      "org.testcontainers" % "testcontainers" % "2.0.5" % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    // The shim jar is wired into the compiler test classpath as a resource so
    // CompilerFactory tests can load it without going through the CLI.
    Test / resourceGenerators += Def.task {
      val shimJar = (compilerShim / Compile / packageBin).value
      val resourceDir = (Test / resourceManaged).value
      val targetFile = resourceDir / "marklit-compiler-shim.jar"
      IO.copyFile(shimJar, targetFile)
      Seq(targetFile)
    }.taskValue
  )

lazy val cli = project
  .in(file("cli"))
  .dependsOn(compiler)
  .settings(
    name := "marklit-cli",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-cli" % "0.8.1",
      // Coursier for dependency resolution (Java API - works with Scala 3)
      "io.get-coursier" % "interface" % coursierInterfaceVersion,
      // Fastparse for using directive parsing
      "com.lihaoyi" %% "fastparse" % "3.1.1",
      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    // Bundle the compiler-shim jar as a resource. The CompilerFactory extracts
    // this at runtime onto each per-version compiler classloader. The jar is
    // thin (shim classes only — scala3-compiler is Provided), so it works
    // against whatever scala3-compiler version the user requested.
    Compile / resourceGenerators += Def.task {
      val shimJar = (compilerShim / Compile / packageBin).value
      val resourceDir = (Compile / resourceManaged).value
      val targetFile = resourceDir / "marklit-compiler-shim.jar"
      IO.copyFile(shimJar, targetFile)
      Seq(targetFile)
    }.taskValue,
    // Assembly settings for fat jar
    assembly / assemblyJarName := "marklit-cli.jar",
    assembly / mainClass := Some("marklit.cli.MarklitCli"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
      case PathList("META-INF", "versions", "9", "module-info.class") =>
        MergeStrategy.discard
      case PathList("META-INF", xs @ _*)
          if xs.lastOption.exists(_.endsWith(".SF")) =>
        MergeStrategy.discard
      case PathList("META-INF", xs @ _*)
          if xs.lastOption.exists(_.endsWith(".DSA")) =>
        MergeStrategy.discard
      case PathList("META-INF", xs @ _*)
          if xs.lastOption.exists(_.endsWith(".RSA")) =>
        MergeStrategy.discard
      case PathList("module-info.class")        => MergeStrategy.discard
      case x if x.endsWith("module-info.class") => MergeStrategy.discard
      case "reference.conf"                     => MergeStrategy.concat
      case x if x.endsWith(".conf")             => MergeStrategy.concat
      case "scala-collection-compat.properties" => MergeStrategy.first
      case x                                    =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    }
  )

lazy val plugin = project
  .in(file("sbt-plugin"))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-marklit",
    scalaVersion := "2.12.20",
    // Copy CLI assembly jar to plugin resources before packaging
    Compile / resourceGenerators += Def.task {
      val cliJar = (cli / assembly).value
      val resourceDir = (Compile / resourceManaged).value
      val targetFile = resourceDir / "marklit-cli.jar"
      IO.copyFile(cliJar, targetFile)
      Seq(targetFile)
    }.taskValue,
    // Ensure CLI assembly is built before plugin publish
    publish := (publish dependsOn (cli / assembly)).value,
    publishLocal := (publishLocal dependsOn (cli / assembly)).value,
    scalacOptions := Seq("-deprecation", "-feature")
  )
