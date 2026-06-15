// The `.options` per-dependency patch, end to end. A project patches an under-declaring native dependency - one that
// ships no descriptor of its own - and that patch (a) propagates into the project's OWN published descriptor for a
// downstream consumer (completeness), exported at RUNTIME scope so a `% Runtime` dependency is not dropped, and
// (b) folds into the patching project's OWN link, not only its descriptor. vendor is the descriptor-less dependency.
scalaVersion := "3.8.4"
organization := "snx.test"
version := "0.1.0"

val vendor = project.enablePlugins(SNXPlugin)

// producer patches a RUNTIME-scoped vendor dependency. A consumer links the Runtime classpath and resolves a library's
// compile and runtime dependencies transitively, so a `% Runtime` requirement is needed downstream and must export;
// were the export scope Compile-visible it would be dropped (the regression this discriminates).
val producer = project
  .enablePlugins(SNXPlugin)
  .settings(
    SNX.dependencies += "snx.test" %% "vendor" % "0.1.0" % Runtime options { case _ => Usage.libraries("snx_vendor_absent") }
  )

val consumer = project
  .enablePlugins(SNXPlugin)
  .dependsOn(producer)
  .settings(SNX.deliverable := Executable)

// local patches vendor for its OWN link, proving `.options` folds into the patching project's link, not only its
// published descriptor.
val local = project
  .enablePlugins(SNXPlugin)
  .settings(
    SNX.deliverable := Executable,
    SNX.dependencies += "snx.test" %% "vendor" % "0.1.0" options { case _ => Usage.libraries("snx_local_absent") }
  )

val checkPatch = taskKey[Unit]("assert the runtime-scoped per-dependency patch propagated into the producer descriptor")
checkPatch := Def.uncached {
  val _ = (producer / Compile / resources).value
  val descriptor = (producer / Compile / resourceManaged).value / "META-INF" / "scala-native" / "native.json"
  assert(descriptor.exists, s"the producer wrote no descriptor at $descriptor")
  val content = IO.read(descriptor)
  assert(content.contains("snx_vendor_absent"), s"the producer descriptor omits the runtime-scoped dependency patch:\n$content")
  streams.value.log.info("snx resolution/completeness: the runtime-scoped dependency patch propagated into the producer descriptor")
}
