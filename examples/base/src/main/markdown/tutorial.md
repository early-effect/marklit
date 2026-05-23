# Marklit Tutorial

This is a comprehensive example demonstrating all marklit features.

## Basic Code Execution

Code blocks are compiled and executed, with output displayed:

```scala
val greeting = "Hello, Marklit!"
println(greeting)
```

## Sequential Blocks Share Context

Blocks accumulate in the same scope, so you can build up definitions:

```scala
case class Person(name: String, age: Int)
```

```scala
val alice = Person("Alice", 30)
println(s"${alice.name} is ${alice.age} years old")
```

## Silent Modifier

The `silent` modifier compiles and executes code but hides the output:

```scala marklit:silent
val secretValue = 42
println("This output is hidden")
```

You can still use values defined in silent blocks:

```scala
println(s"The secret is: $secretValue")
```

## Invisible Modifier

The `invisible` modifier hides both the code and output - useful for setup:

```scala marklit:invisible
val setupData = List("configured", "hidden", "setup")
```

The invisible block set up `setupData` which we can use:

```scala
println(setupData.mkString(", "))
```

## Compile-Only Modifier

The `compile-only` modifier verifies code compiles but doesn't execute it:

```scala marklit:compile-only
def expensiveOperation(): Unit =
  Thread.sleep(10000) // Would take 10 seconds if executed
  println("Done!")
```

## Fail Modifier (Expected Compilation Errors)

The `fail` modifier asserts that code fails to compile - great for showing what NOT to do:

```scala marklit:fail
val x: String = 42  // Type mismatch: Int vs String
```

## Warn Modifier (Expected Compilation Warnings)

The `warn` modifier asserts that code produces compilation warnings:

```scala marklit:warn
@deprecated("use newMethod instead", "1.0")
def oldMethod(): Unit = ()

oldMethod()  // This should produce a deprecation warning
```

## Crash Modifier (Expected Runtime Exceptions)

The `crash` modifier asserts that code throws an exception at runtime:

```scala marklit:crash
val crashList = List(1, 2, 3)
crashList(10)  // IndexOutOfBoundsException
```

## Passthrough Modifier

The `passthrough` modifier renders content as-is without processing:

```scala marklit:passthrough
// This is not compiled or executed
// It's rendered exactly as written
imaginaryFunction()  // No error even though this doesn't exist
```

## Named Scopes

Use `id=` to create isolated scopes:

```scala marklit:id=math
def square(x: Int): Int = x * x
```

```scala marklit:id=strings
def repeat(s: String, n: Int): String = s * n
```

These scopes are independent - `square` is not visible in the `strings` scope.

## Scope Inheritance

Use `extends=` to inherit from another scope:

```scala marklit:id=base
val baseValue = 100
```

```scala marklit:extends=base
val derived = baseValue * 2
println(s"Derived: $derived")
```

## Appending to Scopes

Use `append` with `extends=` to add to an existing scope:

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

## Scala Version Filtering

Use `scala=` to run blocks only on specific Scala versions:

### Scala 3 Only

```scala marklit:scala=3
// Scala 3 enums
enum Status:
  case Active, Inactive, Pending

println(Status.Active)
```

### Scala 2 Only

```scala marklit:scala=2
// Scala 2 style
sealed trait Status2
case object Active2 extends Status2
case object Inactive2 extends Status2

println(Active2)
```

## Combining Modifiers

You can combine multiple modifiers:

```scala marklit:silent,id=combined
val combinedSetup = "combined example"
```

```scala marklit:extends=combined
println(s"Using: $combinedSetup")
```

## Scala 3 Indentation Syntax

Marklit properly handles Scala 3's indentation-sensitive syntax:

```scala
def greet(name: String): Unit =
  if name.nonEmpty then
    println(s"Hello, $name!")
    println("Nice to meet you.")
  else
    println("Hello, stranger!")

greet("World")
```

## Working with Collections

```scala marklit:id=collections
val numbers = (1 to 5).toList

// Map, filter, reduce
val result = numbers
  .map(_ * 2)
  .filter(_ > 4)
  .reduce(_ + _)

println(s"Result: $result")
```

## Pattern Matching

```scala marklit:id=patterns
def describe(x: Any): String = x match
  case i: Int if i > 0 => s"positive int: $i"
  case s: String       => s"string of length ${s.length}"
  case _               => "something else"

println(describe(42))
println(describe("hello"))
println(describe(3.14))
```

That's the complete marklit feature set!
