MyVersions.settings

ThisBuild / organization := "rocks.earlyeffect"
// version is derived from git tags by sbt-dynver (tag v0.1.0 -> 0.1.0).

ThisBuild / organizationName := "Early Effect"
ThisBuild / organizationHomepage := Some(url("https://www.earlyeffect.rocks"))
ThisBuild / licenses := List(
  "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")
)
ThisBuild / homepage := Some(url("https://github.com/early-effect/marklit"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/early-effect/marklit"),
    "scm:git@github.com:early-effect/marklit.git"
  )
)
ThisBuild / developers := List(
  Developer(
    id = "russwyte",
    name = "Russ White",
    email = "356303+russwyte@users.noreply.github.com",
    url = url("https://github.com/russwyte")
  )
)
ThisBuild / versionScheme := Some("early-semver")

// zio-cli 0.8.1 still pins zio-json 0.9.2 while we resolve 0.10.0. Under early-semver a 0.9 -> 0.10
// bump reads as breaking, so sbt 2.x's strict eviction check fails the build. The codec API zio-cli
// uses is unchanged across the bump, so force 0.10.0 rather than hold zio-json back.
ThisBuild / libraryDependencySchemes += "dev.zio" %% "zio-json" % "always"

// Publishing to Sonatype's Central Portal. sbt 1.11+ has built-in support via
// `localStaging` / `publishSigned` / `sonaRelease`; no sbt-sonatype plugin needed.
ThisBuild / publishTo := {
  val centralSnapshots =
    "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}

// CI-only publishing: the signing key hex comes from the PGP_KEY_HEX env var, set
// by the shared early-effect org secret in the release workflow. There is no real
// key in this file: the "MISSING_KEY_HEX" sentinel keeps the build loadable for
// local compile/test but makes signing fail loudly if anyone tries to publish
// off-CI. Rotating the key is a one-place change to the PGP_KEY_HEX org secret.
usePgpKeyHex(sys.env.getOrElse("PGP_KEY_HEX", "MISSING_KEY_HEX"))

// zipx: Aggregate verify (builtin testFull) + Central publish (ordered release alias) + catalog PRs.
zipxJavaVersion := JdkVersion("25")
// Ordered publish: compilerApi → core → compiler → plugin/clean → plugin, then sonaRelease.
// That `plugin/clean` is why this is the `release` alias, not ZipxCentral.release's default
// per-module publishSigned + sonaRelease. Aggregate would join `release` once per publisher;
// releaseRoot is the Once job. `addCommandAlias` is not a TaskKey; SbtCommand.raw is the hatch.
zipxCapabilities += ZipxCentral.releaseRoot.running(
  SbtCommand.raw("release").fold(msg => sys.error(s"zipx: $msg"), identity)
)

// Copy a packaged jar (an sbt 2.0 virtual file ref) into a resource dir.
// fileConverter must be read inside each Def.task (`.value` is a macro that
// only expands in a task/setting scope), but the conversion + copy lives here.
def copyJarResource(
    converter: xsbti.FileConverter,
    jar: xsbti.HashedVirtualFileRef,
    targetDir: File,
    name: String
): Seq[File] = {
  val targetFile = targetDir / name
  IO.copyFile(converter.toPath(jar).toFile, targetFile)
  Seq(targetFile)
}

// Only the sbt plugin is published; everything else is internal or bundled.
lazy val publishSettings = Seq(
  publishMavenStyle := true,
  pomIncludeRepository := { _ => false }
)

ThisBuild / scalacOptions := Seq(
  "-deprecation",
  "-feature",
  "-Wunused:all"
)

lazy val root = project
  .in(file("."))
  .aggregate(
    compilerApi,
    compilerShim,
    compilerShim2,
    core,
    compiler,
    cli,
    plugin
  )
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
  .settings(publishSettings)
  .settings(
    name := "marklit-compiler-api",
    crossPaths := false,
    autoScalaLibrary := false,
    Compile / doc / sources := Seq.empty,
    MyVersions.junitTests,
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
    // Oldest supported 3.x: keep the shim's dotc API surface compatible at runtime
    // against any user-requested 3.x compiler. Bump only when we drop a 3.x line.
    scalaVersion := MyVersions.shimScala,
    MyVersions.shimTests,
    libraryDependencies += MyVersions
      .moduleID(MyVersions.scala3Compiler) % Provided,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    // Embed the shim's compile-time scala3 version as a plain text resource
    // so CompilerFactory.defaultScalaVersion can read it WITHOUT loading any
    // shim classes (which would transitively need scala3-compiler on the
    // classpath, defeating the whole point of the per-version classloader).
    Compile / resourceGenerators += Def.task {
      val target =
        (Compile / resourceManaged).value / "marklit-shim-version.txt"
      IO.write(target, MyVersions.shimScala: String)
      Seq(target)
    }.taskValue
  )

// The shim jar is a thin jar (compiled classes only; `scala3-compiler` is
// Provided, and `compiler-api` is loaded via its own classloader at runtime).
// We package only the shim's own compile output, not its transitive deps.

// The 2.13 sibling of `compilerShim`. Imports `scala.tools.nsc.*` (the
// classic Scala compiler), compiled against `MyVersions.shim2Scala`. The
// orchestrator picks this shim when a block requests `scala=2.x.y` or
// `scala=2`, mirroring the dotc shim path on the 3.x side.
lazy val compilerShim2 = project
  .in(file("compiler-shim-2"))
  .dependsOn(compilerApi)
  .settings(
    name := "marklit-compiler-shim-2",
    publish / skip := true,
    scalaVersion := MyVersions.shim2Scala,
    // Match shim's "extra" warnings off: the 2.13 nsc API has long-
    // deprecated members we touch through StoreReporter; suppress noise.
    scalacOptions := Seq("-deprecation", "-feature"),
    libraryDependencies += MyVersions
      .moduleID(MyVersions.scalaCompiler) % Provided,
    // Embed the 2.13 shim's compile-time scala version so the orchestrator
    // can read it without touching shim classes (which would require
    // scala-compiler on the probe classpath).
    Compile / resourceGenerators += Def.task {
      val target =
        (Compile / resourceManaged).value / "marklit-shim-2-version.txt"
      IO.write(target, MyVersions.shim2Scala: String)
      Seq(target)
    }.taskValue
  )

// Publish the plugin and the libraries it depends on to the local Ivy repo.
// The plugin declares marklit-compiler as a libraryDependency, so its deps
// (compiler-api, core, compiler) must be published for that dep to resolve.
addCommandAlias(
  "publishAll",
  "; compilerApi/publishLocal; core/publishLocal; compiler/publishLocal; plugin/clean; plugin/publishLocal"
)

// Release the plugin and its published dependencies to Sonatype Central
// (requires git tag + signing key + creds).
addCommandAlias(
  "release",
  "; compilerApi/publishSigned; core/publishSigned; compiler/publishSigned; plugin/clean; plugin/publishSigned; sonaRelease"
)

lazy val core = project
  .in(file("core"))
  .settings(publishSettings)
  .settings(
    name := "marklit-core",
    MyVersions.coreLib,
    MyVersions.zioTests,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )

lazy val compiler = project
  .in(file("compiler"))
  .dependsOn(core, compilerApi)
  .settings(publishSettings)
  .settings(
    name := "marklit-compiler",
    MyVersions.compilerLib,
    MyVersions.zioTests,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    // Both shim jars are bundled as Compile resources so they ride inside the
    // published marklit-compiler jar. CompilerFactory.copyResource reads them
    // via getClass.getResourceAsStream at runtime, so any consumer of
    // marklit-compiler (the CLI fat jar, the sbt/Mill plugins in-process) finds
    // them on its classpath without a separate published shim artifact. The
    // compiler's own tests inherit Compile resources, so CompilerFactory tests
    // keep working with no Test-scoped generator.
    Compile / resourceGenerators += Def.task {
      copyJarResource(
        fileConverter.value,
        (compilerShim / Compile / packageBin).value,
        (Compile / resourceManaged).value,
        "marklit-compiler-shim.jar"
      )
    }.taskValue,
    Compile / resourceGenerators += Def.task {
      copyJarResource(
        fileConverter.value,
        (compilerShim2 / Compile / packageBin).value,
        (Compile / resourceManaged).value,
        "marklit-compiler-shim-2.jar"
      )
    }.taskValue
  )

lazy val cli = project
  .in(file("cli"))
  .dependsOn(compiler)
  .settings(
    name := "marklit-cli",
    publish / skip := true,
    MyVersions.cliLib,
    MyVersions.zioTests,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    // The two compiler-shim jars are bundled as Compile resources of
    // marklit-compiler now, so they arrive here transitively via
    // dependsOn(compiler) and the assembly fat jar still contains them.
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
  .dependsOn(compiler)
  .settings(publishSettings)
  .settings(
    name := "sbt-marklit",
    // sbt 2.0 plugins compile against Scala 3 and publish with the _sbt2_3
    // suffix. dependsOn(compiler) puts marklit-compiler on the plugin classpath
    // and in the published POM. Do not also declare it as marklit-compiler %
    // version.value: zipxCheckDeps would treat that GAV as an undeclared catalog
    // row, and the version is this project, not a Maven pin.
    scalacOptions := Seq("-deprecation", "-feature"),
    // Scripted tests: publish this plugin + its libs to the local repo first,
    // and pass the version through so each test's project/plugins.sbt can
    // resolve it via `sys.props("plugin.version")`.
    scriptedDependencies := scriptedDependencies
      .dependsOn(
        compilerApi / publishLocal,
        core / publishLocal,
        compiler / publishLocal,
        publishLocal
      )
      .value,
    scriptedLaunchOpts ++= Seq(
      "-Xmx1024m",
      s"-Dplugin.version=${version.value}"
    ),
    scriptedBufferLog := false
  )
