// sbt-snx interoperating with sbt 2.x's built-in project matrix. The matrix's own `nativePlatform` is bound to the
// official sbt-scala-native plugin (it reflectively enables `scala.scalanative.sbtplugin.ScalaNativePlugin`), which an
// sbt-snx project does not have - so sbt-snx provides `snxPlatform`, the analogous extension that adds a native row
// (the standard `VirtualAxis.native` axis) with SNXPlugin enabled. The one matrix expands into a JVM row (`app`) and a
// native row (`appNative`); each compiles its own platform source directory (`scalajvm` / `scalanative`).
val scala3 = "3.8.4"
scalaVersion := scala3

val app = projectMatrix
  .jvmPlatform(scalaVersions = Seq(scala3))
  .snxPlatform(scalaVersions = Seq(scala3), settings = Seq(SNX.deliverable := Executable))
