package example

/** Scala 2.13-only API: a sealed-trait + case-objects encoding of the same
  * shape that the 3.x source dir exposes as an `enum`.
  *
  * Lives in `src/main/scala-2.13/`, which sbt only includes on the 2.13
  * build. Calls in the markdown look identical (`Color.Red`, `c.code`)
  * regardless of which underlying encoding is on the classpath.
  */
sealed abstract class Color(val code: String)
object Color {
  case object Red extends Color("#ff0000")
  case object Green extends Color("#00ff00")
  case object Blue extends Color("#0000ff")
}
