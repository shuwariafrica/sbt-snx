// sbt-snx interoperating with sbt 2.x's built-in project matrix. The matrix's own `nativePlatform` is bound to the
// official sbt-scala-native plugin, and from sbt 2.1 so is every row on `VirtualAxis.native`, so `snxPlatform` adds its
// row on `SNX.axis` instead: a native row's id suffix and source directories, with SNXPlugin enabled. Each matrix
// expands into a JVM row and a native row, each compiling its own platform source directory (`scalajvm` /
// `scalanative`); `app` depends on `lib`, so each of its rows must resolve the matching row of the other matrix.
val scala3 = sys.props("scala.version")
scalaVersion := scala3

val lib = projectMatrix
  .jvmPlatform(scalaVersions = Seq(scala3))
  .snxPlatform(scalaVersions = Seq(scala3))

val app = projectMatrix
  .dependsOn(lib)
  .jvmPlatform(scalaVersions = Seq(scala3))
  .snxPlatform(scalaVersions = Seq(scala3), settings = Seq(SNX.deliverable := Executable))

val checkFinder = taskKey[Unit]("assert the snxNative finder selects the matrix's sbt-snx row")
checkFinder := {
  val found = app.snxNative(scala3).id
  if (found != "appNative") sys.error(s"app.snxNative($scala3) found $found, expected appNative")
}
