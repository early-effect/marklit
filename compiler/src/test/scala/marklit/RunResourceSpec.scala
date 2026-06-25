package marklit

import zio.*
import zio.test.*

import java.nio.file.{Files, Path}

/** End-to-end tests for the build-provided run-scoped resource
  * ([[MarklitRunConfig.runResourceClass]]).
  *
  * The resource class is loaded by marklit's per-run user loader `U`, a
  * different classloader from this test, so state is observed across the
  * boundary via (a) a file named by the `marklit.test.events` system property
  * for lifecycle events, and (b) rendered block output for the shared in-JVM
  * counter. See `marklit.testfixtures.RunResourceFixtures`.
  */
object RunResourceSpec extends ZIOSpecDefault:

  /** User classpath for the run, gathered from the test's own classloader URL
    * chain (sbt's test runner uses layered classloaders, so `java.class.path`
    * is unreliable here). Minus the Scala stdlib, which the per-version
    * compiler loader supplies — leaving the host's copy on would clash. This
    * mirrors how a build forwards its `fullClasspath` and gives `U` the
    * fixtures *and* the full ZIO dependency set a zio-app block needs to
    * compile (marklit's `ApiOnlyParent` hides the host's copies, so they must
    * be passed explicitly).
    */
  private val testClasspath: Vector[String] =
    def loaderUrls(cl: ClassLoader): Vector[String] =
      val here = cl match
        case u: java.net.URLClassLoader =>
          u.getURLs.toVector.map(url =>
            java.nio.file.Paths.get(url.toURI).toString
          )
        case _ => Vector.empty
      val parent = Option(cl.getParent).map(loaderUrls).getOrElse(Vector.empty)
      parent ++ here
    loaderUrls(getClass.getClassLoader).distinct.filterNot { entry =>
      val name = java.nio.file.Paths.get(entry).getFileName.toString
      name.startsWith("scala-library") || name.startsWith("scala3-library")
    }

  private def tempMd(content: String): ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attempt {
        val p = Files.createTempFile("marklit-runres-", ".md")
        Files.writeString(p, content)
        p
      }
    )(p => ZIO.attempt(Files.deleteIfExists(p)).ignore)

  private def tempDir: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attempt(Files.createTempDirectory("marklit-runres-out-"))
    )(p =>
      ZIO.attempt {
        if Files.exists(p) then
          Files
            .walk(p)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(f => Files.deleteIfExists(f): Unit)
      }.ignore
    )

  /** A fresh temp file whose path is published to the `marklit.test.events`
    * system property for the lifetime of the enclosing scope, then cleared. The
    * property is how the fixture (on loader `U`) and this test agree on the
    * event file across the classloader boundary.
    */
  private def eventsFile: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attempt {
        val p = Files.createTempFile("marklit-events-", ".log")
        java.lang.System.setProperty("marklit.test.events", p.toString)
        p
      }
    )(p =>
      ZIO.attempt {
        java.lang.System.clearProperty("marklit.test.events")
        Files.deleteIfExists(p): Unit
      }.ignore
    )

  private def readLines(p: Path): ZIO[Any, Throwable, Vector[String]] =
    ZIO.attempt {
      import scala.jdk.CollectionConverters.*
      if Files.exists(p) then
        Files.readAllLines(p).asScala.toVector.filter(_.nonEmpty)
      else Vector.empty
    }

  /** Pin the run to the Scala version this test was built with, so the ZIO jar
    * on [[testClasspath]] (built for that version) has TASTy the resolved
    * compiler can read. The bundled default shim is older and would reject
    * newer ZIO TASTy — a version-skew artifact of the test harness, not of the
    * feature. The per-run user loader `U` is built at this same version, so
    * sharing lines up.
    */
  private val hostScalaVersion: String =
    scala.util.Properties.versionNumberString

  private def runConfig(
      file: Path,
      outDir: Path,
      resource: Option[String]
  ): MarklitRunConfig =
    MarklitRunConfig(
      inputFiles = Vector(file),
      outputDir = Some(outDir),
      scalaVersion = Some(hostScalaVersion),
      classpath = testClasspath,
      runResourceClass = resource
    )

  // Two anonymous blocks, each incrementing the shared counter and printing it.
  private val counterDoc =
    """```scala
      |println("counter=" + marklit.testfixtures.SharedRunState.counter.incrementAndGet())
      |```
      |
      |```scala
      |println("counter=" + marklit.testfixtures.SharedRunState.counter.incrementAndGet())
      |```
      |""".stripMargin

  def spec = suite("RunResource")(
    test("no resource configured: lifecycle never runs, counter not shared") {
      // Baseline / no-op contract: without runResourceClass, behavior is exactly
      // as before — each block reloads user classes fresh, so the shared counter
      // reads 1 in both blocks, and no events file is written.
      ZIO.scoped {
        for
          events <- eventsFile
          file <- tempMd(counterDoc)
          outDir <- tempDir
          result <- MarklitRun.run(runConfig(file, outDir, None))
          rendered = result.files.head.rendered.getOrElse("")
          eventLines <- readLines(events)
        yield assertTrue(
          result.success,
          // Each block sees a freshly-loaded counter (starts at 0 → 1).
          rendered.split("counter=1").length - 1 == 2,
          !rendered.contains("counter=2"),
          eventLines.isEmpty
        )
      }
    },
    test("resource lifecycle runs exactly once, acquire before teardown") {
      ZIO.scoped {
        for
          events <- eventsFile
          file <- tempMd(counterDoc)
          outDir <- tempDir
          result <- MarklitRun.run(
            runConfig(
              file,
              outDir,
              Some("marklit.testfixtures.RecordingResource")
            )
          )
          eventLines <- readLines(events)
        yield assertTrue(
          result.success,
          // Acquired once before any doc, closed once after the last.
          eventLines == Vector("acquire", "close")
        )
      }
    },
    test("resource configured: a plain object is shared across blocks") {
      ZIO.scoped {
        for
          _ <- eventsFile
          file <- tempMd(counterDoc)
          outDir <- tempDir
          result <- MarklitRun.run(
            runConfig(
              file,
              outDir,
              Some("marklit.testfixtures.RecordingResource")
            )
          )
          rendered = result.files.head.rendered.getOrElse("")
        yield assertTrue(
          result.success,
          // One shared counter (loaded by U) → blocks count up 1 then 2.
          rendered.contains("counter=1"),
          rendered.contains("counter=2")
        )
      }
    },
    test("resource configured: sharing reaches a zio-app block too") {
      // The critical zio-app check: when sharing is on, a zio-app block loads
      // ZIO from the same loader U as the shared object, so `Console.printLine`
      // type-checks against the shared ZIO (no LinkageError) AND the zio-app
      // block sees the same counter a preceding plain block incremented.
      val mixedDoc =
        """```scala
          |println("counter=" + marklit.testfixtures.SharedRunState.counter.incrementAndGet())
          |```
          |
          |```scala marklit:zio-app
          |zio.Console.printLine(
          |  "counter=" + marklit.testfixtures.SharedRunState.counter.incrementAndGet()
          |)
          |```
          |""".stripMargin
      ZIO.scoped {
        for
          _ <- eventsFile
          file <- tempMd(mixedDoc)
          outDir <- tempDir
          result <- MarklitRun.run(
            runConfig(
              file,
              outDir,
              Some("marklit.testfixtures.RecordingResource")
            )
          )
          rendered = result.files.head.rendered.getOrElse("")
        yield assertTrue(
          result.success,
          rendered.contains("counter=1"),
          // zio-app block saw the plain block's increment → 2.
          rendered.contains("counter=2")
        )
      }
    },
    test("setup failure is a notice; the run still proceeds") {
      ZIO.scoped {
        for
          _ <- eventsFile
          file <- tempMd(counterDoc)
          outDir <- tempDir
          result <- MarklitRun.run(
            runConfig(
              file,
              outDir,
              Some("marklit.testfixtures.ThrowingSetupResource")
            )
          )
        yield assertTrue(
          // The run is not failed by a resource setup error — docs still process.
          result.success,
          result.notices.exists(n =>
            n.contains("setup failed") && n.contains("boom-setup")
          )
        )
      }
    },
    test("teardown failure is a notice; the run still succeeds") {
      ZIO.scoped {
        for
          _ <- eventsFile
          file <- tempMd(counterDoc)
          outDir <- tempDir
          result <- MarklitRun.run(
            runConfig(
              file,
              outDir,
              Some("marklit.testfixtures.ThrowingTeardownResource")
            )
          )
        yield assertTrue(
          result.success,
          result.notices.exists(n =>
            n.contains("teardown failed") && n.contains("boom-teardown")
          )
        )
      }
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(180.seconds)
