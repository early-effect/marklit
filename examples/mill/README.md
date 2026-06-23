# Marklit Mill Example

This example shows how to use marklit with the [Mill](https://mill-build.org/) build tool.

## Prerequisites

The Mill plugin calls marklit's compiler **in-process** — there's no CLI jar to
build. You just need the plugin (and the libraries it depends on) published to
your local repo. Until `mill-marklit` is on Maven Central, publish them locally:

1. Install Mill: https://mill-build.org/mill/Installation_IDE_Support.html
2. Publish marklit's libraries and the Mill plugin locally from the repo root:
   ```bash
   cd ../..
   sbt '; set every version := "0.1.0-LOCAL" ; compilerApi/publishLocal ; core/publishLocal ; compiler/publishLocal'
   (cd mill-plugin && mill plugin.publishLocal)
   ```
   This example's `build.mill` depends on `mill-marklit:0.1.0-LOCAL`.
3. marklit's published artifacts are built for JDK 25, so run Mill on a JDK 25
   (e.g. `export JAVA_HOME=$(/usr/libexec/java_home -v 25)` or via your JDK
   manager) for these examples.

## Usage

Generate documentation:
```bash
mill docs.marklitGenerate
```

Check that code compiles without generating output:
```bash
mill docs.marklitCheck
```

This build also defines a page-scoped module: `mill pageDocs.marklitGenerate`.

Output is written to `out/docs/marklitGenerate.dest/`.

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

`build.mill` mixes `MarklitModule` into the `docs` and `pageDocs` modules. The
trait provides the `marklitGenerate` / `marklitCheck` tasks plus configurable
settings (`marklitSourceDir`, `marklitShowVersion`, `marklitShowWarnings`,
`marklitVerbose`, `marklitPageScope`, `marklitClasspath`,
`marklitCrossModuleDeps`, `marklitMajorClasspaths`, `marklitCacheDir`). See
[mill-plugin/README.md](../../mill-plugin/README.md) for the full table.

This example also demonstrates **cross-built dependencies**: `core` is built for
both 2.13 and 3.x, and `docs` exposes both builds via `marklitCrossModuleDeps =
core.crossModules`, so a `marklit:scala=2.13.16` block can reach the 2.13 build
of `core`. The plugin buckets each cross-module's classpath by major
automatically.

## Customizing

To use marklit in your own Mill project:

1. Add `mill-marklit` to your `build.mill` `//| mvnDeps:` header and `import
   marklit.mill.MarklitModule`.
2. Mix `MarklitModule` into your `ScalaModule`.
3. Point `marklitSourceDir` at your markdown directory. Your module's compile
   classpath is forwarded automatically, so blocks can use your project code
   — no manual `--classpath` wiring needed.
4. For cross-version blocks, set `marklitCrossModuleDeps` to your cross-built
   sibling modules' `.crossModules`.
