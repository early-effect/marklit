scalaVersion := "3.8.2"

name := "marklit-sbt-example"
version := "0.1.0"

// Use shared markdown from base example
marklitSourceDirectory := baseDirectory.value.getParentFile / "base" / "src" / "main" / "markdown"
marklitTargetDirectory := baseDirectory.value / "target" / "docs"
marklitVerbose := true

// Alias to clean and regenerate docs
addCommandAlias("docs", "; clean; marklitGenerate")
