package marklit.compiler

import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.ConcurrentHashMap

/** Normalize classpath entries for scalac / dotc.
  *
  * Those compilers only treat regular files ending in `.jar` / `.jmod` / `.zip`
  * (or directories) as archives. sbt 2.0.4's content-addressed store materializes
  * jars as extensionless CAS files (`…/sha256-…-<size>`), which URL classloaders
  * accept but scalac silently skips — producing "Not found" errors for classes
  * that are on the JVM classpath. Remap those to a temp `*.jar` link (or copy).
  */
private[marklit] object ClasspathEntries:

  private val materialized = new ConcurrentHashMap[String, String]()

  def forScalac(entries: Seq[String]): Seq[String] =
    entries.map(ensureScalacReadable)

  def forScalac(entries: Vector[String]): Vector[String] =
    entries.map(ensureScalacReadable)

  private def ensureScalacReadable(entry: String): String =
    val path =
      try Paths.get(entry).toAbsolutePath.normalize
      catch case _: Exception => return entry
    if !Files.isRegularFile(path) then entry
    else
      val name = path.getFileName.toString
      if hasArchiveSuffix(name) then entry
      else if !isZipMagic(path) then entry
      else
        materialized.computeIfAbsent(
          path.toString,
          _ => materializeJar(path)
        )

  private def hasArchiveSuffix(name: String): Boolean =
    val lower = name.toLowerCase
    lower.endsWith(".jar") || lower.endsWith(".jmod") || lower.endsWith(".zip")

  private def isZipMagic(path: Path): Boolean =
    try
      val in = Files.newInputStream(path)
      try
        val b0 = in.read()
        val b1 = in.read()
        // PK\x03\x04 (local file) or PK\x05\x06 (empty archive)
        b0 == 'P' && b1 == 'K'
      finally in.close()
    catch case _: Exception => false

  private def materializeJar(source: Path): String =
    val dir = Files.createTempDirectory("marklit-cp-")
    dir.toFile.deleteOnExit()
    val dest = dir.resolve(source.getFileName.toString + ".jar")
    try
      Files.createSymbolicLink(dest, source)
    catch
      case _: UnsupportedOperationException | _: java.nio.file.FileSystemException =>
        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING)
    dest.toFile.deleteOnExit()
    dest.toAbsolutePath.toString

end ClasspathEntries
