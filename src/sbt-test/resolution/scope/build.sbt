// Export scope, expressed in the library definition: a `% Test` library - scoped to the test link in its own
// definition, mirroring a managed dependency's `% Test` - does NOT export into the published descriptor, while a
// library with no configuration applies to every link and exports. (That a `% Test` library still folds into the test
// link is proven by vendored/test, whose binding tests link a `% Test` vendored library.)
scalaVersion := "3.8.4"
organization := "snx.test"
version := "0.1.0"

val lib = project
  .enablePlugins(SNXPlugin)
  .settings(
    SNX.libraries := { case _ => Seq(NativeLibrary("snx_export"), NativeLibrary("snx_test_only") % Test) }
  )

val checkScope = taskKey[Unit]("the unscoped library exports; the `% Test` library does not")
checkScope := Def.uncached {
  val _ = (lib / Compile / resources).value
  val descriptor = (lib / Compile / resourceManaged).value / "META-INF" / "scala-native" / "native.json"
  assert(descriptor.exists, s"no descriptor at $descriptor")
  val content = IO.read(descriptor)
  assert(content.contains("snx_export"), s"the descriptor omits the unscoped library:\n$content")
  assert(!content.contains("snx_test_only"), s"a `% Test` library must not export into the descriptor:\n$content")
  streams.value.log.info("snx resolution/scope: an unscoped library exports; a `% Test` library does not")
}
