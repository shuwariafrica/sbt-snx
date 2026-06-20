// Completeness: a project completes an under-declaring managed dependency by declaring the missing native library
// itself. The declaration (a) propagates into the project's OWN published descriptor, so a downstream consumer that
// resolves the project (and transitively the dependency) gets the requirement; and (b) folds into the project's OWN
// link. `vendor` is the under-declaring NIR dependency: it declares no native library, so it ships no descriptor and
// nothing else carries its requirement.
scalaVersion := "3.8.4"
organization := "snx.test"
version := "0.1.0"

// vendor declares no native library, so it publishes no descriptor - the under-declaring dependency the others complete.
val vendor = project.enablePlugins(SNXPlugin)

// producer resolves the under-declaring vendor dependency and declares the system library vendor needs but does not
// itself declare. The declaration exports into producer's OWN descriptor, reaching a consumer that resolves producer.
val producer = project
  .enablePlugins(SNXPlugin)
  .settings(
    SNX.dependencies += "snx.test" %% "vendor" % "0.1.0",
    SNX.libraries += NativeLibrary("snx_vendor_absent")
  )

val consumer = project
  .enablePlugins(SNXPlugin)
  .dependsOn(producer)
  .settings(SNX.deliverable := Executable)

// local completes vendor for its OWN link: it resolves vendor, declares the library, and links an executable, so the
// declaration folds into its own link (not only a published descriptor). vendor ships no descriptor, so the failing
// `-l` can only come from local's own declaration.
val local = project
  .enablePlugins(SNXPlugin)
  .settings(
    SNX.deliverable := Executable,
    SNX.dependencies += "snx.test" %% "vendor" % "0.1.0",
    SNX.libraries += NativeLibrary("snx_local_absent")
  )

val checkExport = taskKey[Unit]("assert the compensating library exported into the producer descriptor")
checkExport := Def.uncached {
  val _ = (producer / Compile / resources).value
  val descriptor = (producer / Compile / resourceManaged).value / "META-INF" / "scala-native" / "native.json"
  assert(descriptor.exists, s"the producer wrote no descriptor at $descriptor")
  val content = IO.read(descriptor)
  assert(content.contains("snx_vendor_absent"), s"the producer descriptor omits the declared library:\n$content")
  streams.value.log.info("snx resolution/completeness: the compensating library exported into the producer descriptor")
}
