# Mill Marklit Plugin

A Mill plugin for [marklit](https://github.com/russwyte/marklit) - typechecked Scala documentation.

> **Not yet published.** Build and publish it locally first (see
> [Building from Source](#building-from-source)), then depend on the local
> version. marklit's compiler runs **in-process** — the plugin depends on
> `marklit-compiler` and calls it directly; there is no CLI subprocess or
> daemon.

## Installation

Declare the plugin in your `build.mill` header:

```scala
//| mvnDeps:
//| - io.github.russwyte::mill-marklit:0.1.0

import marklit.mill.MarklitModule
```

## Usage

Mix `MarklitModule` into your module:

```scala
//| mvnDeps:
//| - io.github.russwyte::mill-marklit:0.1.0

import mill._
import mill.scalalib._
import marklit.mill.MarklitModule

object docs extends ScalaModule with MarklitModule {
  def scalaVersion = "3.8.2"

  // Optional: customize source directory (defaults to moduleDir / "markdown")
  // override def marklitSourceDir = Task.Source(moduleDir / "docs")
}
```

Then run:

```bash
# Generate documentation with executed code output
mill docs.marklitGenerate

# Check that code compiles without generating output
mill docs.marklitCheck
```

## Configuration

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `marklitSourceDir` | `T[PathRef]` | `moduleDir / "markdown"` | Directory containing markdown source files |
| `marklitShowVersion` | `T[Boolean]` | `true` | Show Scala version in output blocks |
| `marklitShowWarnings` | `T[Boolean]` | `true` | Render compile warnings in output blocks |
| `marklitVerbose` | `T[Boolean]` | `false` | Enable verbose output |
| `marklitPageScope` | `T[Boolean]` | `false` | Share scope across all anonymous blocks per file (mdoc-style) |
| `marklitClasspath` | `T[Seq[PathRef]]` | `compileClasspath()` (stdlib filtered) | Classpath made available inside code blocks |
| `marklitCrossModuleDeps` | `Seq[CrossModuleBase]` | `Seq.empty` | Cross-built sibling modules to expose to cross-version blocks |
| `marklitMajorClasspaths` | `T[Map[String, Seq[PathRef]]]` | derived from `marklitCrossModuleDeps` | Per-major (`"2"`/`"3"`) classpaths for cross-version blocks |
| `marklitCacheDir` | `T[Option[PathRef]]` | a dest under `out/` | Persistent on-disk block compile cache (`None` disables) |

## Example with Project Code

If your markdown examples use code from your project:

```scala
object mylib extends ScalaModule {
  def scalaVersion = "3.8.2"
  // your library code
}

object docs extends ScalaModule with MarklitModule {
  def scalaVersion = "3.8.2"
  
  // Include mylib on the classpath so examples can use it
  override def moduleDeps = Seq(mylib)
}
```

## Building from Source

1. Publish marklit's libraries locally (the plugin depends on `marklit-compiler`):
   ```bash
   cd /path/to/marklit
   sbt '; set every version := "0.1.0-LOCAL" ; compilerApi/publishLocal ; core/publishLocal ; compiler/publishLocal'
   ```
   (Match the `marklitVersion` in `mill-plugin/build.mill`.)

2. Build and publish the Mill plugin locally:
   ```bash
   cd mill-plugin
   mill plugin.publishLocal
   ```

## Development

Run tests:
```bash
mill plugin.test
```
