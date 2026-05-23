# marklit - A Better Scala Documentation Tool

## Vision

marklit is a next-generation typechecked documentation tool for Scala that addresses the limitations of mdoc while maintaining its strengths. The core philosophy is **flexibility without complexity** - offering powerful configurability through intuitive syntax.

## Key Differentiators from mdoc

| Feature | mdoc | marklit |
|---------|------|---------|
| Modifiers per block | Single | Multiple (varargs-style) |
| App scopes per file | One | Multiple (explicit IDs) |
| Reset handling | Explicit modifier | Implicit via scope IDs |
| Build integration | Manual classpath config | BSP auto-discovery |
| Output formats | Markdown | Markdown (HTML planned) |
| Target platforms | Primarily JVM | JVM, Scala.js, Scala Native |

## Core Concepts

### 1. Scope System

An **app scope** is an isolated compilation context. Scopes form a tree structure with inheritance and append semantics.

#### Scope Configuration

```scala
enum ScopeStrategy:
  case Isolated  // default - each block gets unique anonymous scope
  case Shared    // all blocks share file-level scope (mdoc compatibility)
```

Configurable globally or per-file via directive.

#### Scope Modifiers

| Modifier | Description |
|----------|-------------|
| `id=<name>` | Names this block's scope |
| `extends=<name>` | Inherits from named scope (creates anonymous child) |
| `append` | Appends to parent scope instead of creating child (requires `extends`) |
| `scala=<version>` | Compiler version for this block (e.g., `scala=2.13`, `scala=3`) |

