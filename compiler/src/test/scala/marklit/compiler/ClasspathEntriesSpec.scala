package marklit.compiler

import zio.test.*

import java.nio.file.Files

object ClasspathEntriesSpec extends ZIOSpecDefault:

  def spec = suite("ClasspathEntries")(
    test("extensionless zip/jar is remapped to a .jar path scalac can read") {
      val dir = Files.createTempDirectory("marklit-cp-spec-")
      val cas = dir.resolve("sha256-deadbeef-12")
      // Minimal empty ZIP (PK\x05\x06 …)
      Files.write(
        cas,
        Array[Byte](
          'P', 'K', 5, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        )
      )
      val out = ClasspathEntries.forScalac(Vector(cas.toString))
      assertTrue(
        out.size == 1,
        out.head.endsWith(".jar"),
        Files.isRegularFile(java.nio.file.Paths.get(out.head)) ||
          Files.isSymbolicLink(java.nio.file.Paths.get(out.head))
      )
    },
    test("real .jar paths and directories are left alone") {
      val dir = Files.createTempDirectory("marklit-cp-spec-dir-")
      val jar = Files.createTempFile("marklit-cp-spec-", ".jar")
      Files.write(jar, Array[Byte]('P', 'K', 5, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
      val out = ClasspathEntries.forScalac(Vector(jar.toString, dir.toString))
      assertTrue(out == Vector(jar.toString, dir.toString))
    },
    test("identical CAS entries reuse the same materialized path") {
      val cas = Files.createTempFile("marklit-cp-cas-", "")
      Files.write(cas, Array[Byte]('P', 'K', 5, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
      // Drop the random suffix file's accidental extensionless nature is enough
      val a = ClasspathEntries.forScalac(Vector(cas.toString)).head
      val b = ClasspathEntries.forScalac(Vector(cas.toString)).head
      assertTrue(a == b, a.endsWith(".jar"))
    }
  )

end ClasspathEntriesSpec
