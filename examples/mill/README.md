# Marklit Mill Example

This example shows how to use marklit with the [Mill](https://mill-build.org/) build tool.

## Prerequisites

1. Install Mill: https://mill-build.org/mill/Installation_IDE_Support.html
2. Build the marklit CLI jar from the root project:
   ```bash
   cd ../..
   sbt 'project cli' assembly
   ```

## Usage

Generate documentation:
```bash
mill root.marklitGenerate
```

Check that code compiles without generating output:
```bash
mill root.marklitCheck
```

Output is written to `out/root/marklitGenerate.dest/`.

### Watch mode

`mill -w docs.marklitGenerate` re-runs the task whenever a markdown source
under `marklitSourceDir` changes. This works out of the box when the source
directory lives inside the Mill workspace.

This example shares its markdown source with the sbt example
(`../base/src/main/markdown`), which sits **outside** the Mill workspace
root. Mill's native file-system watcher refuses to register paths outside
the workspace and prints `Watched path … is outside workspace root … is
unsupported`. To watch this layout, fall back to Mill's polling mode:

```bash
mill -w --notify-watch=false docs.marklitGenerate
```

Polling has higher idle CPU cost but ignores the workspace-root restriction.
For a typical project where markdown lives inside the docs module, the
default `mill -w docs.marklitGenerate` is preferred.

## Configuration

The `build.mill` file defines:

- `marklitSourceDir` - Task.Source for the markdown source directory
- `marklitCliJar` - Task.Source for the marklit CLI jar
- `marklitGenerate` - Task that generates markdown with executed output
- `marklitCheck` - Task that verifies code compiles without generating output

## Customizing

To use marklit in your own Mill project, copy the marklit tasks from `build.mill` and adjust:

1. Set `marklitSourceDir` to your markdown source directory (relative to `moduleDir`)
2. Either embed the CLI jar or reference it from a published location
3. Add any project classpath entries via `--classpath` if your examples use project code

## Example with Project Classpath

If your markdown examples use code from your project:

```scala
def marklitGenerate = Task {
  val cp = runClasspath().map(_.path.toString).mkString(":")
  val sourceDir = marklitSourceDir().path
  val targetDir = Task.dest
  val cliJar = marklitCliJar().path
  
  val sources = os.walk(sourceDir).filter(_.ext == "md")
  
  val args = Seq(
    "java", "-jar", cliJar.toString,
    "--verbose",
    "--classpath", cp,
    "--out", targetDir.toString
  ) ++ sources.map(_.toString)
  
  os.proc(args).call(check = false)
  // ...
}
```
