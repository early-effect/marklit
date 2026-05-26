package marklit.scope

import marklit.model.*
import marklit.scope.Scope as MarklitScope
import zio.*
import zio.test.*

object ScopeManagerSpec extends ZIOSpecDefault:

  val testLocation = Location("test.md", 1, 1)

  def spec = suite("ScopeManager")(
    suite("default scope")(
      test("anon block (no config) gets a fresh scope parented to default") {
        // README: "By default each block gets a fresh anonymous scope. Code
        // blocks do not share state unless you tell them to."
        for resolved <- ScopeManager.resolveScope(
            ScopeConfig.empty,
            testLocation
          )
        yield assertTrue(
          resolved.scope.id.startsWith("__anon_"),
          resolved.scope.parent == Some(MarklitScope.defaultId),
          resolved.inheritedCode.isEmpty
        )
      },

      test("two anon blocks do NOT see each other's code") {
        for
          r1 <- ScopeManager.resolveScope(ScopeConfig.empty, testLocation)
          // Even if a previous block's code were recorded into its
          // throwaway scope, the next anon block must not see it.
          _ <- ScopeManager.recordCode(r1.scope.id, "val x = 1")
          r2 <- ScopeManager.resolveScope(ScopeConfig.empty, testLocation)
        yield assertTrue(
          r1.scope.id != r2.scope.id,
          r2.inheritedCode.isEmpty,
          r2.scope.priorCode.isEmpty
        )
      },

      test("anon block inherits seeded shared blocks for its version") {
        // `shared` / `shared-{mv}` is the documented opt-in for code that
        // should land in every anon block of a given version.
        for
          _ <- ScopeManager.seedDefaultPriorCode("3.7.3", "val helper = 99")
          resolved <- ScopeManager.resolveScope(
            ScopeConfig.empty,
            testLocation,
            effectiveVersion = Some("3.7.3")
          )
        yield assertTrue(
          resolved.scope.id.startsWith("__anon_"),
          resolved.scope.parent == Some(MarklitScope.defaultIdFor("3.7.3")),
          resolved.inheritedCode == Vector("val helper = 99")
        )
      }
    ),

    suite("named scopes")(
      test("creates named scope with id=") {
        val config = ScopeConfig(id = Some("myScope"))
        for resolved <- ScopeManager.resolveScope(config, testLocation)
        yield assertTrue(
          resolved.scope.id == "myScope",
          resolved.inheritedCode.isEmpty
        )
      },

      test("reuses existing named scope") {
        val config = ScopeConfig(id = Some("reusable"))
        for
          r1 <- ScopeManager.resolveScope(config, testLocation)
          _ <- ScopeManager.recordCode("reusable", "val a = 1")
          r2 <- ScopeManager.resolveScope(config, testLocation)
        yield assertTrue(
          r1.scope.id == r2.scope.id,
          r2.scope.priorCode == Vector("val a = 1")
        )
      },

      test("named scope with scala version") {
        val config =
          ScopeConfig(id = Some("scala3Scope"), scalaVersion = Some("3"))
        for resolved <- ScopeManager.resolveScope(config, testLocation)
        yield assertTrue(
          resolved.scope.id == "scala3Scope",
          resolved.scope.scalaVersion == Some("3")
        )
      }
    ),

    suite("scope inheritance")(
      test("child scope inherits parent code with extends=") {
        val parentConfig = ScopeConfig(id = Some("parent"))
        val childConfig =
          ScopeConfig(id = Some("child"), extendsScope = Some("parent"))
        for
          _ <- ScopeManager.resolveScope(parentConfig, testLocation)
          _ <- ScopeManager.recordCode("parent", "val base = 42")
          child <- ScopeManager.resolveScope(childConfig, testLocation)
        yield assertTrue(
          child.scope.id == "child",
          child.scope.parent == Some("parent"),
          child.inheritedCode == Vector("val base = 42")
        )
      },

      test("anonymous child scope with extends= only") {
        val parentConfig = ScopeConfig(id = Some("anon-parent"))
        val childConfig = ScopeConfig(extendsScope = Some("anon-parent"))
        for
          _ <- ScopeManager.resolveScope(parentConfig, testLocation)
          _ <- ScopeManager.recordCode("anon-parent", "val p = 1")
          child <- ScopeManager.resolveScope(childConfig, testLocation)
        yield assertTrue(
          child.scope.id.startsWith("__anon_"),
          child.scope.parent == Some("anon-parent"),
          child.inheritedCode == Vector("val p = 1")
        )
      },

      test("multi-level inheritance collects all ancestor code") {
        val grandparent = ScopeConfig(id = Some("gp"))
        val parent = ScopeConfig(id = Some("p"), extendsScope = Some("gp"))
        val child = ScopeConfig(id = Some("c"), extendsScope = Some("p"))
        for
          _ <- ScopeManager.resolveScope(grandparent, testLocation)
          _ <- ScopeManager.recordCode("gp", "val a = 1")
          _ <- ScopeManager.resolveScope(parent, testLocation)
          _ <- ScopeManager.recordCode("p", "val b = 2")
          ch <- ScopeManager.resolveScope(child, testLocation)
        yield assertTrue(
          ch.inheritedCode == Vector("val a = 1", "val b = 2")
        )
      }
    ),

    suite("append semantics")(
      test("append mutates parent scope") {
        val parentConfig = ScopeConfig(id = Some("appendTarget"))
        val appendConfig =
          ScopeConfig(extendsScope = Some("appendTarget"), append = true)
        for
          _ <- ScopeManager.resolveScope(parentConfig, testLocation)
          _ <- ScopeManager.recordCode("appendTarget", "val x = 1")
          append <- ScopeManager.resolveScope(appendConfig, testLocation)
          // Record code via append - it goes to parent
          _ <- ScopeManager.recordCode(append.scope.id, "val y = 2")
          // Resolve parent again to see accumulated code
          parent <- ScopeManager.resolveScope(parentConfig, testLocation)
        yield assertTrue(
          append.scope.id == "appendTarget", // append returns parent scope
          parent.scope.priorCode == Vector("val x = 1", "val y = 2")
        )
      }
    ),

    suite("validation")(
      test("rejects id + append combination") {
        val config = ScopeConfig(
          id = Some("invalid"),
          append = true,
          extendsScope = Some("foo")
        )
        for result <- ScopeManager.resolveScope(config, testLocation).either
        yield assertTrue(
          result.isLeft,
          result.left.toOption.exists {
            case MarklitError.ValidationError(_, msg) =>
              msg.contains("id") && msg.contains("append")
            case _ => false
          }
        )
      },

      test("rejects extends to non-existent scope") {
        val config = ScopeConfig(extendsScope = Some("doesNotExist"))
        for result <- ScopeManager.resolveScope(config, testLocation).either
        yield assertTrue(
          result.isLeft,
          result.left.toOption.exists {
            case MarklitError.ValidationError(_, msg) =>
              msg.contains("not found")
            case _ => false
          }
        )
      },

      test("rejects cross-version extends") {
        val scala2 =
          ScopeConfig(id = Some("scala2Scope"), scalaVersion = Some("2.13"))
        val scala3Child = ScopeConfig(
          id = Some("scala3Child"),
          extendsScope = Some("scala2Scope"),
          scalaVersion = Some("3")
        )
        for
          _ <- ScopeManager.resolveScope(scala2, testLocation)
          result <- ScopeManager.resolveScope(scala3Child, testLocation).either
        yield assertTrue(
          result.isLeft,
          result.left.toOption.exists {
            case MarklitError.ValidationError(_, msg) =>
              msg.contains("different version") || msg.contains("Cannot extend")
            case _ => false
          }
        )
      },

      test("rejects append without extends") {
        val config = ScopeConfig(append = true)
        for result <- ScopeManager.resolveScope(config, testLocation).either
        yield assertTrue(
          result.isLeft,
          result.left.toOption.exists {
            case MarklitError.ValidationError(_, msg) =>
              msg.contains("requires")
            case _ => false
          }
        )
      },

      test("rejects reuse of id with a different Scala version") {
        val v337 =
          ScopeConfig(id = Some("shared"), scalaVersion = Some("3.3.7"))
        val v373 =
          ScopeConfig(id = Some("shared"), scalaVersion = Some("3.7.3"))
        for
          _ <- ScopeManager.resolveScope(v337, testLocation)
          result <- ScopeManager.resolveScope(v373, testLocation).either
        yield assertTrue(
          result.isLeft,
          result.left.toOption.exists {
            case MarklitError.ValidationError(_, msg) =>
              msg.contains("'shared'") &&
              msg.contains("3.3.7") && msg.contains("3.7.3")
            case _ => false
          }
        )
      },

      test(
        "rejects reuse of id+extends with a different Scala version on the child"
      ) {
        // Parent has no pinned version, so the parent-vs-child version check
        // doesn't fire. The conflict is between the two reuses of `c2`.
        val parent = ScopeConfig(id = Some("p2"))
        val first = ScopeConfig(
          id = Some("c2"),
          extendsScope = Some("p2"),
          scalaVersion = Some("3.7.3")
        )
        val second = ScopeConfig(
          id = Some("c2"),
          extendsScope = Some("p2"),
          scalaVersion = Some("3.6.4")
        )
        for
          _ <- ScopeManager.resolveScope(parent, testLocation)
          _ <- ScopeManager.resolveScope(first, testLocation)
          result <- ScopeManager.resolveScope(second, testLocation).either
        yield assertTrue(
          result.isLeft,
          result.left.toOption.exists {
            case MarklitError.ValidationError(_, msg) =>
              msg.contains("'c2'") &&
              msg.contains("3.7.3") && msg.contains("3.6.4")
            case _ => false
          }
        )
      }
    ),

    suite("parallel compilation")(
      test("identifies independent scope trees") {
        val tree1Root = ScopeConfig(id = Some("tree1"))
        val tree1Child =
          ScopeConfig(id = Some("tree1-child"), extendsScope = Some("tree1"))
        val tree2Root = ScopeConfig(id = Some("tree2"))
        for
          _ <- ScopeManager.resolveScope(tree1Root, testLocation)
          _ <- ScopeManager.resolveScope(tree1Child, testLocation)
          _ <- ScopeManager.resolveScope(tree2Root, testLocation)
          indep <- ScopeManager.independentScopes
        yield
          // Should have at least 2 independent groups (tree1+child, tree2, and default)
          val tree1Group = indep.find(_.contains("tree1"))
          val tree2Group = indep.find(_.contains("tree2"))
          assertTrue(
            tree1Group.isDefined,
            tree2Group.isDefined,
            tree1Group != tree2Group, // different groups
            tree1Group.exists(_.contains("tree1-child")) // child with parent
          )
      },

      test("allScopeIds returns all created scopes") {
        val s1 = ScopeConfig(id = Some("scope1"))
        val s2 = ScopeConfig(id = Some("scope2"))
        for
          _ <- ScopeManager.resolveScope(s1, testLocation)
          _ <- ScopeManager.resolveScope(s2, testLocation)
          ids <- ScopeManager.allScopeIds
        yield assertTrue(
          ids.contains("scope1"),
          ids.contains("scope2"),
          ids.contains(MarklitScope.defaultId)
        )
      }
    )
  ).provide(ScopeManager.layer) @@ TestAspect.sequential
