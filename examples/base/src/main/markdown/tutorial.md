# Marklit Tutorial

A tour of marklit's single-version, single-scope features. Once you're
comfortable here, follow the cross-links to the topic-specific docs:
- [using-deps.md](using-deps.md) — external libraries.
- [using-directives.md](using-directives.md) — compiler options,
  Scala version, custom resolvers.
- [scopes-and-versions.md](scopes-and-versions.md) — sharing state
  across blocks; running blocks under different Scala versions.
- [zio-example.md](zio-example.md) — `marklit:zio-app` recipe.

## Basic code execution

Code blocks are compiled and executed; their output is rendered below
the source:

```scala
val greeting = "Hello, Marklit!"
println(greeting)
```

## Sharing state with named scopes

Each block is its own scope by default — definitions in one block do
**not** leak into the next. To build up state across blocks, name the
first one with `id=` and have follow-ups `extends=` it:

```scala marklit:id=people
case class Person(name: String, age: Int)
```

```scala marklit:extends=people
val alice = Person("Alice", 30)
println(s"${alice.name} is ${alice.age} years old")
```

For the full scope-mechanics tour (multi-level inheritance, `append`,
per-version scopes), see
[scopes-and-versions.md](scopes-and-versions.md).

## `silent`: hide output, keep code

`marklit:silent` compiles and executes, shows the source, but hides the
runtime output. Useful when the output is noisy and you only care about
the code shape:

```scala marklit:silent,id=secret
val secretValue = 42
println("This output is hidden")
```

Pair `silent` with `id=` so a follow-up block can reach the value:

```scala marklit:extends=secret
println(s"The secret is: $secretValue")
```

## `invisible`: hide everything

`marklit:invisible` hides both the source and the output. Pair it with
`id=` for setup that the reader shouldn't be distracted by but follow-up
blocks need to reach:

```scala marklit:invisible,id=setup
val setupData = List("configured", "hidden", "setup")
```

The invisible block defined `setupData`, which we use here via
`extends=`:

```scala marklit:extends=setup
println(setupData.mkString(", "))
```

## `compile-only`: typecheck without running

`marklit:compile-only` verifies the code compiles but skips execution.
Use when running the code would be slow, side-effecting, or
non-deterministic:

```scala marklit:compile-only
def expensiveOperation(): Unit =
  Thread.sleep(10000) // would take 10 seconds if executed
  println("Done!")
```

## `fail`: assert a compile error

`marklit:fail` asserts that the code *fails* to compile. The rendered
output shows the expected error — great for documenting "what NOT to
do":

```scala marklit:fail
val x: String = 42  // type mismatch: Int vs String
```

If the block surprisingly compiles, marklit reports the file as failed.

## `warn`: assert and display a compile warning

`marklit:warn` plays a dual role:
1. It asserts the block produces at least one compile warning.
2. It always renders those warnings in the output, regardless of the
   global `--show-warnings` / `marklitShowWarnings` setting.

```scala marklit:warn
@deprecated("use newMethod instead", "1.0")
def oldMethod(): Unit = ()

oldMethod()  // produces a deprecation warning
```

For controlling warning display on *non-warn* blocks, see the
`show-warnings=true|false` info-string option in
[using-directives.md](using-directives.md).

## `crash`: assert a runtime exception

`marklit:crash` asserts that running the block throws an exception. The
exception type and message are rendered:

```scala marklit:crash
val crashList = List(1, 2, 3)
crashList(10)  // IndexOutOfBoundsException
```

## `passthrough`: render verbatim

`marklit:passthrough` renders the block as-is, no compilation, no
execution. The fence loses its `scala` language tag in the output, so
this is a good way to keep example snippets that aren't valid Scala:

```scala marklit:passthrough
// This is not compiled or executed
// It's rendered exactly as written
imaginaryFunction()  // no error even though this doesn't exist
```

## Scala 3 indentation syntax

marklit handles Scala 3's indentation-sensitive syntax, including
nested `if`/`else`:

```scala
def greet(name: String): Unit =
  if name.nonEmpty then
    println(s"Hello, $name!")
    println("Nice to meet you.")
  else
    println("Hello, stranger!")

greet("World")
```

## Where to next

- **Want to use a library?** [using-deps.md](using-deps.md)
- **Want to set scalac options or pin a Scala version?**
  [using-directives.md](using-directives.md)
- **Want blocks to share or isolate state, or run different blocks
  under different Scala versions?**
  [scopes-and-versions.md](scopes-and-versions.md)
- **Want a worked ZIO recipe?** [zio-example.md](zio-example.md)
