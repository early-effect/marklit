package example

/** Cross-built greeter — the same API on Scala 2.13 and Scala 3. */
object Greeter {
  def hello(name: String): String = s"Hello, $name!"
}
