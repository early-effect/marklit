package marklit.cache

import marklit.model.*
import zio.*
import zio.test.*

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

object BlockCacheSpec extends ZIOSpecDefault:

  private def withTempDir[A](f: Path => UIO[A]): UIO[A] =
    ZIO.acquireReleaseWith(
      ZIO.succeed(Files.createTempDirectory("marklit-cache-test"))
    )(p =>
      ZIO.succeed {
        if Files.isDirectory(p) then
          val it = Files
            .walk(p)
            .sorted(java.util.Comparator.reverseOrder())
          try
            it.forEach { f =>
              if f != p then Files.deleteIfExists(f): Unit
            }
          finally it.close()
          Files.deleteIfExists(p): Unit
      }
    )(f)

  private val sampleResult = CompileResult(
    success = true,
    diagnostics = List(
      ScalaDiagnostic(
        DiagnosticSeverity.Warning,
        "unused variable",
        line = 3,
        column = 5,
        file = Some("test.md")
      )
    )
  )

  private def sampleKey(code: String = "println(1)") =
    BlockCacheKey.make(
      code = code,
      priorCode = Vector("val x = 1"),
      scalaVersion = "3.8.2",
      classpath = Vector("a.jar", "b.jar"),
      classpathHashes = Vector("h1", "h2"),
      scalacOptions = Vector("-Xfatal-warnings"),
      isZIOApp = false,
      file = "test.md",
      startLine = 10,
      startColumn = 1
    )

  def spec = suite("BlockCache")(
    test("noop returns None and ignores writes") {
      val key = sampleKey()
      for
        before <- BlockCache.noop.get(key)
        _ <- BlockCache.noop.put(key, sampleResult)
        after <- BlockCache.noop.get(key)
      yield assertTrue(before.isEmpty && after.isEmpty)
    },
    test("disk cache roundtrip preserves CompileResult") {
      withTempDir { root =>
        val cache = BlockCache.disk(root)
        val key = sampleKey()
        for
          miss <- cache.get(key)
          _ <- cache.put(key, sampleResult)
          hit <- cache.get(key)
        yield assertTrue(
          miss.isEmpty,
          hit.contains(sampleResult)
        )
      }
    },
    test("different inputs produce different keys") {
      val k1 = sampleKey("println(1)")
      val k2 = sampleKey("println(2)")
      assertTrue(k1 != k2)
    },
    test("same inputs produce same key") {
      val k1 = sampleKey()
      val k2 = sampleKey()
      assertTrue(k1 == k2)
    },
    test("changing classpath hash invalidates key") {
      val k1 = BlockCacheKey.make(
        code = "x",
        priorCode = Vector.empty,
        scalaVersion = "3.8.2",
        classpath = Vector("a.jar"),
        classpathHashes = Vector("hash-a-v1"),
        scalacOptions = Vector.empty,
        isZIOApp = false,
        file = "f",
        startLine = 1,
        startColumn = 1
      )
      val k2 = BlockCacheKey.make(
        code = "x",
        priorCode = Vector.empty,
        scalaVersion = "3.8.2",
        classpath = Vector("a.jar"),
        classpathHashes = Vector("hash-a-v2"),
        scalacOptions = Vector.empty,
        isZIOApp = false,
        file = "f",
        startLine = 1,
        startColumn = 1
      )
      assertTrue(k1 != k2)
    },
    test("clear removes all entries") {
      withTempDir { root =>
        val cache = BlockCache.disk(root)
        val k1 = sampleKey("a")
        val k2 = sampleKey("b")
        for
          _ <- cache.put(k1, sampleResult)
          _ <- cache.put(k2, sampleResult)
          _ <- cache.clear
          h1 <- cache.get(k1)
          h2 <- cache.get(k2)
        yield assertTrue(h1.isEmpty && h2.isEmpty)
      }
    },
    test("malformed entries return None on get") {
      withTempDir { root =>
        val cache = BlockCache.disk(root)
        val key = sampleKey()
        for
          _ <- ZIO.succeed {
            // Write a bogus file at the entry's expected path.
            val k = key.value
            val dir = root.resolve(k.substring(0, 2))
            Files.createDirectories(dir): Unit
            Files.writeString(dir.resolve(s"$k.json"), "this is not json"): Unit
          }
          hit <- cache.get(key)
        yield assertTrue(hit.isEmpty)
      }
    },
    test("put/get round-trips class files when result has classFilesDir") {
      withTempDir { root =>
        withTempDir { srcDir =>
          val cache = BlockCache.disk(root)
          val key = sampleKey()
          for
            _ <- ZIO.succeed {
              // Lay down a couple of fake class files in nested subdirs,
              // mimicking dotc's per-block output layout.
              Files.writeString(
                srcDir.resolve("MarklitWrapper$.class"),
                "AAAA"
              ): Unit
              val sub = srcDir.resolve("foo").resolve("bar")
              Files.createDirectories(sub): Unit
              Files.writeString(sub.resolve("Inner.class"), "BBBB"): Unit
            }
            classedResult = sampleResult.copy(
              success = true,
              classFilesDir = Some(srcDir)
            )
            _ <- cache.put(key, classedResult)
            hit <- cache.get(key)
            cachedDir = hit.flatMap(_.classFilesDir)
            // Listing helps when the assertion below fires.
            listing <- ZIO.succeed {
              cachedDir match
                case Some(d) if Files.isDirectory(d) =>
                  val it = Files.walk(d)
                  try it.iterator.asScala.toList.map(_.toString)
                  finally it.close()
                case _ => Nil
            }
          yield assertTrue(
            hit.exists(_.success),
            cachedDir.isDefined,
            cachedDir.exists(d => d != srcDir),
            // Listing must include the wrapper class file.
            listing.exists(_.endsWith("MarklitWrapper$.class")),
            listing.exists(s =>
              s.endsWith(
                s"foo${java.io.File.separator}bar${java.io.File.separator}Inner.class"
              )
            )
          )
        }
      }
    },
    test("classFilesDir not materialized when result is unsuccessful") {
      withTempDir { root =>
        withTempDir { srcDir =>
          val cache = BlockCache.disk(root)
          val key = sampleKey()
          for
            _ <- ZIO.succeed {
              Files.writeString(
                srcDir.resolve("MarklitWrapper$.class"),
                "AAAA"
              ): Unit
            }
            failedResult = sampleResult.copy(
              success = false,
              classFilesDir = Some(srcDir)
            )
            _ <- cache.put(key, failedResult)
            hit <- cache.get(key)
          yield assertTrue(
            hit.isDefined,
            hit.exists(!_.success),
            hit.flatMap(_.classFilesDir).isEmpty
          )
        }
      }
    },
    test("clear removes class files alongside JSON entries") {
      withTempDir { root =>
        withTempDir { srcDir =>
          val cache = BlockCache.disk(root)
          val key = sampleKey()
          for
            _ <- ZIO.succeed {
              Files.writeString(srcDir.resolve("X.class"), "x"): Unit
            }
            _ <- cache.put(
              key,
              sampleResult.copy(success = true, classFilesDir = Some(srcDir))
            )
            beforeHit <- cache.get(key)
            _ <- cache.clear
            afterHit <- cache.get(key)
          yield assertTrue(
            beforeHit.flatMap(_.classFilesDir).isDefined,
            afterHit.isEmpty
          )
        }
      }
    }
  )
