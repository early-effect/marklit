# Page-Scope Tour

This file is rendered with the build flag `marklitPageScope := true`
(sbt) / `def marklitPageScope = true` (Mill). The blocks below carry
**no** scope modifiers — they are plain anonymous Scala blocks. Page
scope makes them share state per file (per Scala version).

Without page scope the second block here would not see `items` at all.
With it, every anonymous block is rewritten as if it had been written
`id=__page__<scala-version>` (first time) or
`extends=__page__<scala-version>,append` (every later time).

## A running tally across blocks

```scala
val items = List("apples", "pears", "plums")
println(s"have ${items.size} kinds of fruit")
```

```scala
val totalLetters = items.map(_.length).sum
println(s"$totalLetters letters across ${items.size} items")
```

```scala
val acronym = items.map(_.head.toUpper).mkString
println(s"acronym: $acronym")
```

## Pulling in a helper

```scala
def pluralize(n: Int, word: String): String =
  if n == 1 then s"$n $word" else s"$n ${word}s"

println(pluralize(items.size, "fruit"))
```

## Explicit modifiers still win

A block with its own `id=` opts out of the page rewrite entirely:

```scala marklit:id=independent
val isolated = "I do not see items"
println(isolated)
```

The block above has no access to `items`. The next anonymous block
goes back to seeing the page scope:

```scala
println(s"back to the page scope; items = $items")
```

## Page scope is per Scala version

Each Scala version on a page gets its own `__page__<version>` scope.
Anonymous blocks compiled against Scala 2.13 don't see definitions
from anonymous Scala 3 blocks above, and vice versa — which is exactly
what you want, because they wouldn't link at runtime anyway.

Open a 2.13 page scope:

```scala marklit:scala=2
val twoThirteenItems = List(1, 2, 3)
println(s"sum on 2.13 = ${twoThirteenItems.sum}")
```

A second 2.13 block continues the 2.13 page scope:

```scala marklit:scala=2
val doubled = twoThirteenItems.map(_ * 2)
println(s"doubled on 2.13 = $doubled")
```

A 3.x block back here is on the *Scala 3* page scope — `items` from
the top of the page is still in view, but the 2.13 `twoThirteenItems`
above is **not**:

```scala
println(s"on 3.x; items still visible: $items")
```
