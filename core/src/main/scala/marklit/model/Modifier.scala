package marklit.model

/** Modifiers that control how a code block is processed */
enum Modifier:
  case Silent // Compile and execute, show only code (no output)
  case Invisible // Compile and execute, show nothing
  case Fail // Assert compilation failure
  case Warn // Assert compilation warnings
  case Crash // Assert runtime exception
  case CompileOnly // Compile but don't execute
  case Passthrough // Render content as-is (no processing)
  case ZIOApp // Wrap code in ZIOAppDefault (code is the body of `def run`)
  case Shared // Prepend to every per-version default scope
  case SharedMajor(
      major: String
  ) // Prepend to default scopes for that Scala major

object Modifier:
  /** Parse a modifier from its string representation */
  def parse(s: String): Option[Modifier] = s.toLowerCase match
    case "silent"                             => Some(Silent)
    case "invisible"                          => Some(Invisible)
    case "fail"                               => Some(Fail)
    case "warn"                               => Some(Warn)
    case "crash"                              => Some(Crash)
    case "compile-only"                       => Some(CompileOnly)
    case "passthrough"                        => Some(Passthrough)
    case "zio-app"                            => Some(ZIOApp)
    case "shared"                             => Some(Shared)
    case other if other.startsWith("shared-") =>
      val mv = other.stripPrefix("shared-")
      // Accept any nonempty suffix; the major is whatever the user wrote.
      // Matching against version strings happens at scope-resolution time.
      if mv.nonEmpty then Some(SharedMajor(mv)) else None
    case _ => None
