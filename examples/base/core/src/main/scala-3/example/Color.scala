package example

/** Scala 3-only API: an `enum` with cases.
  *
  * Lives in `src/main/scala-3/`, which sbt only includes on the 3.x build.
  */
enum Color:
  case Red, Green, Blue

  def code: String = this match
    case Red   => "#ff0000"
    case Green => "#00ff00"
    case Blue  => "#0000ff"
