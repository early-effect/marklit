package marklit.model

/** A parsed code block from a markdown file */
final case class CodeBlock(
    code: String,
    modifiers: Set[Modifier],
    scopeConfig: ScopeConfig,
    location: Location
):
  /** Whether this block should be executed */
  def shouldExecute: Boolean =
    !modifiers.contains(Modifier.CompileOnly) &&
      !modifiers.contains(Modifier.Fail) &&
      !modifiers.contains(Modifier.Passthrough)

  /** Whether this block expects compilation to fail */
  def expectsFailure: Boolean = modifiers.contains(Modifier.Fail)

  /** Whether this block expects a runtime crash */
  def expectsCrash: Boolean = modifiers.contains(Modifier.Crash)

  /** Whether this block expects warnings */
  def expectsWarnings: Boolean = modifiers.contains(Modifier.Warn)

  /** Whether output should be shown */
  def showOutput: Boolean =
    !modifiers.contains(Modifier.Silent) &&
      !modifiers.contains(Modifier.Invisible)

  /** Whether the code block itself should be shown */
  def showCode: Boolean = !modifiers.contains(Modifier.Invisible)

  /** Whether this is passthrough (no processing) */
  def isPassthrough: Boolean = modifiers.contains(Modifier.Passthrough)

  /** Whether this block should be wrapped in ZIOAppDefault */
  def isZIOApp: Boolean = modifiers.contains(Modifier.ZIOApp)

  /** Whether this block should be skipped (filter behavior) given the current
    * default Scala version.
    *
    * Filter only applies to **bare major** specifiers (`scala=2`, `scala=3`):
    * if the major differs from the current default, skip the block. This is the
    * "build a 2.13 doc and a 3.x doc from one source" use case.
    *
    * **Specific versions** (`scala=3.7.0`, `scala=2.13.16`) are NOT filters —
    * they request a cross-version compile against that exact version, handled
    * by [[marklit.processor.DocumentProcessor]] via [[CompilerService]].
    *
    * The detection rule: a `.` in the version string means specific.
    */
  def isCompatibleWith(currentVersion: String): Boolean =
    scopeConfig.scalaVersion match
      case None               => true
      case Some(blockVersion) =>
        if blockVersion.contains(".") then
          // Specific version — always "compatible" at the filter level.
          // The processor will obtain a matching compiler.
          true
        else
          // Bare major — filter on major version equality.
          val currentMajor = currentVersion.takeWhile(_ != '.')
          currentMajor == blockVersion

  /** The specific Scala version this block requests, if any. Bare majors and
    * absent values return None — those don't trigger a per-block compiler.
    */
  def requestedSpecificScalaVersion: Option[String] =
    scopeConfig.scalaVersion.filter(_.contains("."))

  /** Whether this block contributes its code to every per-version default
    * scope.
    */
  def isShared: Boolean = modifiers.contains(Modifier.Shared)

  /** The major version this block is shared with, if it carries a `shared-{mv}`
    * modifier.
    */
  def sharedMajor: Option[String] =
    modifiers.collectFirst { case Modifier.SharedMajor(mv) => mv }

  /** Whether this block should be prepended to *some* per-version default scope
    * (either unconditionally or restricted to a major).
    */
  def isAnyShared: Boolean = isShared || sharedMajor.isDefined

  /** Whether this block contributes to the default scope for the given Scala
    * version. Unshared blocks return false; `shared` returns true for any
    * version; `shared-{mv}` returns true only when the version's major matches.
    */
  def appliesToDefaultScope(version: String): Boolean =
    if isShared then true
    else
      sharedMajor match
        case None     => false
        case Some(mv) => version.takeWhile(_ != '.') == mv
