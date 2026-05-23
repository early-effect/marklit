// Multi-module example demonstrating marklit against a cross-built core
// library. The `core` module publishes the same `Greeter` API on Scala 2.13
// and Scala 3, with a per-major code path that uses version-specific
// language features. The `docs` module runs marklit; multi-version.md
// references `Greeter` from inside both 2.13 and 3.x code blocks.
ThisBuild / version := "0.1.0"
ThisBuild / organization := "marklit.example"

val scala3 = "3.8.2"
val scala2 = "2.13.16"

lazy val core = project
  .in(file("core"))
  .settings(
    name := "marklit-example-core",
    crossScalaVersions := Seq(scala2, scala3),
    scalaVersion := scala3
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
