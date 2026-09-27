// sbt-zipx brings scala3-compiler, which depends on compiler-interface 1.12.0.
// sbt 2.1 zinc selects 2.1.0-M4, and early-semver fails the meta-build update.
ThisBuild / libraryDependencySchemes += "org.scala-sbt" % "compiler-interface" % "always"
