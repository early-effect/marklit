package marklit.scope

import marklit.model.*
import zio.*

/** A scope node in the scope tree.
  *
  * `topLevel` marks a scope whose code is compiled at file scope (no
  * `MarklitWrapper` wrapper). Each scope is single-kind: top-level scopes hold
  * only top-level definitions, normal scopes hold run-body code. A top-level
  * scope can only be extended/appended by another top-level scope; a normal
  * scope may *extend* (but not append to) a top-level scope, hoisting its
  * definitions above the wrapper.
  */
final case class Scope(
    id: String,
    scalaVersion: Option[String],
    priorCode: Vector[String],
    parent: Option[String],
    topLevel: Boolean = false
):
  def appendCode(code: String): Scope =
    copy(priorCode = priorCode :+ code)

  def allCode: String = priorCode.mkString("\n\n")

object Scope:
  /** Legacy single-default id, kept as the bare-version default for blocks
    * resolved without an effective Scala version. New per-version default ids
    * are produced by [[defaultIdFor]].
    */
  val defaultId = "__default__"

  /** Default-scope id for a specific Scala version (e.g. "__default__3.7.3").
    * If `version` is empty, returns [[defaultId]] for backward compatibility.
    */
  def defaultIdFor(version: String): String =
    if version.isEmpty then defaultId else s"${defaultId}${version}"

  /** Page-scope id for a specific Scala version. Used when the CLI/plugin opts
    * a file into shared-by-default behavior: every anonymous block extends this
    * scope (with `append`), parented to the per-version default so `shared`
    * blocks still seed it.
    */
  val pageId = "__page__"
  def pageIdFor(version: String): String =
    if version.isEmpty then pageId else s"${pageId}${version}"

  def default: Scope = Scope(defaultId, None, Vector.empty, None)

  def defaultFor(version: String): Scope =
    Scope(defaultIdFor(version), Some(version), Vector.empty, None)

  def named(
      id: String,
      scalaVersion: Option[String] = None,
      parent: Option[String] = None,
      topLevel: Boolean = false
  ): Scope =
    Scope(id, scalaVersion, Vector.empty, parent, topLevel)

/** Result of resolving a scope for a code block.
  *
  * `inheritedTopLevel` / `inheritedBody` are the ancestor code split by kind:
  * code from top-level ancestor scopes (hoisted above the wrapper) vs. code
  * from normal ancestor scopes (run-body). Because a top-level scope can only
  * be extended by another top-level scope, top-level ancestors always form a
  * prefix of the chain, so `inheritedTopLevel ++ inheritedBody` preserves the
  * original ancestor order.
  */
final case class ResolvedScope(
    scope: Scope,
    inheritedTopLevel: Vector[String],
    inheritedBody: Vector[String]
):
  /** Flat inherited code in ancestor order (top-level ancestors first). Kept
    * for callers that don't care about hoist placement.
    */
  def inheritedCode: Vector[String] = inheritedTopLevel ++ inheritedBody

  /** Definitions to emit at file scope: inherited top-level ancestor code,
    * plus this scope's own code when it is itself a top-level scope.
    */
  def hoistCode: Vector[String] =
    if scope.topLevel then inheritedTopLevel ++ scope.priorCode
    else inheritedTopLevel

  /** Code to emit in the wrapper body: inherited normal-ancestor code, plus
    * this scope's own code when it is a normal scope.
    */
  def bodyCode: Vector[String] =
    if scope.topLevel then inheritedBody
    else inheritedBody ++ scope.priorCode

  /** All code in order: inherited from ancestors + this scope's accumulated
    * code
    */
  def allCode: String =
    (inheritedCode ++ scope.priorCode).mkString("\n\n")

