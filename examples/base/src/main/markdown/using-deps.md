# Using External Dependencies

How to declare external libraries from inside a markdown file using
`//> using` directives. marklit hands the directive list to Coursier and
puts the resolved jars on every block's compile classpath.

For other directives — compiler options, Scala version, custom repos —
see [using-directives.md](using-directives.md).

## Single dependency

`//> using dep <org>::<name>:<version>` adds one library. The `::` means
"cross-published for the current Scala version." Use a single `:` for
Java-only artifacts.

```scala
//> using dep com.lihaoyi::pprint:0.9.0

import pprint._

case class Person(name: String, age: Int, email: String)

val alice = Person("Alice", 30, "alice@example.com")
pprint.pprintln(alice)
```

## Multiple dependencies (separate directives)

Repeat the directive once per dependency:

```scala
//> using dep com.lihaoyi::fansi:0.5.0
//> using dep com.lihaoyi::sourcecode:0.4.2

import fansi._
import sourcecode._

val colored = fansi.Color.Green("Hello from fansi!")
println(colored.render)

// sourcecode gives us compile-time info
println(s"This is line ${implicitly[Line].value}")
```

## Plural form: `//> using deps`

`//> using deps a, b, c` accepts a comma-separated list, equivalent to
three separate `dep` directives. Aliases `//> using dependencies`,
`//> using libs`, and `//> using libraries` parse the same way.

```scala
//> using deps com.lihaoyi::pprint:0.9.0, com.lihaoyi::os-lib:0.11.4

import pprint._
import os._

pprint.pprintln(os.pwd)
```

## See also

- [using-directives.md](using-directives.md) — compiler options, Scala
  version, custom resolvers, and the `show-warnings` option.
- [scopes-and-versions.md](scopes-and-versions.md) — sharing dependencies
  across blocks via named scopes and per-version compilation.
