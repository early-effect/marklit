lazy val docs = project
  .in(file("."))
  .settings(
    scalaVersion := "3.8.2",
    marklitSourceDirectory := baseDirectory.value / "markdown",
    marklitTargetDirectory := baseDirectory.value / "target" / "docs",
    marklitVerbose := true
  )

// Assert the generated output actually contains the executed block's stdout.
TaskKey[Unit]("checkOutput") := {
  val out = baseDirectory.value / "target" / "docs" / "good.md"
  val content = IO.read(out)
  if (!content.contains("answer = 2"))
    sys.error(s"expected executed output 'answer = 2' in $out, got:\n$content")
}