/** Manages scope trees for markdown document processing */
trait ScopeManager:
  /** Get or create a scope for a code block based on its config.
    *
    * `effectiveVersion` is the Scala version this block will be compiled
    * against (block override > file default > CLI default). When the block has
    * no explicit scope (`config` is empty of id/extends/append), we resolve to
    * a per-version default scope keyed by `effectiveVersion`.
    */
  def resolveScope(
      config: ScopeConfig,
      location: Location,
      effectiveVersion: Option[String] = None,
      topLevel: Boolean = false
  ): IO[MarklitError, ResolvedScope]

  /** Seed a per-version default scope's prior code (used to inject `shared` /
    * `shared-{mv}` blocks before any block of that version is processed).
    *
    * Idempotent against the same `(version, code)` pair: appends only if the
    * exact code isn't already present.
    */
  def seedDefaultPriorCode(version: String, code: String): UIO[Unit]

  /** Record code as having been processed in a scope */
  def recordCode(scopeId: String, code: String): UIO[Unit]

  /** Get all scope IDs (for parallel compilation of independent branches) */
  def allScopeIds: UIO[Set[String]]

  /** Get scopes that can be compiled in parallel (no dependencies between them)
    */
  def independentScopes: UIO[Vector[Set[String]]]

object ScopeManager:
  def resolveScope(
      config: ScopeConfig,
      location: Location,
      effectiveVersion: Option[String] = None,
      topLevel: Boolean = false
  ): ZIO[ScopeManager, MarklitError, ResolvedScope] =
    ZIO.serviceWithZIO[ScopeManager](
      _.resolveScope(config, location, effectiveVersion, topLevel)
    )

  def seedDefaultPriorCode(
      version: String,
      code: String
  ): URIO[ScopeManager, Unit] =
    ZIO.serviceWithZIO[ScopeManager](_.seedDefaultPriorCode(version, code))

  def recordCode(scopeId: String, code: String): URIO[ScopeManager, Unit] =
    ZIO.serviceWithZIO[ScopeManager](_.recordCode(scopeId, code))

  def allScopeIds: URIO[ScopeManager, Set[String]] =
    ZIO.serviceWithZIO[ScopeManager](_.allScopeIds)

  def independentScopes: URIO[ScopeManager, Vector[Set[String]]] =
    ZIO.serviceWithZIO[ScopeManager](_.independentScopes)

  val layer: ULayer[ScopeManager] = ZLayer.fromZIO(make)

  /** Create a new ScopeManager instance */
  def make: UIO[ScopeManager] =
    Ref.make(ScopeState.empty).map(ScopeManagerLive(_))

/** Internal state for scope management */
private final case class ScopeState(
    scopes: Map[String, Scope],
    // Track parent->children relationships for parallel compilation
    children: Map[String, Set[String]],
    // Counter for anonymous scopes
    anonymousCounter: Int
):
  def getScope(id: String): Option[Scope] = scopes.get(id)

  def addScope(scope: Scope): ScopeState =
    val newChildren = scope.parent match
      case Some(parentId) =>
        children.updated(
          parentId,
          children.getOrElse(parentId, Set.empty) + scope.id
        )
      case None => children
    copy(
      scopes = scopes + (scope.id -> scope),
      children = newChildren
    )

  def updateScope(scope: Scope): ScopeState =
    copy(scopes = scopes + (scope.id -> scope))

  def nextAnonymousId: (ScopeState, String) =
    (copy(anonymousCounter = anonymousCounter + 1), s"__anon_$anonymousCounter")

  /** Find all root scopes (no parent) */
  def rootScopes: Set[String] =
    scopes.values.filter(_.parent.isEmpty).map(_.id).toSet

  /** Get all descendants of a scope */
  def descendants(id: String): Set[String] =
    val direct = children.getOrElse(id, Set.empty)
    direct ++ direct.flatMap(descendants)

private object ScopeState:
  val empty: ScopeState = ScopeState(
    scopes = Map(Scope.defaultId -> Scope.default),
    children = Map.empty,
    anonymousCounter = 0
  )

