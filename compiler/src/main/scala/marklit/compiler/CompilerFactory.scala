package marklit.compiler

import marklit.compiler.api.DotcInvoker
import marklit.resolver.DependencyResolver
import zio.*

import java.net.{URL, URLClassLoader}
import java.nio.file.{Files, Path, StandardCopyOption}

/** Builds Compilers for arbitrary Scala 3 versions by resolving the requested
  * `scala3-compiler` jars at runtime and invoking dotc reflectively across a
  * per-version classloader.
  *
  * The marklit JVM never directly imports `dotty.tools.*`. Instead, the shim
  * (compiled into `marklit-compiler-shim.jar` and bundled as a CLI resource)
  * implements a small Java-friendly interface, [[DotcInvoker]]. This factory:
  *
  *   1. Coursier-resolves `scala3-compiler_3:<version>` for the requested
  *      version (cached by version).
  *   2. Builds a [[URLClassLoader]] whose URLs are `[shim.jar, ...compiler
  *      jars]` and whose parent only exposes `marklit.compiler.api.*` and
  *      `java.*` from the host loader.
  *   3. Reflectively constructs `marklit.compiler.shim.DotcInvokerImpl` from
  *      that loader and casts it to [[DotcInvoker]].
  *   4. Wraps the invoker in a [[ScalaCompiler]] bound to the same classpath so
  *      user code executes against the matching scala3-library at runtime.
  */
trait CompilerFactory:
  /** Get a [[Compiler]] for the requested Scala 3 version. Repeated calls for
    * the same version share the underlying classloader and resolved jars.
    *
    * @param scalaVersion
    *   Full Scala 3 version (e.g., "3.7.0"). Bare-major requests like "3" must
    *   be resolved by the caller before reaching the factory.
    * @param extraClasspath
    *   Additional jars to put on the user-code compile/runtime classpath (e.g.,
    *   resolved dependencies from `//> using dep` directives or the CLI's
    *   `--classpath`).
    * @param scalacOptions
    *   Compiler options to apply to every block compiled by the returned
    *   compiler (e.g., `-deprecation` from `//> using option`).
    */
  def forVersion(
      scalaVersion: String,
      extraClasspath: Vector[String] = Vector.empty,
      scalacOptions: Vector[String] = Vector.empty,
      runtimeParent: Option[ClassLoader] = None,
      shareUserClasses: Boolean = false
  ): UIO[Compiler]

  /** A classloader that can load *user* classes (`extraClasspath`) against the
    * per-version compiler stdlib — the same parent loader block execution uses
    * ([[ScalaCompiler.executeCompiled]]).
    *
    * Used to instantiate a build-provided run resource (a
    * `java.util.function.Supplier[AutoCloseable]`) once per run, outside the
    * per-block execution path. The returned loader is scoped: it is closed when
    * the surrounding [[Scope]] closes, so callers acquire it inside the run's
    * `ZIO.scoped` boundary.
    */
  def userClassLoader(
      scalaVersion: String,
      extraClasspath: Vector[String]
  ): URIO[Scope, ClassLoader]

