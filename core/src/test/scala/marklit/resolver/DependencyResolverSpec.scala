package marklit.resolver

import zio.Scope
import zio.test.*

/** Integration tests for DependencyResolver's Coursier-backed helpers. These
  * tests hit the local Coursier cache (or fetch on first run); they don't
  * depend on the test JVM's classpath layout.
  */
object DependencyResolverSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("DependencyResolver")(
      suite("resolveScalaLibrarySync")(
        test(
          "Scala 3: returns scala3-library and transitive scala-library 2.13"
        ) {
          val jars = DependencyResolver.resolveScalaLibrarySync("3.3.7")
          assertTrue(
            jars.nonEmpty,
            jars.exists(_.contains("scala3-library_3-3.3.7")),
            // dotc needs scala-library 2.13 for `package scala`; transitive of scala3-library.
            jars.exists(_.contains("scala-library-2.13"))
          )
        },
        test("Scala 2: returns scala-library at the requested version") {
          val jars = DependencyResolver.resolveScalaLibrarySync("2.13.16")
          assertTrue(
            jars.nonEmpty,
            jars.exists(_.contains("scala-library-2.13.16"))
          )
        }
      ),
      suite("resolveScalaCompilerSync")(
        test(
          "returns scala3-compiler with transitive scala3-library and scala-library"
        ) {
          val jars = DependencyResolver.resolveScalaCompilerSync("3.3.7")
          assertTrue(
            jars.exists(_.contains("scala3-compiler_3-3.3.7")),
            jars.exists(_.contains("scala3-library_3-3.3.7")),
            jars.exists(_.contains("scala-library-2.13")),
            // tasty-core and scala-asm are essential dotc dependencies.
            jars.exists(_.contains("tasty-core")),
            jars.exists(_.contains("scala-asm"))
          )
        },
        test(
          "works for a different 3.x minor (verifying multi-version resolution)"
        ) {
          // 3.7.0 is intentionally chosen to differ from the project's own
          // scala3Version — this confirms the resolver isn't hard-pinned.
          val jars = DependencyResolver.resolveScalaCompilerSync("3.7.0")
          assertTrue(
            jars.exists(_.contains("scala3-compiler_3-3.7.0")),
            jars.exists(_.contains("scala3-library_3-3.7.0"))
          )
        },
        test("resolves scala-compiler for 2.13.x") {
          val jars = DependencyResolver.resolveScalaCompilerSync("2.13.16")
          assertTrue(
            jars.exists(_.contains("scala-compiler-2.13.16")),
            jars.exists(_.contains("scala-library-2.13.16")),
            // scala-reflect is part of the 2.13 stdlib distribution.
            jars.exists(_.contains("scala-reflect-2.13.16"))
          )
        },
        test("rejects unsupported Scala lines (e.g. 2.12)") {
          val ex = scala.util
            .Try(DependencyResolver.resolveScalaCompilerSync("2.12.20"))
            .failed
            .get
          assertTrue(
            ex.isInstanceOf[IllegalArgumentException],
            ex.getMessage.contains("Unsupported Scala version")
          )
        }
      )
    )
