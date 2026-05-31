# Top-Level Blocks

By default marklit wraps every block in `object MarklitWrapper: def run(): Unit
= …`, so your code is a **method body**. That's the right default for runnable
examples, but some Scala constructs are only legal — or only warning-free — at
the *top level* of a source file. The `top-level` modifier compiles a block
verbatim as its own compilation unit (no wrapper).

Because top-level code has no entry point, `top-level` blocks are
**compile-only**: marklit type-checks them and renders the code (plus any
diagnostics), but never executes them. To *use* a top-level definition in a
running example, give it an `id=` and `extends=` it from a normal block — marklit
hoists the definition above the wrapper while your example runs inside `run()`.

## The motivating case: matching a parameterized enum case

Pattern-matching a parameterized `enum`/ADT case against a non-local scrutinee
needs a runtime type test. When the `enum` is declared *inside* `def run()` it
becomes a **local class**, and the type test "cannot be checked at runtime" — so
the compiler warns. Here is the problem, asserted with `warn`:

```scala marklit:warn
enum CounterAction:
  case Inc
  case Set(v: Int)

val action: Any = CounterAction.Set(10)
val label = action match
  case CounterAction.Set(v) => s"set to $v"
  case _                    => "other"
println(label)
```

The warning is real, not cosmetic — it tells you the match is not actually
type-safe at runtime. Moving the `enum` to the top level makes it a genuine
class, so the type test is checkable and the warning disappears.

Define the `enum` in a `top-level` block (compile-only — note no output below
it):

```scala marklit:top-level,id=actions
enum CounterAction:
  case Inc
  case Set(v: Int)
```

Then match on it from a normal block that `extends` the top-level scope. The
`enum` is hoisted, so this compiles cleanly **and** runs:

```scala marklit:extends=actions
val action: Any = CounterAction.Set(10)
val label = action match
  case CounterAction.Set(v) => s"set to $v"
  case _                    => "other"
println(label)
```

## `opaque type`: illegal inside a method body

An `opaque type` can only be declared at the top level — inside the wrapper it
fails to compile outright. `top-level` lets you show one:

```scala marklit:top-level,id=temperature
opaque type Celsius = Double

object Celsius:
  def apply(d: Double): Celsius = d
  extension (c: Celsius) def value: Double = c
```

And a normal block can construct and use it, with the opaque type hoisted into
scope:

```scala marklit:extends=temperature
val t = Celsius(21.5)
println(s"temperature = ${t.value}°C")
```

## `@main`: a top-level entry point

A `@main` method "cannot be a main method since it cannot be accessed
statically" inside the wrapper. As a `top-level` block it type-checks fine
(compile-only — marklit shows it, but does not invoke it):

```scala marklit:top-level
@main def greet(name: String): Unit =
  println(s"Hello, $name!")
```

## Combining with a Scala version

`top-level` composes with the `scala=` selector, so you can pin a top-level
definition to a specific major or version. This `given`/`extension` pair is
compiled against Scala 3:

```scala marklit:top-level,id=show3,scala=3
trait Show[A]:
  def show(a: A): String

given Show[Int] with
  def show(a: Int): String = s"Int($a)"

extension [A](a: A)(using s: Show[A]) def shown: String = s.show(a)
```

```scala marklit:extends=show3,scala=3
println(42.shown)
```

## Rules

- `top-level` is **strict**: it may only be combined with scope options
  (`id`/`extends`/`append`), a version selector (`scala=3`, `scala=3.7.3`), and
  `show-warnings`. Pairing it with a behavioral modifier (`silent`, `fail`,
  `crash`, `zio-app`, `scala=shared`, …) is a validation error.
- Scopes are single-kind. A normal block may `extends=` a top-level scope
  (hoisting its definitions); a `top-level` block may **not** extend or append to
  a normal scope, and `append` must stay within one kind.

## See also

- [tutorial.md](tutorial.md) — modifier basics (`silent`, `invisible`,
  `compile-only`, `fail`, `warn`, `crash`, `passthrough`).
- [scopes-and-versions.md](scopes-and-versions.md) — how `id=` / `extends=` /
  `append` work, and per-block multi-version compilation.
