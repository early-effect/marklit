# Scopes and Multi-Version Compilation

How marklit lets blocks share state, stay isolated when needed, and run
against multiple Scala versions in the same document. These features are
covered together because they're deeply intertwined: a *scope* is the unit
that gets compiled together, and per-block versioning is a property of
that scope.

## Why scopes?

By default each block gets its own scope, isolated from the others. This
keeps doc examples self-contained — defining `val x = 1` in one block
doesn't leak into the next. When you *want* sharing, you opt in
explicitly with `id=` and `extends=`.

## Named scopes: `id=` and `extends=`

Tag a block with `id=<name>` to give its scope a name. Other blocks can
then `extends=<name>` to inherit everything in that scope (and add to
it).

```scala marklit:id=base
val baseValue = 100
def double(n: Int): Int = n * 2
```

```scala marklit:extends=base
println(s"baseValue = $baseValue")
println(s"double(7) = ${double(7)}")
```

A scope that isn't extended is an island — `id=math` and `id=strings`
below stay independent:

```scala marklit:id=math
def square(x: Int): Int = x * x
```

```scala marklit:id=strings
def repeat(s: String, n: Int): String = s * n
```

## Appending: extending in place

Add `append` to grow an existing scope from a follow-up block, instead
of creating a new derived scope. This is the right tool when you want
the rendered output to read top-to-bottom but later blocks need to
contribute back to the same shared state.

```scala marklit:id=growing
var items = List("a")
```

```scala marklit:extends=growing,append
items = items :+ "b"
```

```scala marklit:extends=growing,append
items = items :+ "c"
println(items)  // List(a, b, c)
```

## Filtering by major: `scala=2`, `scala=3`

A block tagged `scala=2` only runs when the active Scala major is 2; a
block tagged `scala=3` only runs on major 3. Use these to show
side-by-side language differences.

```scala marklit:scala=3
enum Status:
  case Active, Inactive, Pending

println(Status.Active)
```

```scala marklit:scala=2
sealed trait Status2
case object Active2 extends Status2
case object Inactive2 extends Status2

println(Active2)
```

## Cross-version compilation: `scala=<full-version>`

Tag a block with a full version (e.g. `scala=3.7.3` or `scala=2.13.16`)
to compile *that block* against *that compiler*, regardless of the
file's default. marklit resolves the requested compiler via Coursier,
runs it on its own classloader, and emits a `// Scala <version>` annotation
above the rendered output. (Pass `--show-warnings=false` or
`marklitShowVersion := false` to suppress that annotation.)

The file below uses `shared-3` and `shared-2` setup blocks (see next
section) so each per-version block has a `reportVersion()` helper in
scope:

```scala marklit:shared-3
def reportVersion(): Unit = {
  val v = dotty.tools.dotc.config.Properties.versionNumberString
  println(s"compiled against Scala $v")
}
```

```scala marklit:shared-2
def reportVersion(): Unit = {
  val v = scala.util.Properties.versionNumberString
  println(s"compiled against Scala $v")
}
```

The next block has no `scala=` modifier, so it compiles against
whatever the build plugin (or `--scala-version`) declared. In this
example that's the project's `scalaVersion` setting.

```scala
reportVersion()
```

A specific 3.x version:

```scala marklit:scala=3.3.7
reportVersion()
```

A different 3.x version — gets its own classloader, cached for reuse:

```scala marklit:scala=3.6.4
reportVersion()
```

A third 3.x version — each version-specific block is compiled in
isolation; its scope can't extend a scope from a different version (and
vice versa). This avoids classloader leakage between mismatched library
jars.

```scala marklit:scala=3.7.3
reportVersion()
```

Crossing the major boundary into Scala 2.13:

```scala marklit:scala=2.13.16
reportVersion()
```

## Sharing helpers across versions: `shared` and `shared-{major}`

Blocks marked `shared` contribute their code to *every* per-version
default scope, "as if prepended at the document's start." `shared-3` /
`shared-2` restrict the contribution to a single major — useful when
the helper code uses APIs that only exist on one side (the
`reportVersion` example above is exactly that case: dotc's properties
API on Scala 3, scala-library's on 2.13).

## Per-version default scopes

Each Scala version maintains its own default scope. Calling
`reportVersion()` from a `scala=3.3.7` block reaches the 3.x `shared-3`
helper; the same call in a `scala=2.13.16` block reaches the 2.13
`shared-2` helper. You don't need to thread the helper through
`extends=` per block.

## Cross-built dependencies

When a sibling project is cross-built (e.g. a `core` module published
for both 2.13 and 3.x), the build plugin can forward per-major
classpaths. The default-3 block reaches the 3.x build of `core`; a
`scala=2.13.16` block reaches the 2.13 build. The call sites look
identical:

```scala
println(example.Greeter.hello("Scala 3"))
```

```scala marklit:scala=2.13.16
println(example.Greeter.hello("Scala 2.13"))
```

If `example.Color` is an `enum` on Scala 3 and a sealed trait + case
objects on Scala 2.13 (different bytecode shapes for the same FQN),
both builds still expose the same surface API:

```scala
println(example.Color.Red.code)
```

```scala marklit:scala=2.13.16
println(example.Color.Red.code)
```

## Combining scopes and modifiers

Modifiers stack with scopes. `silent` hides setup output, `id=` names
the scope, and a follow-up `extends=` reuses the values:

```scala marklit:silent,id=combined
val combinedSetup = "combined example"
```

```scala marklit:extends=combined
println(s"Using: $combinedSetup")
```

## Why this matters

The `marklit-cli` jar is built once and bundles **only** thin shims
against the dotc (3.x) and nsc (2.13) compiler APIs. At runtime, every
per-version classloader gets a fresh copy of the user-requested
compiler (and its matching standard library) from Coursier — so user
code is always compiled and run by the version they asked for, never by
the bundled shim's version.

## See also

- [tutorial.md](tutorial.md) — single-version basics: visibility,
  assertions, passthrough.
- [using-directives.md](using-directives.md) — file-level
  `//> using scala` directive (the per-file default that `scala=`
  overrides).
- [zio-example.md](zio-example.md) — a worked example that uses `id=`
  and `extends=` to compose a small ZIO service.
