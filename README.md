# marklit

**Typechecked Scala documentation that actually runs your code — across multiple Scala versions, in the same file.**

Write Markdown with Scala code fences. marklit compiles each fence against your real project classpath, executes it, and renders a new Markdown file with the actual output spliced in. Code that's supposed to fail can be asserted to fail. Code that's supposed to crash can be asserted to crash. And — uniquely — a single document can mix Scala 3.3.7, 3.7.3, 3.8.2, and 2.13.16 blocks side by side, each compiled by its own real compiler.

If your docs claim something works, marklit makes the build break when it doesn't.

## What sets marklit apart

marklit lives in [mdoc](https://scalameta.org/mdoc/)'s neighborhood. Many ideas — `silent`, `invisible`, `compile-only`, `fail`, `crash`, `passthrough` — are deliberately compatible. Where it diverges:

| Feature | mdoc | marklit |
| --- | :---: | :---: |
| Compile + execute Scala code in Markdown | ✓ | ✓ |
| Assert compile errors / runtime crashes | ✓ | ✓ |
| Auto-discover the project's classpath from the build tool | ✓ | ✓ |
| **Mix multiple Scala versions in one document** | ✗ | **✓** |
| **Per-block specific Scala version** (`scala=3.7.3`, `scala=2.13.16`) | ✗ | **✓** |
| **Named scopes with inheritance** (`id=foo`, `extends=foo`) | ✗ | **✓** |
| **Append to a named scope** (`extends=foo,append`) | ✗ | **✓** |
| Cross-built dependencies on per-major classpaths | ✗ | **✓** |
| **Built-in ZIO runtime** (`zio-app` modifier) | ✗ | **✓** |
| Multiple modifiers per block (`silent,id=setup`) | partial | **✓** |
| Scoped-by-default — blocks isolated unless you opt in | ✗ | **✓** |
| **Persistent on-disk block cache + warm-classloader daemon** | ✗ | **✓** |

The multi-version story is the one that pays for the project. You can write a migration guide that shows the *same code* compiled on 2.13 and 3.x, side by side, with both outputs verified by real compilers.

## A 60-second tour

````markdown
# Greeter

```scala marklit:id=base
case class Greeting(name: String, lang: String)
def greet(g: Greeting) = s"${g.lang}: hello, ${g.name}!"
```

```scala marklit:extends=base
println(greet(Greeting("Alice", "en")))
```

```scala marklit:extends=base,append
def shout(g: Greeting) = greet(g).toUpperCase
```

```scala marklit:extends=base
// sees `greet` AND `shout` — the previous block appended to `base`
println(shout(Greeting("Bob", "en")))
```

```scala marklit:fail
val x: String = 42  // expected to NOT compile
```

```scala marklit:crash
sys.error("boom")  // expected to throw
```
````

Run it (sbt):

```sh
sbt marklitGenerate
```

You get back the same Markdown with executed output spliced in, the `fail` block's compiler diagnostic embedded, and the `crash` block's exception captured.

## The killer feature: multi-version

Drop a `scala=<version>` modifier on a block and marklit resolves *that exact* `scala3-compiler` (or `scala-compiler` for 2.13) via Coursier, loads it on its own isolated classloader, and compiles the block against it.

````markdown
```scala marklit:scala=3.3.7
println(scala.util.Properties.versionNumberString)  // "3.3.7" actually
```

```scala marklit:scala=3.7.3
println(scala.util.Properties.versionNumberString)  // "3.7.3" actually
```

```scala marklit:scala=2.13.16
println(scala.util.Properties.versionNumberString)  // "2.13.16" actually
```
````

The `marklit-cli` jar is built once and bundles only thin shims against the dotc (3.x) and nsc (2.13) compiler APIs. Every per-version classloader gets a fresh copy of the *user-requested* compiler and its matching standard library — so user code is always compiled and run by the version they asked for, never by the bundled shim's version.

A worked example with five different versions in one file lives in [examples/base/src/main/markdown/scopes-and-versions.md](examples/base/src/main/markdown/scopes-and-versions.md).

### Cross-built dependencies, the right way

If your project cross-builds a sibling module (e.g. `core` published for both 2.13 and 3.x), the build plugin auto-detects this and forwards each major's classpath to marklit. A `scala=2.13.x` block reaches the 2.13 build of `core`; a default-major block reaches the 3.x build. No manual wiring.

````markdown
```scala
// Default 3.x — uses core_3
println(example.Greeter.hello("Scala 3"))
```

```scala marklit:scala=2.13.16
// Cross-version — uses core_2.13
println(example.Greeter.hello("Scala 2.13"))
```
````

Both blocks reference `example.Greeter`. Both compile. Both produce real output from the real compiled jar.

## Performance

Compiling Scala (especially across multiple versions) is expensive. marklit has two layers of caching that make warm runs roughly **3-4× faster** than cold runs on the included examples:

- **Long-lived daemon JVM.** The build plugins talk to a marklit subprocess over JSON-RPC instead of spawning a fresh JVM per task. Per-version compiler classloaders stay warm across `marklitGenerate` invocations within a build session. Cold-start of a new Scala version is ~1-2s; subsequent compiles against the same version reuse that loader.
- **Persistent SHA-256 block cache.** Every block's compiled `.class` files are stored on disk keyed by a hash of `(code, prior code, scalaVersion, classpath, scalac options, …)`. A cache hit skips both compile and re-emit — execution loads the cached class files directly. Cache lives at `target/marklit-cache/` (sbt) or `out/<module>/marklitCacheDir.dest/marklit-cache/` (Mill); both plugins clean it as part of `<proj>/marklitClean`.

Cold-vs-warm on [examples/sbt/](examples/sbt/) (4 markdown files, 46 blocks across 4 Scala versions): **~20s → ~6s**. Mill: **~18s → ~4s**. Same machine, no other changes.

Both layers are on by default. To disable the daemon: `marklitDaemon := false` (sbt) / `def marklitDaemonEnabled = false` (Mill). To disable the disk cache: `marklitCacheDirectory := None` (sbt) / `def marklitCacheDir = None` (Mill).

## Modifiers

Code fences with the info string `scala marklit:<modifiers>` are processed. Modifiers are comma-separated and freely combined.

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
| `zio-app` | Wrap the block as a ZIO program and run it via ZIO's `Runtime.unsafe`. |
| `id=<name>` | Name this block's scope. |
| `extends=<name>` | Create a child scope inheriting from `<name>`. |
| `extends=<name>,append` | Append to `<name>` instead of branching. Subsequent `extends=<name>` blocks see the appended code. |
| `scala=<bare-major>` | Filter the block to a Scala major (`scala=2`, `scala=3`). Skipped at runtimes that don't match. |
| `scala=<specific-version>` | Compile this block against an exact version (`scala=3.7.3`, `scala=2.13.16`). |
| `scala=shared` | Compile and run against *every* Scala version in use, and prepend the code to every per-version default scope. Renders one output if all versions agree, per-version labeled output otherwise. |
| `scala=shared-2` / `scala=shared-3` | Like `scala=shared`, but restricted to a single Scala major. |

Examples: `silent,id=setup`, `fail,extends=errors`, `zio-app,scala=3.8.2`. `id` and `append` are mutually exclusive; `append` requires `extends`.

See [examples/base/src/main/markdown/tutorial.md](examples/base/src/main/markdown/tutorial.md) for a worked example of every feature.

## How marklit reads your Markdown

Marklit is **not** a full Markdown processor. It scans for fenced code blocks and treats everything else as opaque text — headings, lists, tables, links, HTML, your blank lines and trailing whitespace all flow through verbatim. The renderer's job is to splice executed output into the right places, not to rewrite your prose.

Fence detection follows [CommonMark](https://spec.commonmark.org/0.31.2/#fenced-code-blocks):

- Opener may be indented **0–3 spaces**. 4+ leading spaces is an indented code block, not a fence.
- Fence character is `` ` `` or `~`, repeated **at least 3 times**.
- The closing fence must use the **same character** as the opener and be **at least as long**. So a fence opened with ```` ```` ```` only closes on a line of 4+ backticks — inner ``` ``` ``` lines are content.
- The closing line may have trailing spaces/tabs but **no other content** after the fence.
- Backtick-fence info strings may not contain `` ` ``.
- An **unterminated fence is implicitly closed at EOF** — your file doesn't have to end with a closing fence.
- Content lines have up to *opener-indent* leading spaces stripped, so an indented opener doesn't smuggle indentation into your code.
- `\r\n` and lone `\r` line endings are normalized to `\n` before parsing.

The info string identifies a Scala block by the literal token `scala` (case-insensitive) followed by a word boundary, so `scala-cli`, `scalafmt`, and `scalajs` blocks are **not** treated as Scala — they pass through unchanged. The token list after the language is the modifiers (see [Modifiers](#modifiers)); both `marklit:` and `mdoc:` prefixes are accepted.

### Fences inside blockquotes are rejected

Marklit refuses to extract Scala code from inside a `>` blockquote:

````markdown
> ```scala
> val x = 1
> ```
````

This produces a parse error directing you to move the fence outside the blockquote (or strip the `>` prefix from the fence and its content lines). The reason is that splicing executed output back into a blockquote would silently break the quote structure — better to fail loudly. Non-Scala fences inside blockquotes (e.g. `> ```bash`) are unaffected and pass through.

## Scopes — explicit by default

By default each block gets a fresh anonymous scope. Code blocks do **not** share state unless you tell them to. This inverts mdoc's default and is closer to mdoc's `:reset` semantics out of the box. The reasoning: most documentation snippets are *examples* that should stand alone; sharing state is the exception, not the rule.

To opt in:

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
// sees User AND validate
println(validate(User("Bob", -1)))
```
````

`extends` without `id` is an anonymous child — useful for one-off blocks that need parent context but won't themselves be extended. Independent scope branches are tracked so they can be compiled in parallel.

Cross-version `extends` is rejected: a `scala=3` scope cannot extend a `scala=2` scope.

### Page scope (mdoc-style sharing, opt-in at the build level)

For small, focused documents where every block builds on the previous one, the explicit-id discipline can be noise. **Page scope** is a build-level flag that makes every anonymous block in a file share state, exactly as if you'd written `id=__page__<scala-version>` on the first one and `extends=__page__<scala-version>,append` on the rest.

Enable it once:

- **sbt:** `marklitPageScope := true`
- **Mill:** `def marklitPageScope = true`
- **CLI:**  `marklit --page-scope ...`

Then anonymous blocks in the same file (per Scala version) accumulate state without any modifiers. Page scope only rewrites anonymous blocks — `id=`, `extends=`, `passthrough`, `scala=shared`, `fail`, `crash`, and `warn` always win. See [examples/base/src/main/markdown/page-scope.md](examples/base/src/main/markdown/page-scope.md) for a worked example.

If you want to render some files page-scoped and others isolated within the same project, define a sibling task (sbt) or module (Mill) that overrides `marklitPageScope` and points `marklitSourceDirectory` (or `marklitSourceDir` in Mill) at a different folder — both build tools let you reconfigure plugin tasks freely. There's no need to choose one mode for the whole build.

Default remains off: marklit's per-block isolation is the better default for reference docs where each block stands alone. Flip page scope on per-project, per-module, or per-task when the document genuinely reads as one continuous session.

## Build tool integration

### sbt

```scala
// project/plugins.sbt
addSbtPlugin("io.github.russwyte" % "sbt-marklit" % "0.1.0-SNAPSHOT")
```

```scala
// build.sbt
lazy val docs = project
  .dependsOn(core)  // your normal project dep
  .settings(
    scalaVersion := "3.8.2",
    marklitSourceDirectory := baseDirectory.value / "src" / "main" / "markdown",
    marklitTargetDirectory := baseDirectory.value / "target" / "docs"
  )
```

Tasks and commands:

| Name | What it does |
| --- | --- |
| `marklitGenerate` *(command)* | Cross-compile any cross-built deps, then render Markdown from every marklit-enabled project. Single command, frictionless from a clean checkout. |
| `marklitCompile` *(command)* | Same flow as above but verify-only (no rendered output). |
| `<proj>/marklitGenerate` *(task)* | Render output for one project. Assumes cross-built deps are already compiled — use the build-level command above when you want auto-cross-compile. |
| `<proj>/marklitCompile` *(task)* | Verify-only sibling of the task above. |
| `<proj>/marklitClean` *(task)* | Remove the target directory and persistent block cache. |

The plugin auto-passes your project's `fullClasspath` to marklit, so any dependency you've declared in `build.sbt` is available inside code fences. **For cross-built deps**, the plugin walks your `dependsOn` graph, finds any sibling project with a multi-entry `crossScalaVersions`, and forwards each major's classpath as `--classpath-2` / `--classpath-3`. The build-level `marklitGenerate` command schedules `+ depProj/compile` for each cross-built dep before invoking the docs task, so a clean checkout works in a single `sbt marklitGenerate`.

A worked multi-version example lives in [examples/sbt/](examples/sbt/).

### Mill

```scala
//| mvnDeps:
//| - io.github.russwyte::mill-marklit:0.1.0-SNAPSHOT

import marklit.mill.MarklitModule

val scala3 = "3.8.2"
val scala2 = "2.13.16"

object core extends Cross[CoreModule](Seq(scala2, scala3))
trait CoreModule extends CrossSbtModule

object docs extends ScalaModule with MarklitModule {
  def scalaVersion = scala3
  override def moduleDeps = Seq(core(scala3))
  override def marklitCrossModuleDeps = core.crossModules
}
```

Tasks: `docs.marklitGenerate`, `docs.marklitCheck`. Set `marklitCrossModuleDeps` to your cross-built deps' `.crossModules` and the plugin handles per-major classpath bucketing automatically.

A worked example lives in [examples/mill/](examples/mill/).

### CLI

The CLI is bundled inside both build plugins, but you can also run it standalone:

```sh
marklit docs/ --out target/docs/
```

Common flags:

| Flag | Description |
| --- | --- |
| `--out`, `-o` | Output directory. |
| `--check`, `-c` | Verify without writing output. |
| `--watch`, `-w` | Re-process on file changes. |
| `--scala-version` | Default Scala version for blocks without a `scala=` modifier. |
| `--classpath`, `-cp` | Default classpath (colon/semicolon-separated). |
| `--classpath-2` | Classpath used when compiling Scala 2.x blocks. |
| `--classpath-3` | Classpath used when compiling Scala 3.x blocks. |
| `--deps`, `-d` | Coursier-style deps, e.g. `dev.zio::zio:2.1.26`. |
| `--repos`, `-r` | Extra Maven repositories. |
| `--no-show-version` | Suppress the `// Scala x.y.z` annotation on output blocks. |
| `--cache-dir` | Persistent on-disk block cache directory (off by default; both build plugins enable it automatically). |
| `--page-scope` | Share scope across all anonymous blocks in each file (per Scala version). Off by default. |
| `--verbose`, `-v` | Verbose logging. |

You can also declare dependencies inline in a Markdown file using [scala-cli](https://scala-cli.virtuslab.org/) `using` directives:

```markdown
//> using scala 3.8.2
//> using dep dev.zio::zio:2.1.26
//> using options -feature -deprecation
```

Precedence (highest to lowest): per-block `scala=<specific>` → in-source `//> using scala` → CLI `--scala-version` → bundled shim's compile-time version.

## How it actually works

1. **Parse** the Markdown with fastparse — extract code fences and modifier strings.
2. **Build the scope graph** from `id` / `extends` / `append`, validate it (no cycles, no cross-major inheritance), group blocks by scope.
3. **For each requested Scala version**: ask the `CompilerFactory` for a compiler. If it's not the default, the factory Coursier-resolves `scala3-compiler_3:<version>` (or `scala-compiler:<version>` for 2.13), builds a `URLClassLoader` from those JARs, and reflectively invokes the version-stable shim that lives on that loader. Compilers are cached by version.
4. **Compile** each block by wrapping it in a synthetic top-level object and feeding the prior-scope code + block code to dotc/nsc through the shim.
5. **Execute** under a child-first classloader, capturing stdout via a marker-based redirect so output from prior blocks doesn't leak.
6. **Validate** outcomes against the modifiers.
7. **Render** the original Markdown back out, splicing in code, output, diagnostics, and exceptions per modifier rules.

The per-version classloader pattern is the same one Bloop and Metals use to host arbitrary Scala compilers without polluting their own runtime classpath.

## Modules

| Module | What it is |
| --- | --- |
| [core/](core/) | Parser, scope manager, document processor, renderer. Pure types, no compiler dependency. Published. |
| [compiler-api/](compiler-api/) | Java-only interfaces between marklit and the per-version compiler shims. |
| [compiler-shim/](compiler-shim/) | Thin shim against `dotty.tools.dotc.*`. Pinned to the oldest 3.x we support; the API surface is stable across 3.x. |
| [compiler-shim-2/](compiler-shim-2/) | Thin shim against `scala.tools.nsc.*` for 2.13. |
| [compiler/](compiler/) | Orchestration: Coursier-based `CompilerFactory`, classloader management, ZIO layers. Does not directly depend on `scala3-compiler` or `scala-compiler`. |
| [cli/](cli/) | `zio-cli` front-end. Distributed as a fat jar bundled inside the build plugins. |
| [sbt-plugin/](sbt-plugin/) | `sbt-marklit` AutoPlugin. Published. |
| [mill-plugin/](mill-plugin/) | `mill-marklit` module trait. Built with Mill. Published. |

## Limitations

- **JVM only.** Scala.js and Scala Native backends are not supported.
- **Markdown output only.** HTML / Docusaurus rendering is not built.
- **Watch mode** is wired up at the CLI flag level but is not battle-tested.
- **Variable capture** — only stdout is rendered; expression result values are not yet picked up.
- **Scala 2.12 and earlier are not supported.** Cross-version blocks are 2.13.x and 3.x only.

## Building from source

```sh
sbt publishAll                 # build CLI fat jar + publish sbt plugin locally
(cd mill-plugin && mill plugin.publishLocal) # publish mill plugin locally
sbt test                       # full test suite
```

## Inspiration

marklit owes its shape to [mdoc](https://scalameta.org/mdoc/) and reuses mdoc's modifier vocabulary where it makes sense. The differences — multiple modifiers per block, scope inheritance with append, scoped-by-default semantics, real per-block multi-version compilation, ZIO runtime, cross-built dependency awareness — are the parts worth comparing.

## License

[Apache 2.0](LICENSE)
