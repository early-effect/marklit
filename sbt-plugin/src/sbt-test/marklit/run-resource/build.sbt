// Lifecycle-event log file, shared with the run resource via a system property.
val eventsFile = settingKey[File]("Run-resource lifecycle event log")

lazy val docs = project
  .in(file("."))
  .settings(
    scalaVersion := "3.8.2",
    marklitSourceDirectory := baseDirectory.value / "markdown",
    marklitTargetDirectory := baseDirectory.value / "target" / "docs",
    // The run resource lives in this project's own source tree, so it's on the
    // docs compile classpath that marklit forwards.
    marklitRunResourceClass := Some("example.CounterResource"),
    marklitVerbose := true,
    eventsFile := baseDirectory.value / "target" / "events.log",
    // Publish the events-file path to the JVM the marklit task runs in, so the
    // resource (loaded on marklit's own classloader) writes to the same file.
    Global / onLoad := {
      System.setProperty(
        "marklit.scripted.events",
        (baseDirectory.value / "target" / "events.log").getAbsolutePath
      )
      (Global / onLoad).value
    }
  )

// Assert the executed block saw the run-scoped shared counter at value 1 — the
// resource was acquired (and the counter reset) before the block ran.
TaskKey[Unit]("checkOutput") := {
  val out = baseDirectory.value / "target" / "docs" / "doc.md"
  val content = IO.read(out)
  if (!content.contains("counter = 1"))
    sys.error(s"expected 'counter = 1' in $out, got:\n$content")
}

// Assert the lifecycle ran exactly once per generate, acquire before close.
// After two generates in one session the log must be acquire,close,acquire,close.
TaskKey[Unit]("checkLifecycle") := {
  val log = eventsFile.value
  val lines =
    if (log.exists()) IO.readLines(log).filter(_.nonEmpty) else Nil
  val expected = List("acquire", "close", "acquire", "close")
  if (lines != expected)
    sys.error(s"expected lifecycle $expected, got: $lines")
}

// Reset the event log before the run sequence.
TaskKey[Unit]("resetEvents") := {
  val log = eventsFile.value
  IO.delete(log)
}
