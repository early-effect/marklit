# Mill Marklit Plugin

A Mill plugin for [marklit](https://github.com/russwyte/marklit) - typechecked Scala documentation.

## Installation

Add the plugin to your `build.mill`:

```scala
import $ivy.`io.github.russwyte::mill-marklit:0.1.0`
import marklit.mill.MarklitModule
```

## Usage

Mix `MarklitModule` into your module:

```scala
import $ivy.`io.github.russwyte::mill-marklit:0.1.0`
import marklit.mill.MarklitModule

object docs extends ScalaModule with MarklitModule {
  def scalaVersion = "3.8.2"
  
  // Optional: customize source directory (defaults to millSourcePath / "markdown")
  // override def marklitSourceDir = Task.Source(millSourcePath / "docs")
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
| `marklitSourceDir` | `T[PathRef]` | `millSourcePath / "markdown"` | Directory containing markdown source files |
| `marklitShowVersion` | `T[Boolean]` | `true` | Show Scala version in output blocks |
| `marklitVerbose` | `T[Boolean]` | `false` | Enable verbose output |
| `marklitClasspath` | `T[Seq[PathRef]]` | `runClasspath()` | Classpath for code execution |

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

1. Build the CLI jar:
   ```bash
   cd /path/to/marklit
   sbt 'project cli' assembly
   ```

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
