package marklit

import zio.*
import zio.test.*

import java.nio.file.{Files, Path}

object MarklitRunSpec extends ZIOSpecDefault:

  /** Write `content` to a fresh temp `.md` file and yield its path. */
  private def tempMd(content: String): ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attempt {
        val p = Files.createTempFile("marklit-run-spec-", ".md")
        Files.writeString(p, content)
        p
      }
    )(p => ZIO.attempt(Files.deleteIfExists(p)).ignore)

  private def tempDir: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attempt(Files.createTempDirectory("marklit-run-out-"))
    )(p =>
      ZIO
        .attempt {
          if Files.exists(p) then
            Files
              .walk(p)
              .sorted(java.util.Comparator.reverseOrder())
              .forEach(f => Files.deleteIfExists(f): Unit)
        }
        .ignore
    )

  def spec = suite("MarklitRun")(
    test("generate writes rendered output and reports success") {
      val md =
        """# Title
          |
          |```scala
          |val x = 1 + 1
          |println(x)
          |```
          |""".stripMargin
      ZIO.scoped {
        for
          file   <- tempMd(md)
          outDir <- tempDir
          result <- MarklitRun.run(
            MarklitRunConfig(
              inputFiles = Vector(file),
              outputDir = Some(outDir)
            )
          )
          report = result.files.head
          outPath = report.outputPath.get
          wroteFile <- ZIO.attempt(Files.exists(outPath))
          written   <- ZIO.attempt(Files.readString(outPath))
        yield assertTrue(
          result.success,
          result.files.size == 1,
          report.success,
          report.rendered.exists(_.nonEmpty),
          wroteFile,
          written.contains("2")
        )
      }
    },
    test("check mode reports success without writing output") {
      val md =
        """```scala
          |val ok = "fine"
          |println(ok)
          |```
          |""".stripMargin
      ZIO.scoped {
        for
          file   <- tempMd(md)
          outDir <- tempDir
          result <- MarklitRun.run(
            MarklitRunConfig(
              inputFiles = Vector(file),
              outputDir = Some(outDir),
              check = true
            )
          )
          report = result.files.head
          // Check mode never renders and never writes to the output dir.
          contents <- ZIO.attempt(
            Files.list(outDir).count()
          )
        yield assertTrue(
          result.success,
          report.success,
          report.rendered.isEmpty,
          contents == 0L
        )
      }
    },
    test("a failing block is reported as data, not a failed effect") {
      val md =
        """```scala
          |val n: Int = "not an int"
          |```
          |""".stripMargin
      ZIO.scoped {
        for
          file   <- tempMd(md)
          outDir <- tempDir
          result <- MarklitRun.run(
            MarklitRunConfig(
              inputFiles = Vector(file),
              outputDir = Some(outDir)
            )
          )
          report = result.files.head
          // Compile failure => report.success false, but the effect succeeded.
          wroteAnything <- ZIO.attempt(Files.list(outDir).count())
        yield assertTrue(
          !result.success,
          result.failedCount == 1,
          !report.success,
          report.failedCompiles.nonEmpty,
          // Run did not succeed overall, so no output is written.
          wroteAnything == 0L
        )
      }
    },
    test("missing input file fails the effect") {
      val missing = Path.of("/tmp/marklit-this-does-not-exist-12345.md")
      for exit <- MarklitRun.run(MarklitRunConfig(inputFiles = Vector(missing))).exit
      yield assertTrue(exit.isFailure)
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)
