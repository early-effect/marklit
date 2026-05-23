# Dependency Resolution Example

This example demonstrates using external dependencies in marklit code blocks.

## Using Directives

You can specify dependencies directly in code blocks using `//> using` directives:

```scala
//> using dep com.lihaoyi::pprint:0.9.0

import pprint._

case class Person(name: String, age: Int, email: String)

val alice = Person("Alice", 30, "alice@example.com")
pprint.pprintln(alice)
```

## Multiple Dependencies

Multiple dependencies can be specified:

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

## Scala Version Directive

You can also specify scalac options:

```scala
//> using option -deprecation

@deprecated("use newMethod", "1.0")
def oldMethod(): Unit = println("old")

oldMethod()
```