/** Live implementation of ScopeManager */
private final class ScopeManagerLive(stateRef: Ref[ScopeState])
    extends ScopeManager:

  override def resolveScope(
      config: ScopeConfig,
      location: Location,
      effectiveVersion: Option[String],
      topLevel: Boolean
  ): IO[MarklitError, ResolvedScope] =
    // First validate the config
    ZIO
      .fromEither(config.validate)
      .mapError(msg => MarklitError.ValidationError(location, msg))
      .flatMap { validConfig =>
        stateRef
          .modify { state =>
            resolveInternal(
              validConfig,
              location,
              state,
              effectiveVersion,
              topLevel
            )
          }
          .flatMap {
            case Left(error)     => ZIO.fail(error)
            case Right(resolved) => ZIO.succeed(resolved)
          }
      }

  override def seedDefaultPriorCode(
      version: String,
      code: String
  ): UIO[Unit] =
    stateRef.update { state =>
      val id = Scope.defaultIdFor(version)
      val current = state.getScope(id).getOrElse(Scope.defaultFor(version))
      val withScope =
        if state.getScope(id).isDefined then state else state.addScope(current)
      if current.priorCode.contains(code) then withScope
      else withScope.updateScope(current.appendCode(code))
    }

  private def resolveInternal(
      config: ScopeConfig,
      location: Location,
      state: ScopeState,
      effectiveVersion: Option[String],
      topLevel: Boolean
  ): (Either[MarklitError, ResolvedScope], ScopeState) =
    // A block and the scope it resolves to must agree on kind: a top-level
    // scope holds only top-level definitions (hoisted to file scope), a normal
    // scope holds run-body code. Reusing an `id` across kinds, or appending
    // across kinds, is illegal — they cannot share a single compilation form.
    def kindMismatch(scopeId: String): String =
      val (blockKind, scopeKind) =
        if topLevel then ("top-level", "non-top-level")
        else ("non-top-level", "top-level")
      s"Scope '$scopeId' is $scopeKind; cannot use it from a $blockKind block " +
        "(top-level and run-body scopes cannot be mixed)"

    // A NORMAL block may *extend* a top-level scope (hoisting its definitions);
    // any other cross-kind relationship is rejected. `forAppend = true`
    // tightens this to forbid even the extend case, since append mutates the
    // parent and would mix kinds in one scope.
    def crossKindError(
        parent: Scope,
        forAppend: Boolean
    ): Option[MarklitError] =
      val allowed =
        parent.topLevel == topLevel ||
          (parent.topLevel && !topLevel && !forAppend)
      if allowed then None
      else
        Some(MarklitError.ValidationError(location, kindMismatch(parent.id)))

    // Re-using an existing `id=` must match the scope's kind *exactly* — unlike
    // the extends relaxation above, you cannot redeclare a top-level scope as a
    // normal one (or vice-versa); the scope's single compilation form is fixed
    // at creation.
    def sameKindError(existing: Scope): Option[MarklitError] =
      if existing.topLevel == topLevel then None
      else Some(MarklitError.ValidationError(location, kindMismatch(existing.id)))

    (config.id, config.extendsScope, config.append) match
      // No scope config - fresh anonymous scope per block (isolated by
      // default, per the README's "Scopes — explicit by default" model).
      //
      // Normal blocks parent to the per-version default scope (`__default__
      // <version>`), inheriting its seeded `shared` / `shared-{mv}` code.
      // Top-level blocks must NOT inherit that run-body code (illegal at file
      // scope), so an anonymous top-level block gets a parentless isolated
      // scope instead.
      case (None, None, false) =>
        if topLevel then
          val (stateWithId, anonId) = state.nextAnonymousId
          val newScope =
            Scope.named(anonId, effectiveVersion, None, topLevel = true)
          val newState = stateWithId.addScope(newScope)
          (Right(ResolvedScope(newScope, Vector.empty, Vector.empty)), newState)
        else
          val (defaultScope, stateWithDefault) = effectiveVersion match
            case Some(v) =>
              val id = Scope.defaultIdFor(v)
              state.getScope(id) match
                case Some(s) => (s, state)
                case None    =>
                  val ns = Scope.defaultFor(v)
                  (ns, state.addScope(ns))
            case None =>
              val s = state.getScope(Scope.defaultId).getOrElse(Scope.default)
              (s, state)
          val (stateWithId, anonId) = stateWithDefault.nextAnonymousId
          val newScope =
            Scope.named(anonId, effectiveVersion, Some(defaultScope.id))
          val newState = stateWithId.addScope(newScope)
          val (tl, body) = collectInheritedCode(defaultScope.id, stateWithId)
          (Right(ResolvedScope(newScope, tl, body)), newState)

      // id=foo - create or get named scope
      case (Some(id), None, false) =>
        state.getScope(id) match
          case Some(existing) =>
            sameKindError(existing) match
              case Some(err) => (Left(err), state)
              case None       =>
                // A subsequent block re-using `id=foo` must agree on the Scala
                // version. The version we record on a scope at creation is the
                // block's *requested* version (config.scalaVersion); we compare
                // against that here, not against `effectiveVersion`, so that
                // bare-major filters (e.g. `scala=3`) and absent values stay
                // compatible with each other.
                (config.scalaVersion, existing.scalaVersion) match
                  case (Some(reqV), Some(existingV)) if reqV != existingV =>
                    (
                      Left(
                        MarklitError.ValidationError(
                          location,
                          s"Scope '$id' was previously declared with Scala $existingV; cannot reuse with Scala $reqV"
                        )
                      ),
                      state
                    )
                  case _ =>
                    (
                      Right(ResolvedScope(existing, Vector.empty, Vector.empty)),
                      state
                    )
          case None =>
            val newScope =
              Scope.named(id, config.scalaVersion, None, topLevel)
            val newState = state.addScope(newScope)
            (Right(ResolvedScope(newScope, Vector.empty, Vector.empty)), newState)

      // id=foo,extends=bar - create named child scope
      case (Some(id), Some(parentId), false) =>
        state.getScope(parentId) match
          case None =>
            (
              Left(
                MarklitError.ValidationError(
                  location,
                  s"Parent scope '$parentId' not found"
                )
              ),
              state
            )
          case Some(parent) =>
            crossKindError(parent, forAppend = false) match
              case Some(err) => (Left(err), state)
              case None       =>
                // Check version compatibility
                (config.scalaVersion, parent.scalaVersion) match
                  case (Some(childV), Some(parentV)) if childV != parentV =>
                    (
                      Left(
                        MarklitError.ValidationError(
                          location,
                          s"Cannot extend scope '$parentId' (Scala $parentV) with different version (Scala $childV)"
                        )
                      ),
                      state
                    )
                  case _ =>
                    state.getScope(id) match
                      case Some(existing) =>
                        sameKindError(existing) match
                          case Some(err) => (Left(err), state)
                          case None       =>
                            // Re-use of `id=foo` must not flip the version.
                            (config.scalaVersion, existing.scalaVersion) match
                              case (Some(reqV), Some(existingV))
                                  if reqV != existingV =>
                                (
                                  Left(
                                    MarklitError.ValidationError(
                                      location,
                                      s"Scope '$id' was previously declared with Scala $existingV; cannot reuse with Scala $reqV"
                                    )
                                  ),
                                  state
                                )
                              case _ =>
                                val (tl, body) =
                                  collectInheritedCode(parentId, state)
                                (Right(ResolvedScope(existing, tl, body)), state)
                      case None =>
                        val effectiveVersion =
                          config.scalaVersion.orElse(parent.scalaVersion)
                        val newScope =
                          Scope.named(
                            id,
                            effectiveVersion,
                            Some(parentId),
                            topLevel
                          )
                        val newState = state.addScope(newScope)
                        val (tl, body) = collectInheritedCode(parentId, state)
                        (Right(ResolvedScope(newScope, tl, body)), newState)

      // extends=bar (anonymous child)
      case (None, Some(parentId), false) =>
        state.getScope(parentId) match
          case None =>
            (
              Left(
                MarklitError.ValidationError(
                  location,
                  s"Parent scope '$parentId' not found"
                )
              ),
              state
            )
          case Some(parent) =>
            crossKindError(parent, forAppend = false) match
              case Some(err) => (Left(err), state)
              case None       =>
                // Check version compatibility
                (config.scalaVersion, parent.scalaVersion) match
                  case (Some(childV), Some(parentV)) if childV != parentV =>
                    (
                      Left(
                        MarklitError.ValidationError(
                          location,
                          s"Cannot extend scope '$parentId' (Scala $parentV) with different version (Scala $childV)"
                        )
                      ),
                      state
                    )
                  case _ =>
                    val (stateWithId, anonId) = state.nextAnonymousId
                    val effectiveVersion =
                      config.scalaVersion.orElse(parent.scalaVersion)
                    val newScope =
                      Scope.named(
                        anonId,
                        effectiveVersion,
                        Some(parentId),
                        topLevel
                      )
                    val newState = stateWithId.addScope(newScope)
                    val (tl, body) = collectInheritedCode(parentId, state)
                    (Right(ResolvedScope(newScope, tl, body)), newState)

      // extends=bar,append - append to parent scope (mutate it)
      case (None, Some(parentId), true) =>
        state.getScope(parentId) match
          case None =>
            (
              Left(
                MarklitError.ValidationError(
                  location,
                  s"Parent scope '$parentId' not found"
                )
              ),
              state
            )
          case Some(parent) =>
            crossKindError(parent, forAppend = true) match
              case Some(err) => (Left(err), state)
              case None       =>
                // Check version compatibility
                (config.scalaVersion, parent.scalaVersion) match
                  case (Some(v), Some(parentV)) if v != parentV =>
                    (
                      Left(
                        MarklitError.ValidationError(
                          location,
                          s"Cannot append to scope '$parentId' (Scala $parentV) with different version (Scala $v)"
                        )
                      ),
                      state
                    )
                  case _ =>
                    // For append, we return the parent scope itself - code will
                    // be added to it. We only collect ancestors' code, NOT the
                    // parent's own priorCode, because DocumentProcessor adds
                    // scope.priorCode separately.
                    val (tl, body) = parent.parent
                      .map(pid => collectInheritedCode(pid, state))
                      .getOrElse((Vector.empty, Vector.empty))
                    (Right(ResolvedScope(parent, tl, body)), state)

      // id=foo,append - invalid (caught by validate, but handle defensively)
      case (Some(_), _, true) =>
        (
          Left(
            MarklitError
              .ValidationError(location, "Cannot use both 'id' and 'append'")
          ),
          state
        )

      // append without extends - invalid (caught by validate, but handle defensively)
      case (None, None, true) =>
        (
          Left(
            MarklitError.ValidationError(
              location,
              "'append' requires 'extends' to specify the parent scope"
            )
          ),
          state
        )

  /** Collect all code from ancestors in order (oldest first), split by scope
    * kind: `(topLevelCode, bodyCode)`. Because a top-level scope can only be
    * extended by another top-level scope, top-level ancestors always form a
    * prefix of the chain — so concatenating `topLevelCode ++ bodyCode` recovers
    * the original ancestor order.
    */
  private def collectInheritedCode(
      scopeId: String,
      state: ScopeState
  ): (Vector[String], Vector[String]) =
    def ancestors(id: String): Vector[Scope] =
      state.getScope(id) match
        case None        => Vector.empty
        case Some(scope) =>
          scope.parent match
            case None           => Vector(scope)
            case Some(parentId) => ancestors(parentId) :+ scope
    val chain = ancestors(scopeId)
    val tl = chain.filter(_.topLevel).flatMap(_.priorCode)
    val body = chain.filterNot(_.topLevel).flatMap(_.priorCode)
    (tl, body)

  override def recordCode(scopeId: String, code: String): UIO[Unit] =
    stateRef.update { state =>
      state.getScope(scopeId) match
        case Some(scope) => state.updateScope(scope.appendCode(code))
        case None        => state // shouldn't happen
    }

  override def allScopeIds: UIO[Set[String]] =
    stateRef.get.map(_.scopes.keySet)

  override def independentScopes: UIO[Vector[Set[String]]] =
    stateRef.get.map { state =>
      // Group scopes by their root ancestor
      // Scopes with different roots are independent
      def findRoot(id: String): String =
        state.getScope(id).flatMap(_.parent) match
          case None           => id
          case Some(parentId) => findRoot(parentId)

      val byRoot = state.scopes.keys.groupBy(findRoot)
      byRoot.values.map(_.toSet).toVector
    }
