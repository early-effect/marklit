# marklit

Typechecked Scala documentation. You write Markdown with Scala code fences; marklit compiles and runs each block against your real project classpath, then renders a new Markdown file with the actual output spliced in. Code that's supposed to fail can be asserted to fail. Code that's supposed to crash can be asserted to crash.

It's [mdoc](https://scalameta.org/mdoc/) territory — with a few different choices: **multiple modifiers per block**, **named scopes with inheritance**, **automatic classpath discovery via BSP**, and a **ZIO** runtime under the hood.

> Status: usable for Scala 3 projects. Scala 2.13 cross-build is on the roadmap, not implemented. See [Limitations](#limitations).

## Quick example

Write `docs/example.md`:

````markdown
```scala
case class User(name: String, age: Int)
val alice = User("Alice", 30)
println(s"${alice.name} is ${alice.age}")
```

```scala marklit:fail
val x: String = 42  // expected to fail compilation
```
````

Run it (sbt example):

```sh
sbt marklitGenerate
```

You get back the same Markdown with the executed output rendered after each block, and the `fail` block's compiler diagnostic embedded as proof it really doesn't compile.

## Modifiers

Code fences with the info string `scala marklit:<modifiers>` are processed. Modifiers are comma-separated.

| Modifier | Effect |
| --- | --- |
| *(none)* | Compile, execute, show code and stdout. |
| `silent` | Compile and execute, show only the code. |
| `invisible` | Compile and execute, hide both code and output. Useful for setup. |
| `compile-only` | Compile but do not execute. |
| `fail` | Assert that compilation fails. The diagnostic is rendered. |
| `warn` | Assert that compilation produces warnings. |
| `crash` | Assert that execution throws. The exception is rendered. |
| `passthrough` | Render the block as-is — no compilation. |
| `zio-app` | Wrap the block as a ZIO program and run it via `Runtime.unsafe.fromLayer`. |
| `id=<name>` | Name this block's scope. |
| `extends=<name>` | Create a child scope inheriting from `<name>`. |
| `extends=<name>,append` | Append to `<name>` instead of branching. Lexical order matters. |
| `scala=<version>` | Restrict the block to a Scala version (`scala=2`, `scala=3`). |

Combine freely: `silent,id=setup`, `fail,extends=errors`, etc. `id` and `append` are mutually exclusive; `append` requires `extends`.

See [examples/base/src/main/markdown/tutorial.md](examples/base/src/main/markdown/tutorial.md) for a worked example of every feature.

## Scopes

By default each block gets a fresh anonymous scope — code blocks do **not** share state unless you tell them to. This inverts mdoc's default and matches `mdoc:reset` semantics out of the box.

To share state across blocks, name a scope and extend it:

````markdown
```scala marklit:id=base
case class User(name: String, age: Int)
```

```scala marklit:extends=base
val alice = User("Alice", 30)
println(alice)
```

```scala marklit:extends=base,append
def validate(u: User): Boolean = u.age > 0
```

```scala marklit:extends=base
// sees User AND validate, because the previous block appended to base
println(validate(User("Bob", -1)))
```
````

`extends` without `id` is an anonymous child — useful for one-off blocks that need parent context but won't themselves be extended. Independent scope branches are tracked so they can be compiled in parallel.

Cross-version `extends` is rejected: a `scala=3` scope cannot extend a `scala=2` scope.

## Build tool integration

### sbt

```scala
// project/plugins.sbt
addSbtPlugin("io.github.russwyte" % "sbt-marklit" % "0.1.0-SNAPSHOT")
```

```scala
// build.sbt
marklitSourceDirectory := baseDirectory.value / "src" / "main" / "markdown"
marklitTargetDirectory := baseDirectory.value / "target" / "docs"
```

Tasks:

| Task | What it does |
| --- | --- |
| `marklitGenerate` | Render Markdown from `marklitSourceDirectory` into `marklitTargetDirectory`. |
| `marklitCompile` | Verify all blocks compile, but don't write output. |
| `marklitClean` | Remove the target directory. |

The plugin auto-passes your project's `fullClasspath` to marklit, so any dependency you've declared in `build.sbt` is available inside code fences. No separate dependency list to keep in sync.

A worked example lives in [examples/sbt/](examples/sbt/).

### Mill

```scala
//| mvnDeps:
//| - io.github.russwyte::mill-marklit:0.1.0-SNAPSHOT

import marklit.mill.MarklitModule

object docs extends ScalaModule with MarklitModule {
  def scalaVersion = "3.8.2"
}
```

Tasks: `marklitGenerate`, `marklitCheck`. Worked example in [examples/mill/](examples/mill/).

### CLI

The CLI is bundled inside both build plugins, but you can also run it standalone:

```sh
marklit docs/ --out target/docs/
```

Flags:

| Flag | Description |
| --- | --- |
| `--out`, `-o` | Output directory. |
| `--check`, `-c` | Verify without writing output. |
| `--watch`, `-w` | Re-process on file changes. |
| `--classpath`, `-cp` | Extra classpath entries (colon-separated). |
| `--deps`, `-d` | Coursier-style deps, e.g. `dev.zio::zio:2.1.26`. |
| `--repos`, `-r` | Extra Maven repositories. |
| `--show-version` | Render the Scala version on each block (default `true`). |
| `--verbose`, `-v` | Verbose logging. |

You can also declare dependencies inline in a Markdown file using [scala-cli](https://scala-cli.virtuslab.org/) `using` directives:

```markdown
//> using dep dev.zio::zio:2.1.26
//> using scala-options -feature -deprecation
```

These are merged with anything supplied on the command line.

## How compilation works

1. **Parse** the Markdown with fastparse — extract code fences and their modifier strings.
2. **Build the scope graph** from `id` / `extends` / `append` declarations, validate it, and group blocks by scope.
3. **Resolve the classpath**: if a `.bsp/` directory is present, marklit speaks BSP to your build server (sbt, Bloop, Mill) to pull build targets, classpath, and scalac options. Otherwise it falls back to a direct dotty driver invocation, with classpath supplied by the calling plugin or CLI.
4. **Compile** each block by wrapping it in `object MarklitWrapper { def run(): Unit = ... }`, inheriting the accumulated scope code, and feeding it to `dotty.tools.dotc`.
5. **Execute** under a child-first classloader to keep ZIO state isolated, capturing stdout via a marker-based redirect so output from prior blocks doesn't leak.
6. **Validate** outcomes against the modifiers (`fail` must produce a diagnostic; `crash` must throw; `warn` must warn).
7. **Render** the original Markdown back out, splicing in code, output, diagnostics, and exceptions per modifier rules.

## Modules

| Module | What it is |
| --- | --- |
| [core/](core/) | Parser, scope manager, document processor, renderer. Pure types, no compiler dependency. Published. |
| [compiler/](compiler/) | The Scala 3 compiler driver, the BSP client, classloader/output isolation. Published. |
| [cli/](cli/) | `zio-cli` front-end. Distributed as a fat jar bundled inside the build plugins. Not published. |
| [sbt-plugin/](sbt-plugin/) | `sbt-marklit` AutoPlugin. Published. |
| [mill-plugin/](mill-plugin/) | `mill-marklit` module trait. Built with Mill, not sbt. |

## Limitations

- **Scala 3 only.** The `scala=` modifier and the `scala-2` filter are wired through, but the actual Scala 2.13 compiler backend is not implemented. A `scala=2` block currently won't compile. Cross-build is planned (see [docs/plan.md](docs/plan.md)).
- **JVM only.** Scala.js and Scala Native backends are aspirational.
- **Markdown output only.** HTML / Docusaurus rendering is not built.
- **Watch mode** is wired up at the CLI flag level but not battle-tested.
- **Variable capture** — only stdout is rendered; expression result values are not yet picked up.

## Building from source

```sh
sbt publishAll          # build CLI fat jar + publish sbt plugin locally
```

Run the test suite:

```sh
sbt test
```

The compiler tests include a BSP integration suite that spins up an sbt build server in a testcontainer.

## Inspiration

marklit owes its shape to mdoc, and reuses mdoc's modifier vocabulary where it makes sense. The differences (multiple modifiers per block, scope inheritance with append, BSP-based classpath discovery, ZIO runtime, scoped-by-default semantics) are the parts worth comparing.
