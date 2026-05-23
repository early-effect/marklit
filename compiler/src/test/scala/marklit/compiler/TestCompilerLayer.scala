package marklit.compiler

import zio.*

/** Layer that produces a default-version [[Compiler]] via [[CompilerFactory]]
  * for tests that just need *some* working compiler. Reads the shim jar from
  * the test classpath resource (wired in build.sbt's
  * `compiler/Test/resourceGenerators`).
  */
object TestCompilerLayer:
  val layer: ZLayer[Any, Throwable, Compiler] =
    CompilerFactory.layer >>> ZLayer.fromZIO {
      ZIO.serviceWithZIO[CompilerFactory](
        _.forVersion(CompilerFactory.defaultScalaVersion)
      )
    }