object CompilerFactory:

  /** Default Scala 3 version — the version the bundled 3.x shim was compiled
    * against. Used when no version is specified by document, CLI flag, or build
    * plugin.
    *
    * Read from a plain text resource embedded in the shim jar at build time
    * (see build.sbt's `compilerShim` resource generator). Reading a `.txt`
    * resource avoids loading any shim classes — which would transitively
    * require `scala3-compiler` on the probe classpath, defeating the whole
    * per-version isolation contract.
    */
  def defaultScalaVersion: String =
    readShimVersion("/marklit-compiler-shim.jar", "marklit-shim-version.txt")

  /** Default Scala 2 version — the version the bundled 2.13 shim was compiled
    * against. Used when a block requests a bare-major `scala=2` and the
    * file/CLI default is not itself a 2.13.x version.
    */
  def defaultScala2Version: String =
    readShimVersion(
      "/marklit-compiler-shim-2.jar",
      "marklit-shim-2-version.txt"
    )

  private def readShimVersion(
      shimResource: String,
      versionEntry: String
  ): String =
    val tmp = Files.createTempFile("marklit-shim-probe-", ".jar")
    try
      copyResource(shimResource, tmp)
      val zip = new java.util.zip.ZipFile(tmp.toFile)
      try
        val entry = zip.getEntry(versionEntry)
        if entry == null then
          throw new IllegalStateException(
            s"$versionEntry missing from shim jar — build.sbt resource generator did not run"
          )
        val in = zip.getInputStream(entry)
        try new String(in.readAllBytes(), "UTF-8").trim
        finally in.close()
      finally zip.close()
    finally Files.deleteIfExists(tmp): Unit

  /** Build a CompilerFactory backed by extracted copies of both bundled shim
    * jars (3.x dotc shim and 2.13 nsc shim) plus Coursier resolution for each
    * requested version.
    */
  val layer: ZLayer[Any, Throwable, CompilerFactory] =
    ZLayer.scoped {
      for
        shim3 <- extractResource("/marklit-compiler-shim.jar", "marklit-shim-")
        shim2 <- extractResource(
          "/marklit-compiler-shim-2.jar",
          "marklit-shim-2-"
        )
        cache <- Ref.make(Map.empty[String, VersionBundle])
      yield new Live(shim3, shim2, cache)
    }

  /** Layer variant for tests: takes both shim jar paths explicitly so tests can
    * point at the freshly-built `compilerShim` / `compilerShim2` packageBin
    * outputs without going through the CLI fat jar.
    */
  def testLayer(
      shim3Jar: Path,
      shim2Jar: Path
  ): ZLayer[Any, Nothing, CompilerFactory] =
    ZLayer.scoped {
      Ref
        .make(Map.empty[String, VersionBundle])
        .map(cache => new Live(shim3Jar, shim2Jar, cache))
    }

  private def extractResource(
      resource: String,
      tmpPrefix: String
  ): ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking {
        val tmp = Files.createTempFile(tmpPrefix, ".jar")
        copyResource(resource, tmp)
        tmp
      }
    )(p => ZIO.attempt(Files.deleteIfExists(p): Unit).ignore)

  /** Cached per-version state: the resolved compiler jars, the per-version
    * classloader, and the [[DotcInvoker]] instance loaded from it. Sharing
    * these across calls is what makes repeated [[forVersion]] invocations
    * cheap.
    */
  private final case class VersionBundle(
      invoker: DotcInvoker,
      compilerJars: Vector[String],
      loader: URLClassLoader
  )

  // ---------- Internals ----------

  /** Copy a classpath resource to the given path. Errors out loudly if the
    * resource is missing — that means the build is broken (the cli module's
    * resourceGenerators step in build.sbt didn't run).
    */
  private def copyResource(name: String, target: Path): Unit =
    val in = Option(getClass.getResourceAsStream(name)).getOrElse {
      throw new IllegalStateException(
        s"shim resource '$name' not on classpath — was build.sbt's CLI resourceGenerators step skipped?"
      )
    }
    try
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING): Unit
    finally in.close()

  /** Build a per-version classloader. Parent is a filtering loader that exposes
    * only `marklit.compiler.api.*` (so the [[DotcInvoker]] interface is a
    * single shared class across loaders) and `java.*`/`javax.*` from the host.
    * Everything else — including `dotty.*` and `scala.*` — comes from the URLs.
    */
  private def buildLoader(
      shimJar: Path,
      compilerJars: Vector[String]
  ): URLClassLoader =
    val urls: Array[URL] =
      (Vector(shimJar.toUri.toURL) ++ compilerJars.map(p =>
        java.nio.file.Paths.get(p).toUri.toURL
      )).toArray
    new URLClassLoader(urls, new ApiOnlyParent(getClass.getClassLoader))

  /** A parent classloader that delegates only `marklit.compiler.api.*` (and
    * `java.*`/`javax.*`, which the JVM bootstrap covers anyway) to the given
    * host loader. Refuses to load anything else, forcing the child loader to
    * find `dotty.*` and `scala.*` in its own URL list.
    *
    * The host has scala3-library on it (transitive of marklit's own compile),
    * so without this filter the child would inherit a `scala.*` from the host
    * AND find one in its URLs — yielding the very "package scala contains
    * object and package with same name" duplicate-definition errors that
    * triggered this whole refactor.
    */
  private final class ApiOnlyParent(host: ClassLoader)
      extends ClassLoader(null):
    override def loadClass(name: String, resolve: Boolean): Class[?] =
      // Delegate the marklit<->shim bridge API plus everything the JDK platform
      // classloader owns. The platform check is authoritative and covers java.*,
      // javax.*, sun.*, jdk.* AND platform-module packages such as org.w3c.* /
      // org.xml.* (java.xml) that JDBC drivers reach for at runtime — so we never
      // again dead-end a JDK class that wasn't in a hand-maintained prefix list.
      // `scala.*` / `dotty.*` are deliberately NOT shared: the child must find
      // those in its own per-version URL list.
      val shared =
        name.startsWith("marklit.compiler.api.") ||
          ApiOnlyParent.isPlatformClass(name)
      if shared then
        val c = host.loadClass(name)
        if resolve then resolveClass(c)
        c
      else throw new ClassNotFoundException(name)

  private object ApiOnlyParent:
    private val platformLoader: ClassLoader =
      ClassLoader.getPlatformClassLoader

    /** True when the JDK platform classloader can load `name` — the
      * authoritative, version-current test for "is this a JDK platform class?".
      */
    def isPlatformClass(name: String): Boolean =
      try
        platformLoader.loadClass(name)
        true
      catch case _: ClassNotFoundException => false

  private final class Live(
      shim3Jar: Path,
      shim2Jar: Path,
      cache: Ref[Map[String, VersionBundle]]
  ) extends CompilerFactory:
    override def forVersion(
        scalaVersion: String,
        extraClasspath: Vector[String] = Vector.empty,
        scalacOptions: Vector[String] = Vector.empty,
        runtimeParent: Option[ClassLoader] = None,
        shareUserClasses: Boolean = false
    ): UIO[Compiler] =
      bundleFor(scalaVersion).map { bundle =>
        // outputDir per call is fine — the heavy work (classloader + Coursier
        // resolution + reflective shim load) lives in the cached bundle.
        val outputDir = Files.createTempDirectory(s"marklit-out-$scalaVersion-")
        outputDir.toFile.deleteOnExit()
        new ScalaCompiler(
          invoker = bundle.invoker,
          classpath = bundle.compilerJars ++ extraClasspath,
          scalacOptions = scalacOptions,
          outputDir = outputDir,
          scalaVersion = scalaVersion,
          // When a run resource is configured, `runtimeParent` is the per-run
          // user loader U (itself a child of bundle.loader, so it still sees the
          // matching stdlib). Blocks then load user classes from U and share the
          // resource instance. Otherwise execution parents off bundle.loader as
          // before.
          runtimeLoader = Some(runtimeParent.getOrElse(bundle.loader)),
          shareUserClasses = shareUserClasses
        )
      }

    override def userClassLoader(
        scalaVersion: String,
        extraClasspath: Vector[String]
    ): URIO[Scope, ClassLoader] =
      bundleFor(scalaVersion).flatMap { bundle =>
        ZIO.acquireRelease(
          ZIO.succeed {
            val urls: Array[URL] =
              extraClasspath
                .map(p => java.nio.file.Paths.get(p).toUri.toURL)
                .toArray
            new URLClassLoader(urls, bundle.loader): ClassLoader
          }
        ) {
          case cl: URLClassLoader =>
            ZIO.attempt(cl.close()).ignore
          case _ => ZIO.unit
        }
      }

    private def bundleFor(scalaVersion: String): UIO[VersionBundle] =
      cache.get.flatMap { existing =>
        existing.get(scalaVersion) match
          case Some(b) => ZIO.succeed(b)
          case None    =>
            ZIO
              .attemptBlocking(buildBundle(scalaVersion))
              .orDie
              .flatMap(b => cache.update(_.updated(scalaVersion, b)).as(b))
      }

    private def buildBundle(scalaVersion: String): VersionBundle =
      val (shimJar, invokerClass) =
        if scalaVersion.startsWith("3") then
          (shim3Jar, "marklit.compiler.shim.DotcInvokerImpl")
        else if scalaVersion.startsWith("2.13") then
          (shim2Jar, "marklit.compiler.shim.NscInvokerImpl")
        else
          throw new IllegalArgumentException(
            s"Unsupported Scala version '$scalaVersion'. Marklit supports 2.13.x and 3.x."
          )
      val compilerJars =
        DependencyResolver.resolveScalaCompilerSync(scalaVersion)
      val loader = buildLoader(shimJar, compilerJars)
      val invokerCls = Class.forName(invokerClass, true, loader)
      val invoker = invokerCls
        .getDeclaredConstructor()
        .newInstance()
        .asInstanceOf[DotcInvoker]
      VersionBundle(invoker, compilerJars, loader)
