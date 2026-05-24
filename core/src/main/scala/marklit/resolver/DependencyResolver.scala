package marklit.resolver

import coursierapi.*
import fastparse.*
import fastparse.NoWhitespace.*
import zio.*

import scala.jdk.CollectionConverters.*

/** Resolves Maven/Ivy dependencies to classpath entries using Coursier */
object DependencyResolver:

  // ============================================================================
  // Maven Coordinate Parser (using fastparse)
  // ============================================================================

  /** Parsed Maven coordinate */
  case class MavenCoord(
      org: String,
      name: String,
      version: String,
      crossBuilt: Boolean
  )

  // Character predicates for Maven coordinates
  private def isCoordChar(c: Char): Boolean =
    c.isLetterOrDigit || c == '.' || c == '-' || c == '_'

  // Fastparse parsers for Maven coordinates
  private def coordPart[$: P]: P[String] = P(CharsWhile(isCoordChar, 1).!)

  // org::name:version (cross-built)
  private def crossBuiltCoord[$: P]: P[MavenCoord] =
    P(coordPart ~ "::" ~ coordPart ~ ":" ~ coordPart).map {
      case (org, name, version) =>
        MavenCoord(org, name, version, crossBuilt = true)
    }

  // org:name:version (exact artifact)
  private def exactCoord[$: P]: P[MavenCoord] =
    P(coordPart ~ ":" ~ coordPart ~ ":" ~ coordPart).map {
      case (org, name, version) =>
        MavenCoord(org, name, version, crossBuilt = false)
    }

  // Full coordinate parser - try cross-built first
  private def mavenCoord[$: P]: P[MavenCoord] =
    P(crossBuiltCoord | exactCoord)

  /** Parse a dependency string in format: org::name:version or org:name:version
    *
    * Examples:
    *   - "dev.zio::zio:2.1.24" -> cross-built (::)
    *   - "org.slf4j:slf4j-api:2.0.9" -> exact artifact (:)
    */
  def parseDependency(
      dep: String,
      scalaBinaryVersion: String
  ): Either[String, Dependency] =
    fastparse.parse(dep.trim, mavenCoord(using _)) match
      case Parsed.Success(coord, _) =>
        val artifactName =
          if coord.crossBuilt then s"${coord.name}_$scalaBinaryVersion"
          else coord.name
        Right(Dependency.of(coord.org, artifactName, coord.version))
      case _: Parsed.Failure =>
        Left(
          s"Invalid dependency format: $dep. Expected org::name:version or org:name:version"
        )

  // ============================================================================
  // Scala Version Parser (using fastparse)
  // ============================================================================

  private def versionPart[$: P]: P[String] = P(CharsWhile(_.isDigit, 1).!)

  private def scalaVersionParser[$: P]: P[String] =
    P(versionPart ~ ("." ~ versionPart).rep).map { case (major, rest) =>
      if major == "3" then "3"
      else if rest.nonEmpty then s"$major.${rest.head}"
      else major
    }

  /** Extract Scala binary version from full version string */
  def scalaBinaryVersion(scalaVersion: String): String =
    fastparse.parse(scalaVersion, scalaVersionParser(using _)) match
      case Parsed.Success(binary, _) => binary
      case _: Parsed.Failure         => scalaVersion

  /** Resolve dependencies to JAR files using Coursier
    *
    * @param deps
    *   List of dependency strings (e.g., "dev.zio::zio:2.1.24")
    * @param scalaVersion
    *   The Scala version for cross-building (e.g., "3.8.2")
    * @param repositories
    *   Additional repositories (Maven Central is always included)
    * @return
    *   List of resolved JAR file paths
    */
  def resolve(
      deps: Vector[String],
      scalaVersion: String,
      repositories: Vector[String] = Vector.empty
  ): Task[Vector[String]] =
    ZIO.attemptBlocking {
      if deps.isEmpty then Vector.empty
      else
        val binaryVersion = scalaBinaryVersion(scalaVersion)

        // Parse all dependencies
        val coursierDeps = deps.map { depStr =>
          parseDependency(depStr, binaryVersion) match
            case Right(dep) => dep
            case Left(err)  => throw new IllegalArgumentException(err)
        }

        // Build fetch request
        val fetch = Fetch
          .create()
          .addDependencies(coursierDeps*)

        // Add custom repositories
        repositories.foreach { repoStr =>
          if repoStr.startsWith("ivy:") then
            fetch.addRepositories(IvyRepository.of(repoStr.stripPrefix("ivy:")))
          else fetch.addRepositories(MavenRepository.of(repoStr))
        }

        // Fetch and return paths
        fetch.fetch().asScala.toVector.map(_.getAbsolutePath)
    }

  // ============================================================================
  // Using Directives Parser (using fastparse)
  // ============================================================================

  /** Parsed using directive */
  enum Directive:
    case Dep(value: String)
    case Deps(values: Vector[String])
    case Scala(version: String)
    case Repo(url: String)
    case Repos(urls: Vector[String])
    case ScalacOption(option: String)
    case ScalacOptions(options: Vector[String])
    case Unknown(key: String, value: String)

  /** Accumulated using directives from a document */
  case class UsingDirectives(
      dependencies: Vector[String] = Vector.empty,
      scalaVersion: Option[String] = None,
      repositories: Vector[String] = Vector.empty,
      scalacOptions: Vector[String] = Vector.empty
  ):
    def merge(other: UsingDirectives): UsingDirectives =
      UsingDirectives(
        dependencies = dependencies ++ other.dependencies,
        scalaVersion = other.scalaVersion.orElse(scalaVersion),
        repositories = repositories ++ other.repositories,
        scalacOptions = scalacOptions ++ other.scalacOptions
      )

    def isEmpty: Boolean =
      dependencies.isEmpty && scalaVersion.isEmpty && repositories.isEmpty && scalacOptions.isEmpty

  extension (ud: UsingDirectives)
    def addDirective(d: Directive): UsingDirectives = d match
      case Directive.Dep(v)    => ud.copy(dependencies = ud.dependencies :+ v)
      case Directive.Deps(vs)  => ud.copy(dependencies = ud.dependencies ++ vs)
      case Directive.Scala(v)  => ud.copy(scalaVersion = Some(v))
      case Directive.Repo(v)   => ud.copy(repositories = ud.repositories :+ v)
      case Directive.Repos(vs) => ud.copy(repositories = ud.repositories ++ vs)
      case Directive.ScalacOption(v) =>
        ud.copy(scalacOptions = ud.scalacOptions :+ v)
      case Directive.ScalacOptions(vs) =>
        ud.copy(scalacOptions = ud.scalacOptions ++ vs)
      case Directive.Unknown(_, _) => ud

  // Fastparse parsers - use CharPred instead of CharsWhileIn due to macro bug
  private def isSpace(c: Char): Boolean = c == ' ' || c == '\t'
  private def isIdentChar(c: Char): Boolean =
    c.isLetterOrDigit || c == '_' || c == '-'
  private def isValueChar(c: Char): Boolean = !c.isWhitespace && c != ','

  private def ws[$: P]: P[Unit] = P(CharsWhile(isSpace, 0))
  private def ws1[$: P]: P[Unit] = P(CharsWhile(isSpace, 1))

  private def identifier[$: P]: P[String] =
    P(CharsWhile(isIdentChar, 1).!)

  // A value can be a quoted string or an unquoted word (no commas or whitespace)
  private def quotedString[$: P]: P[String] =
    P("\"" ~/ CharsWhile(_ != '"', 0).! ~ "\"")

  private def unquotedValue[$: P]: P[String] =
    P(CharsWhile(isValueChar, 1).!)

  private def value[$: P]: P[String] =
    P(quotedString | unquotedValue)

  // Comma-separated list of values
  private def valueList[$: P]: P[Vector[String]] =
    P(value.rep(sep = ws ~ "," ~ ws, min = 1).map(_.toVector))

  // Using directive line: //> using key value(s)
  private def usingDirective[$: P]: P[Directive] =
    P("//>" ~ ws ~ "using" ~ ws1 ~ identifier ~ ws1 ~ valueList).map {
      case (key, values) =>
        key.toLowerCase match
          case "dep" | "dependency" | "lib" | "library" =>
            if values.size == 1 then Directive.Dep(values.head)
            else Directive.Deps(values)
          case "deps" | "dependencies" | "libs" | "libraries" =>
            Directive.Deps(values)
          case "scala" =>
            Directive.Scala(values.headOption.getOrElse(""))
          case "repo" | "repository" =>
            if values.size == 1 then Directive.Repo(values.head)
            else Directive.Repos(values)
          case "repos" | "repositories" =>
            Directive.Repos(values)
          case "option" | "scalac" =>
            if values.size == 1 then Directive.ScalacOption(values.head)
            else Directive.ScalacOptions(values)
          case "options" =>
            Directive.ScalacOptions(values)
          case other =>
            Directive.Unknown(other, values.mkString(", "))
    }

  // Try to parse a line as a using directive
  private def tryParseLine(line: String): Option[Directive] =
    val trimmed = line.trim
    if !trimmed.startsWith("//>") then None
    else
      parse(trimmed, usingDirective(using _)) match
        case Parsed.Success(directive, _) => Some(directive)
        case _: Parsed.Failure            => None

  /** Parse using directives from code */
  def parseUsingDirectives(code: String): UsingDirectives =
    code.linesIterator.foldLeft(UsingDirectives()) { (acc, line) =>
      tryParseLine(line) match
        case Some(directive) => acc.addDirective(directive)
        case None            => acc
    }

  /** Extract using directives from all code blocks in a document */
  def extractFromDocument(codeBlocks: Vector[String]): UsingDirectives =
    codeBlocks.foldLeft(UsingDirectives()) { (acc, code) =>
      acc.merge(parseUsingDirectives(code))
    }

  /** Resolve Scala library jars for compilation. For Scala 3, only need
    * scala3-library which transitively brings in scala-library.
    *
    * @param scalaVersion
    *   Full Scala version (e.g., "3.8.2")
    * @return
    *   List of JAR file paths for Scala standard library
    */
  def resolveScalaLibrary(scalaVersion: String): Task[Vector[String]] =
    ZIO.attemptBlocking {
      resolveScalaLibrarySync(scalaVersion)
    }

  /** Synchronous version for use in ScalaCompiler initialization */
  def resolveScalaLibrarySync(scalaVersion: String): Vector[String] =
    val isScala3 = scalaVersion.startsWith("3")

    // For Scala 3, only request scala3-library - it brings in scala-library transitively
    // For Scala 2, only request scala-library
    val deps =
      if isScala3 then
        Vector(
          Dependency.of("org.scala-lang", "scala3-library_3", scalaVersion)
        )
      else
        Vector(Dependency.of("org.scala-lang", "scala-library", scalaVersion))

    val fetch = Fetch.create().addDependencies(deps*)
    fetch.fetch().asScala.toVector.map(_.getAbsolutePath)

  /** Resolve compiler jars (with all transitive deps, including the matching
    * stdlib). Used by CompilerFactory to build a per-version classloader for
    * invoking either dotc (Scala 3) or nsc (Scala 2.13) reflectively.
    *
    * Scala 2.13.x maps to `org.scala-lang:scala-compiler:<version>`. Earlier
    * 2.x lines (2.12, 2.11) are explicitly rejected — marklit only supports
    * 2.13 and 3.x.
    *
    * @param scalaVersion
    *   Full Scala version (e.g. "3.7.0", "2.13.16").
    * @return
    *   List of JAR file paths sufficient to invoke the matching compiler. Order
    *   is the URLClassLoader scan order.
    */
  def resolveScalaCompiler(scalaVersion: String): Task[Vector[String]] =
    ZIO.attemptBlocking {
      resolveScalaCompilerSync(scalaVersion)
    }

  /** Synchronous version for use in CompilerFactory initialization */
  def resolveScalaCompilerSync(scalaVersion: String): Vector[String] =
    val dep =
      if scalaVersion.startsWith("3") then
        Dependency.of("org.scala-lang", "scala3-compiler_3", scalaVersion)
      else if scalaVersion.startsWith("2.13") then
        Dependency.of("org.scala-lang", "scala-compiler", scalaVersion)
      else
        throw new IllegalArgumentException(
          s"Unsupported Scala version '$scalaVersion'. Marklit supports 2.13.x and 3.x; earlier 2.x lines are not supported."
        )
    Fetch
      .create()
      .addDependencies(dep)
      .fetch()
      .asScala
      .toVector
      .map(_.getAbsolutePath)
