// Multi-module example demonstrating marklit against a cross-built core
// library. The `core` module publishes the same `Greeter` API on Scala 2.13
// and Scala 3, with a per-major code path that uses version-specific
// language features. The `docs` module runs marklit; multi-version.md
// references `Greeter` from inside both 2.13 and 3.x code blocks.
//
// The `core` source tree lives in ../base/core/src so the Mill example
// can reuse the exact same code; only the build wiring differs.
ThisBuild / version := "0.1.0"
ThisBuild / organization := "marklit.example"

val scala3 = "3.8.2"
val scala2 = "2.13.16"

// Shared source root for the cross-built `core` module — the Mill example
// points its CrossScalaModule at these same files. Resolved per-project
// from `baseDirectory` (which is examples/sbt/core for this project) up
// two levels to examples/, then into base/core/src.
lazy val core = project
  .in(file("core"))
  .settings(
    name := "marklit-example-core",
    crossScalaVersions := Seq(scala2, scala3),
    scalaVersion := scala3,
    Compile / scalaSource := baseDirectory.value / ".." / ".." / "base" / "core" / "src" / "main" / "scala",
    Compile / unmanagedSourceDirectories ++= {
      val sharedMain =
        baseDirectory.value / ".." / ".." / "base" / "core" / "src" / "main"
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, _)) => Seq(sharedMain / "scala-2.13")
        case Some((3, _)) => Seq(sharedMain / "scala-3")
        case _            => Nil
      }
    }
  )

lazy val docs = project
  .in(file("docs"))
  .dependsOn(core)
  .settings(
    name := "marklit-example-docs",
    scalaVersion := scala3,
    // Use the shared markdown sources. The base/ directory is reused across
    // the sbt and (eventually) Mill examples to avoid drift.
    marklitSourceDirectory := (ThisBuild / baseDirectory).value.getParentFile / "base" / "src" / "main" / "markdown",
    marklitTargetDirectory := baseDirectory.value / "target" / "docs",
    marklitVerbose := true
    // marklitMajorClasspaths is auto-discovered from this project's
    // dependsOn graph — every project dep with a non-default-major entry in
    // crossScalaVersions contributes its compiled classes directory. Just
    // make sure the cross-builds are on disk first (e.g., `+core/compile`).
  )

lazy val root = project
  .in(file("."))
  .aggregate(core, docs)
  .settings(
    name := "marklit-sbt-example",
    publish / skip := true
  )

// Cross-build the core module before running docs so the 2.13 jar is
// available for any 2.13 cross-version blocks. The default `docs` alias
// builds both 2.13 and 3.x core, then regenerates docs.
addCommandAlias(
  "docs",
  "; core/clean; +core/compile; docs/clean; docs/marklitGenerate"
)
