// Multi-module example demonstrating marklit against a cross-built core
// library. The `core` module publishes the same `Greeter` API on Scala 2.13
// and Scala 3, with a per-major code path that uses version-specific
// language features. The `docs` module runs marklit; scopes-and-versions.md
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
    // dependsOn graph — every project dep with a non-default-major entry
    // in crossScalaVersions contributes its compiled classes directory.
    // The build-level `marklitGenerate` command auto-runs `+core/compile`
    // before the docs task, so a fresh checkout works with a single command.
  )

// `pageDocs` shows the per-task override pattern: the same plugin is
// reconfigured (different source directory, page-scope on, different
// output dir) so that one project renders mdoc-style page-scoped docs
// while `docs` keeps the per-block isolated default. Run both with
// `sbt marklitGenerate` (the build-level command iterates every
// MarklitPlugin-enabled project that has a source directory).
lazy val pageDocs = project
  .in(file("page-docs"))
  .dependsOn(core)
  .settings(
    name := "marklit-example-page-docs",
    scalaVersion := scala3,
    marklitSourceDirectory := (ThisBuild / baseDirectory).value.getParentFile / "base" / "src" / "main" / "markdown-page-scope",
    marklitTargetDirectory := baseDirectory.value / "target" / "page-docs",
    marklitPageScope := true,
    marklitVerbose := true
  )

lazy val root = project
  .in(file("."))
  .aggregate(core, docs, pageDocs)
  .settings(
    name := "marklit-sbt-example",
    publish / skip := true
  )
