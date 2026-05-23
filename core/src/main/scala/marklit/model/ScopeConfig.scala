package marklit.model

/** Scope configuration for a code block.
  *
  * Rules:
  *   - `id` and `append` are mutually exclusive
  *   - `extends` without `id` creates anonymous child scope
  *   - `extends` with `append` mutates the parent scope
  *   - `id` with `extends` creates a named child scope
  *   - `scalaVersion` sets compiler version; scopes cannot extend across
  *     versions
  */
final case class ScopeConfig(
    id: Option[String] = None,
    extendsScope: Option[String] = None,
    append: Boolean = false,
    scalaVersion: Option[String] = None
):
  /** Validate that the configuration is legal */
  def validate: Either[String, ScopeConfig] =
    if id.isDefined && append then
      Left("Cannot use both 'id' and 'append' - they are mutually exclusive")
    else if append && extendsScope.isEmpty then
      Left("'append' requires 'extends' to specify the parent scope")
    else Right(this)

  /** Whether this block creates a new named scope */
  def createsNamedScope: Boolean = id.isDefined

  /** Whether this block appends to an existing scope */
  def appendsToParent: Boolean = append && extendsScope.isDefined

  /** Whether this block creates an anonymous child scope */
  def createsAnonymousChild: Boolean =
    extendsScope.isDefined && id.isEmpty && !append

object ScopeConfig:
  val empty: ScopeConfig = ScopeConfig()

  /** Parse scope config from modifier strings like "id=foo", "extends=bar",
    * "append"
    */
  def parse(modifiers: Seq[String]): Either[String, ScopeConfig] =
    var id: Option[String] = None
    var extendsScope: Option[String] = None
    var append: Boolean = false
    var scalaVersion: Option[String] = None

    modifiers.foreach { mod =>
      if mod.startsWith("id=") then id = Some(mod.stripPrefix("id="))
      else if mod.startsWith("extends=") then
        extendsScope = Some(mod.stripPrefix("extends="))
      else if mod == "append" then append = true
      else if mod.startsWith("scala=") then
        scalaVersion = Some(mod.stripPrefix("scala="))
    }

    ScopeConfig(id, extendsScope, append, scalaVersion).validate
