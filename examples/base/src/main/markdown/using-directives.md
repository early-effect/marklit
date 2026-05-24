# Other `//> using` Directives

How to control compilation beyond just adding dependencies — scalac
options, the Scala version itself, and custom resolvers. For
dependency directives (`dep`, `deps`, etc.) see
[using-deps.md](using-deps.md).

## Compiler options: `//> using option`

A single scalac option:

```scala
//> using option -deprecation

@deprecated("use newMethod", "1.0")
def oldMethod(): Unit = println("old")

oldMethod()
```

The compiler emits a deprecation warning, which marklit shows above
the runtime output by default.

### Plural form: `//> using options`

The plural form takes a list of flags. `//> using scalac` is an alias
of `option`.

```scala
//> using options -deprecation, -feature

@deprecated("use newMethod", "1.0")
def oldOther(): Unit = println("warned")

oldOther()
```

### Suppressing warnings on a single block: `show-warnings=false`

Sometimes a deprecation is intentional and the warning is noise. Add
`show-warnings=false` to the fenced info string to hide warnings for
just that block, even when warnings are enabled globally:

```scala marklit:show-warnings=false
//> using option -deprecation

@deprecated("legacy adapter, scheduled for removal in 2.0", "1.0")
def legacyAdapter(): Unit = println("quiet")

legacyAdapter()
```

The deprecation still fires at compile time — the `show-warnings=false`
override only affects rendering. Precedence is: per-block override >
global config (`--show-warnings=true|false` / `marklitShowWarnings`) >
default-on. Inversely, `show-warnings=true` opts a single block back in
when the global config is off.

The `marklit:warn` modifier (see [tutorial.md](tutorial.md)) renders
warnings unconditionally — its job is to *assert* warnings exist, so
neither layer of `show-warnings` overrides it.

## File-level Scala version: `//> using scala`

`//> using scala <version>` sets the *file's* default Scala version.
Every block in the file that doesn't carry a per-block `scala=` info
string compiles against this version. Any value Coursier can resolve
works (e.g. `3.7.3`, `3.6.4`, `2.13.16`).

```scala
//> using scala 3.7.3

println(s"file default")
```

The per-block `scala=` info string (`marklit:scala=3.6.4` etc.) overrides
this for one block. See [scopes-and-versions.md](scopes-and-versions.md)
for the cross-version story.

## Custom resolvers: `//> using repo`

Add a Maven repository to the resolver. `//> using repos` is the plural
form, comma-separated. The block below is `compile-only` so the example
doesn't depend on actual snapshot availability at doc-render time.

```scala marklit:compile-only
//> using repo https://oss.sonatype.org/content/repositories/snapshots
//> using dep dev.zio::zio:2.1.25

println("resolved against custom repo")
```

## See also

- [using-deps.md](using-deps.md) — `dep` and `deps` directives.
- [scopes-and-versions.md](scopes-and-versions.md) — per-block Scala
  version with `scala=` info-string option.
