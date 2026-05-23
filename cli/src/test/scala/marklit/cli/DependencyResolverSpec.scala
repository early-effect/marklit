package marklit.cli

import marklit.resolver.DependencyResolver
import zio.*
import zio.test.*

object DependencyResolverSpec extends ZIOSpecDefault:

  def spec = suite("DependencyResolver")(
    suite("parseDependency")(
      test("parses cross-built dependency") {
        val result =
          DependencyResolver.parseDependency("dev.zio::zio:2.1.24", "3")
        assertTrue(
          result.isRight,
          result.toOption.get.getModule.getName.toString == "zio_3"
        )
      },

      test("parses exact artifact dependency") {
        val result =
          DependencyResolver.parseDependency("org.slf4j:slf4j-api:2.0.9", "3")
        assertTrue(
          result.isRight,
          result.toOption.get.getModule.getName.toString == "slf4j-api"
        )
      },

      test("fails on invalid format") {
        val result = DependencyResolver.parseDependency("invalid", "3")
        assertTrue(result.isLeft)
      }
    ),

    suite("parseUsingDirectives")(
      test("parses single dep directive") {
        val code = """//> using dep dev.zio::zio:2.1.24
                     |val x = 1""".stripMargin
        val result = DependencyResolver.parseUsingDirectives(code)
        assertTrue(
          result.dependencies == Vector("dev.zio::zio:2.1.24")
        )
      },

      test("parses multiple dep directives") {
        val code = """//> using dep dev.zio::zio:2.1.24
                     |//> using dep org.typelevel::cats-core:2.10.0
                     |val x = 1""".stripMargin
        val result = DependencyResolver.parseUsingDirectives(code)
        assertTrue(
          result.dependencies.size == 2,
          result.dependencies.contains("dev.zio::zio:2.1.24"),
          result.dependencies.contains("org.typelevel::cats-core:2.10.0")
        )
      },

      test("parses comma-separated deps") {
        val code =
          """//> using deps dev.zio::zio:2.1.24, org.typelevel::cats-core:2.10.0
                     |val x = 1""".stripMargin
        val result = DependencyResolver.parseUsingDirectives(code)
        assertTrue(
          result.dependencies.size == 2
        )
      },

      test("parses scala version directive") {
        val code = """//> using scala 3.8.2
                     |val x = 1""".stripMargin
        val result = DependencyResolver.parseUsingDirectives(code)
        assertTrue(
          result.scalaVersion == Some("3.8.2")
        )
      },

      test("parses repository directive") {
        val code = """//> using repo https://repo.example.com/maven
                     |val x = 1""".stripMargin
        val result = DependencyResolver.parseUsingDirectives(code)
        assertTrue(
          result.repositories == Vector("https://repo.example.com/maven")
        )
      },

      test("parses scalac options") {
        val code = """//> using option -deprecation
                     |//> using options -feature, -Wunused:all
                     |val x = 1""".stripMargin
        val result = DependencyResolver.parseUsingDirectives(code)
        assertTrue(
          result.scalacOptions.contains("-deprecation"),
          result.scalacOptions.contains("-feature"),
          result.scalacOptions.contains("-Wunused:all")
        )
      },

      test("parses quoted string values") {
        val code = """//> using dep "dev.zio::zio:2.1.24"
                     |val x = 1""".stripMargin
        val result = DependencyResolver.parseUsingDirectives(code)
        assertTrue(
          result.dependencies == Vector("dev.zio::zio:2.1.24")
        )
      },

      test("ignores non-directive lines") {
        val code = """val x = 1
                     |// regular comment
                     |println("hello")""".stripMargin
        val result = DependencyResolver.parseUsingDirectives(code)
        assertTrue(result.isEmpty)
      },

      test("ignores unknown directives") {
        val code = """//> using unknown foo
                     |val x = 1""".stripMargin
        val result = DependencyResolver.parseUsingDirectives(code)
        assertTrue(result.isEmpty)
      }
    ),

    suite("resolve")(
      test("resolves a simple dependency") {
        for jars <- DependencyResolver.resolve(
            Vector("com.lihaoyi::pprint:0.9.0"),
            "3.8.2"
          )
        yield assertTrue(
          jars.nonEmpty,
          jars.exists(_.contains("pprint"))
        )
      } @@ TestAspect.timeout(60.seconds),

      test("returns empty for no dependencies") {
        for jars <- DependencyResolver.resolve(Vector.empty, "3.8.2")
        yield assertTrue(jars.isEmpty)
      }
    )
  )