**Rules:**
- `id` and `append` are mutually exclusive (invalid to use together)
- `extends` without `id` creates anonymous child scope (can't be extended further)
- `extends` with `append` mutates the parent scope (lexical order matters)
- `id` with `extends` creates a named child scope
- `scala=<version>` sets compiler version; scopes cannot extend across versions
- Default Scala version comes from config (falls back to project's scalaVersion)

#### Examples

```markdown
```scala marklit:id=base
case class User(name: String, age: Int)
```

```scala marklit:extends=base,append
// Appends to base - now base includes validate
def validate(u: User): Boolean = u.age > 0
```

```scala marklit:id=json,extends=base
// Named child scope - inherits User + validate, can be extended
import io.circe.*
given Codec[User] = deriveCodec
```

```scala marklit:extends=json
// Anonymous child of json - one-off usage
User("Alice", 30).asJson
```

```scala marklit:id=errors,extends=base
// Separate branch - inherits from base (with validate)
val invalid = User("", -1)
validate(invalid) // false
```
```

**Resulting scope tree:**
```
base (User, validate)
├── json (+ Codec)
│   └── (anonymous)
└── errors
```

#### Multi-Version Support

For docs comparing Scala 2 and Scala 3 syntax (e.g., migration guides):

```markdown
## Scala 2 implicits

```scala marklit:scala=2.13
implicit val ord: Ordering[Int] = Ordering.Int
def sorted[A](xs: List[A])(implicit o: Ordering[A]): List[A] = xs.sorted
```

## Scala 3 givens

```scala marklit:scala=3
given Ordering[Int] = Ordering.Int
def sorted[A](xs: List[A])(using Ordering[A]): List[A] = xs.sorted
```
```

**Rules:**
- Both compilers loaded when a file uses both versions
- Scopes are **version-isolated** - cannot `extends` across Scala versions
- Each version maintains its own scope trees

### 2. Varargs Modifiers

Multiple modifiers can be applied to a single code block using comma-separated syntax:

```markdown
```scala marklit:silent,compile-only
// This code is compiled but neither executed nor shown in output
```

```scala marklit:fail,scope=errors
// Expected to fail compilation, within the 'errors' scope
val x: String = 42
```

```scala marklit:crash,scope=runtime
// Expected to throw at runtime
throw new RuntimeException("boom")
```
```

### 3. Supported Modifiers

| Modifier | Description |
|----------|-------------|
| (none) | Compile, execute, show code and output |
| `silent` | Compile and execute, show only code |
| `invisible` | Compile and execute, show nothing |
| `fail` | Assert compilation failure |
| `warn` | Assert compilation warnings |
| `crash` | Assert runtime exception |
| `compile-only` | Compile but don't execute |
| `passthrough` | Render content as-is (no processing) |
| `scope=<id>` | Assign to named app scope |

**Modifier combinations:**
- `silent,scope=shared` - silent output in shared scope
- `fail,scope=errors` - expected failure in error-demo scope
- `invisible,scope=setup` - hidden setup code in shared scope

## Architecture

### High-Level Design

```
┌─────────────────────────────────────────────────────────────┐
│                        marklit                               │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │   Parser    │  │  Compiler   │  │     Renderer        │  │
│  │ (Markdown)  │──│  (Scala)    │──│ (Markdown/HTML/...) │  │
│  └─────────────┘  └─────────────┘  └─────────────────────┘  │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────────────────────────────────────────────────┐│
│  │              Platform Abstraction Layer                 ││
│  │         (JVM / Scala.js / Scala Native)                 ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
```

### Module Structure

```
marklit/
├── core/              # Platform-agnostic types and interfaces
│   └── src/main/scala/
│       ├── model/     # Modifier, CodeBlock, AppScope, ScopeConfig
│       ├── parser/    # Markdown parsing (code fence extraction)
│       └── api/       # Public API traits
│
├── compiler/          # Scala compilation (cross-built)
│   └── src/main/
│       ├── scala/         # Shared: Compiler trait, CompileResult, etc.
│       ├── scala-2.13/    # Scala2Compiler (wraps nsc.Global)
│       └── scala-3/       # Scala3Compiler (wraps dotc.Compiler)
│
├── runtime/           # Execution and output capture
│
├── renderer/          # Output generation
│   └── markdown/      # Markdown output (HTML future)
│
├── cli/               # Command-line interface
├── sbt-plugin/        # sbt integration (auto-detects scalaVersion)
├── mill-plugin/       # Mill integration (future)
└── docs/              # Documentation (dogfooding!)
```

### BSP Integration (Build Server Protocol)

marklit uses BSP to integrate with the user's build tool, providing significant advantages over mdoc's manual classpath configuration:

#### How It Works

```
┌─────────────────────────────────────────────────────────────┐
│                        marklit                               │
├─────────────────────────────────────────────────────────────┤
│  1. Check .bsp/ for connection files                        │
│  2. Connect to build server (sbt, Mill, Bloop, etc.)        │
│  3. Query build targets, classpath, scalac options          │
│  4. Compile code blocks against real project dependencies   │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│              User's Build Server (via BSP)                  │
│         sbt | Mill | Bloop | Gradle | Maven                 │
└─────────────────────────────────────────────────────────────┘
```

#### Advantages Over mdoc

| Aspect | mdoc | marklit (BSP) |
|--------|------|---------------|
| Classpath | Manual config in sbt plugin | Auto-discovered from build |
| Dependencies | Must add to mdoc separately | Uses project's real dependencies |
| Scalac options | Configured separately | Inherited from build |
| Source dependencies | Limited | Full support (compile against unpublished code) |
| Build tool support | sbt-centric | Any BSP-compatible tool |
| Config sync | Must keep in sync manually | Always up-to-date |

#### Example

```scala
// build.sbt
libraryDependencies += "io.circe" %% "circe-generic" % "0.14.5"
```

```markdown
// docs/example.md - just works, no extra configuration needed
```scala mdoc
import io.circe.*, io.circe.generic.auto.*, io.circe.syntax.*
case class User(name: String, age: Int)
User("Alice", 30).asJson  // compiles against project's circe dependency
```
```

#### Fallback Strategy

1. **BSP available** (`.bsp/` exists) → Connect to user's build server
2. **No BSP** → Fall back to bundled Bloop or direct compiler with manual classpath

### Cross-Build Strategy

Using sbt's [version-specific source directories](https://www.scala-sbt.org/1.x/docs/Cross-Build.html#Scala-version+specific+source+directory):

- **Shared code** in `src/main/scala/` - Compiler trait, types, ZIO effects
- **Scala 2.13** in `src/main/scala-2.13/` - wraps `scala.tools.nsc.Global`
- **Scala 3** in `src/main/scala-3/` - wraps `dotty.tools.dotc.Compiler`

```scala
// build.sbt
lazy val compiler = project
  .settings(
    crossScalaVersions := Seq("2.13.12", "3.3.7"),
    libraryDependencies ++= {
      if (scalaVersion.value.startsWith("2."))
        Seq("org.scala-lang" % "scala-compiler" % scalaVersion.value)
      else
        Seq("org.scala-lang" %% "scala3-compiler" % scalaVersion.value)
    }
  )
```

**Plugin artifact resolution:** sbt-marklit automatically selects the compiler artifact matching the user's `scalaVersion` - users don't need to configure this.

### Key Dependencies

| Dependency | Purpose |
|------------|---------|
| **ZIO 2.x** | Effects, resource management, parallelism |
| **zio-test** | Testing framework |
| **fastparse** | Markdown/modifier parsing |
| **testcontainers** | Integration tests (see mechanoid for ZIO patterns) |
| **BSP4j** | BSP client protocol (Java, but standard - used by Metals/Bloop/Mill) |
| **scala-compiler / scala3-compiler** | Fallback compilation (when no BSP) |

**Note:** BSP4j is Java-based but is the ecosystem standard. We'll wrap it in a ZIO-friendly interface. See `mechanoid/postgres/src/test/scala/mechanoid/PostregesTestContainer.scala` for testcontainers + ZIO patterns.

### Core Types

```scala
// Modifier ADT
enum Modifier:
  case Silent
  case Invisible
  case Fail
  case Warn
  case Crash
  case CompileOnly
  case Passthrough

// Scope configuration on a block
case class ScopeConfig(
  id: Option[String],       // id=<name>
  extends: Option[String],  // extends=<name>
  append: Boolean           // append flag
)

// Parsed code block
case class CodeBlock(
  code: String,
  modifiers: Set[Modifier],
  scopeConfig: ScopeConfig,
  location: Location
)

// App scope - node in scope tree
case class AppScope(
  id: String,
  parent: Option[String],
  blocks: Vector[CodeBlock]
)

// Typed errors
enum MarklitError:
  case ParseError(location: Location, message: String)
  case CompileError(location: Location, errors: List[ScalaError])
  case RuntimeError(location: Location, exception: Throwable)
  case ValidationError(location: Location, expected: Modifier, actual: Result)

// Compilation result
enum CompilationResult:
  case Success(output: String, warnings: List[Warning])
  case Failed(errors: List[ScalaError])
```

### ZIO Architecture

```scala
// Compiler as a service - provided via ZLayer
trait Compiler:
  def compile(code: String, context: ScopeContext): IO[CompileError, CompileResult]
  def execute(compiled: CompiledCode): IO[RuntimeError, ExecutionResult]

object Compiler:
  // Singleton - serialized access (MVP)
  val singleton: ZLayer[Scope, Nothing, Compiler]
  
  // Pooled - parallel compilation (production)
  def pooled(size: Int): ZLayer[Scope, Nothing, Compiler]
  
  // Platform-specific (future)
  val jvm: ZLayer[Scope, Nothing, Compiler]
  val scalaJs: ZLayer[Scope, Nothing, Compiler]
  val native: ZLayer[Scope, Nothing, Compiler]

// Scope compilation - parallelizes independent branches
def compileFile(file: ParsedFile): ZIO[Compiler, MarklitError, CompiledFile] =
  for
    graph    <- ZIO.succeed(buildScopeGraph(file.blocks))
    compiled <- graph.traversePar(compileScope)  // parallel where possible
  yield CompiledFile(compiled)
```

### Processing Pipeline

1. **Parse** - Extract code blocks from markdown, parse modifier syntax
2. **Group** - Organize blocks by scope ID
3. **Compile** - For each scope, compile blocks incrementally
4. **Execute** - Run code (unless `compile-only`)
5. **Validate** - Check modifiers against results (`fail`, `crash`, `warn`)
6. **Render** - Generate output document

## Implementation Plan

### Phase 1a: Compilation Foundation

**Goal:** Prove we can compile and execute Scala code via BSP

1. **BSP client** - Connect to build server, discover connection files in `.bsp/`
2. **Build target discovery** - Query available targets, select appropriate one
3. **Classpath/scalac resolution** - Get dependency classpath and compiler options
4. **Compile service** - Submit code to compiler, capture diagnostics
5. **Execution service** - Run compiled code, capture stdout/output values
6. **ZIO integration** - Wrap as `ZLayer[Compiler]` with typed errors

**Testing:**
- [ ] Connect to sbt BSP server in a test project
- [ ] Connect to Mill BSP server
- [ ] Connect to Bloop directly
- [ ] Compile simple `val x = 1 + 1` and verify result
- [ ] Compile code using project dependencies (e.g., circe, zio)
- [ ] Capture compilation errors with line numbers
- [ ] Execute code and capture `println` output
- [ ] Execute code and capture expression results
- [ ] Test Scala 2.13 compilation
- [ ] Test Scala 3 compilation
- [ ] Test fallback when no BSP available

**Deliverable:** `Compiler` service that can compile and run arbitrary Scala code strings against a real project's classpath

---

### Phase 1b: Scope System

**Goal:** Implement scope accumulation and inheritance

1. **Scope context** - Track accumulated definitions per scope
2. **Scope tree** - Build parent/child relationships from `extends`
3. **Append semantics** - Mutate parent scope when `append` specified
4. **Version isolation** - Separate scope trees per Scala version
5. **Parallel compilation** - Compile independent branches concurrently

**Testing:**
- [ ] Single scope with multiple code blocks (definitions accumulate)
- [ ] Named scope with `id=`
- [ ] Child scope with `extends=` (inherits parent definitions)
- [ ] Append with `extends=foo,append` (mutates parent)
- [ ] Verify `id` + `append` is rejected as invalid
- [ ] Independent scopes compile in parallel
- [ ] Cross-version `extends` is rejected

**Deliverable:** `ScopeManager` that correctly accumulates and inherits definitions

---

### Phase 1c: Parsing & Rendering

**Goal:** Extract code blocks from markdown and produce output

1. **Markdown parser** - Extract code fences with info strings
2. **Modifier parser** - Parse `marklit:silent,id=foo,extends=bar` syntax
3. **Validation** - Check modifier combinations are valid
4. **Markdown renderer** - Replace code blocks with compiled output
5. **Error formatting** - Map compiler errors to markdown line numbers

**Testing:**
- [ ] Parse basic code fence
- [ ] Parse multiple modifiers (comma-separated)
- [ ] Parse scope config (`id=`, `extends=`, `append`, `scala=`)
- [ ] Reject invalid modifier combinations
- [ ] Render successful compilation with output
- [ ] Render `silent` blocks (no output shown)
- [ ] Render `fail` blocks (show compilation error)
- [ ] Error positions map to markdown source

**Deliverable:** End-to-end processing of a markdown file

---

### Phase 1d: CLI & Integration

**Goal:** Usable command-line tool

1. **CLI argument parsing** - Input/output paths, options
2. **File discovery** - Find markdown files in input directory
3. **Watch mode** - Re-process on file changes
4. **Configuration** - Site variables, scope strategy setting

**Deliverable:** `marklit docs/ --out target/docs/` works

### Phase 2: Robustness

**Goal:** Production-quality error handling and user experience

1. **Error mapping** - Map compiler errors back to markdown line numbers
2. **Watch mode** - File watching with incremental recompilation
3. **Diagnostics** - Clear error messages for invalid modifiers/syntax
4. **Configuration** - Site variables, compiler options, custom scalac flags

### Phase 3: Integration

**Goal:** Ecosystem integration

1. **sbt plugin** - `sbt-marklit` for seamless build integration
2. **Mill plugin** - Mill build tool support
3. **IDE support** - Basic LSP integration for preview

### Phase 4: Multi-platform

**Goal:** Support all Scala platforms

1. **Scala.js backend** - Compile and run in Node.js/browser
2. **Scala Native backend** - Native compilation support
3. **Cross-platform testing** - Verify examples across platforms

### Phase 5: Extended Rendering

**Goal:** Rich output formats

1. **HTML renderer** - Standalone HTML output
2. **Docusaurus integration** - MDX-compatible output
3. **Custom renderers** - Plugin API for custom output formats

## Configuration

### CLI Interface

```bash
# Basic usage
marklit docs/ --out target/docs/

# With options
marklit docs/ \
  --out target/docs/ \
  --watch \
  --scala-version 3.3.7 \
  --site.VERSION 1.0.0 \
  --classpath "lib/*"
```

### Programmatic API

```scala
import marklit.*

val settings = Settings(
  inputDir = Path("docs"),
  outputDir = Path("target/docs"),
  scalaVersion = "3.3.7",
  siteVariables = Map("VERSION" -> "1.0.0"),
  classpath = List(Path("lib"))
)

Marklit.process(settings) match
  case Result.Success(files) => println(s"Processed ${files.size} files")
  case Result.Failure(errors) => errors.foreach(println)
```

## Compatibility Notes

### mdoc Migration

marklit aims for easy migration from marklit:

| mdoc syntax | marklit equivalent |
|-------------|-------------------|
| `` ```scala mdoc `` | `` ```scala mdoc `` (same) |
| `` ```scala marklit:silent `` | `` ```scala marklit:silent `` (same) |
| `` ```scala marklit:reset `` | `` ```scala marklit:scope=unique123 `` (auto-generated) |
| `` ```scala marklit:fail `` | `` ```scala marklit:fail `` (same) |
| `` ```scala marklit:crash `` | `` ```scala marklit:crash `` (same) |

**Key difference:** Without explicit scope, each block gets its own isolated scope by default (like mdoc's `reset`). Use `scope=<id>` to share context between blocks.

### Breaking Changes from mdoc

1. **Default isolation** - Blocks are isolated by default (mdoc shares by default)
2. **No `reset` modifier** - Use explicit `scope=<id>` instead
3. **Modifier syntax** - Comma-separated for multiple modifiers

## Design Decisions (Resolved)

1. **Default scope behavior** - Configurable via `ScopeStrategy.Isolated` (default) or `ScopeStrategy.Shared`. See Scope System section.

2. **Scope inheritance** - Use `id=<name>` to name scopes, `extends=<name>` to inherit, `append` to mutate parent. `id` and `append` are mutually exclusive. See Scope System section.

3. **Output format** - Deferred until HTML rendering (Phase 5). Markdown only for now.

4. **Parallel compilation** - Yes, design for parallelism from the start using ZIO. Independent scope branches compile concurrently. Compiler provided as ZLayer (singleton for MVP, pooled for production). See ZIO Architecture section.

5. **Effect system** - ZIO for typed errors, resource management, and structured concurrency.

6. **Build integration** - BSP (Build Server Protocol) for automatic classpath/scalac discovery. Falls back to bundled Bloop or direct compiler when no BSP available. Major advantage over mdoc's manual configuration approach.

## Success Criteria

- [ ] Process markdown with multiple modifiers per block
- [ ] Support multiple named scopes per file
- [ ] Accurate error mapping to source locations
- [ ] Watch mode with <500ms incremental compilation
- [ ] JVM, Scala.js, and Scala Native support
- [ ] sbt plugin for build integration
- [ ] 100% mdoc feature parity (except deprecated features)
