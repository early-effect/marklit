# Multi-Version Scala Compilation

marklit can compile each code block against a *different* Scala version
in the same document — including Scala 2.13. Below, the same snippet runs
under several different compilers, resolved on the fly via Coursier.

The version annotation appearing above each output block (`// Scala x.y.z`)
is emitted by marklit — it tells you which compiler actually produced that
output.

## A `shared` setup block

Blocks marked `shared` contribute their code to *every* per-version
default scope, "as if prepended at the document's start." That makes them
ideal for imports or helpers used across versions.

```scala marklit:shared
def reportVersion(): Unit = {
  val v = scala.util.Properties.versionNumberString
  println(s"compiled against Scala $v")
}
```

## File default — uses the project's declared `scalaVersion`

This block has no `scala=` modifier, so it compiles against whatever the
build plugin (or `--scala-version`) declared. In this example that's the
project's `scalaVersion` setting.

```scala
reportVersion()
```

## Specific version — `scala=3.3.7`

Adding `marklit:scala=3.3.7` overrides the file default for *just this block*.
marklit resolves `scala3-compiler_3:3.3.7` via Coursier and runs it on its own
isolated classloader.

Each per-version block has its *own* default scope, so the `reportVersion`
helper from the `shared` block is in scope here without any extra plumbing.

```scala marklit:scala=3.3.7
reportVersion()
```

## Specific version — `scala=3.6.4`

The previous block's `3.3.7` compiler is cached. This block requests a
different version and gets its own classloader.

```scala marklit:scala=3.6.4
reportVersion()
```

## Specific version — `scala=3.7.3`

Each version-specific block is compiled in isolation: its scope cannot
extend a scope from a different version (and vice versa). This avoids
classloader leakage between mismatched library jars.

```scala marklit:scala=3.7.3
reportVersion()
```

## Cross the major boundary — `scala=2.13.16`

marklit also supports Scala 2.13. The bundled CLI carries a thin nsc-side
shim alongside the dotc shim; at runtime marklit picks the right shim based
on the requested major and resolves `scala-compiler:2.13.x` via Coursier.

The 2.13 block has its own per-version default scope, so the `shared`
`reportVersion` helper is recompiled here with 2.13's standard library.

```scala marklit:scala=2.13.16
reportVersion()
```

## Why this matters

The `marklit-cli` jar is built once and bundles **only** thin shims against
the dotc (3.x) and nsc (2.13) compiler APIs. At runtime, every per-version
classloader gets a fresh copy of the user-requested compiler (and its
matching standard library) from Coursier — so user code is always compiled
and run by the version they asked for, never by the bundled shim's
version.
