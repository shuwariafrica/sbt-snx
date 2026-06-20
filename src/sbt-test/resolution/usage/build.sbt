// The producer is a NIR library that exports a per-platform link requirement; the consumer resolves it and folds that
// requirement into its own link, where the deliberately-unresolvable library makes the link fail - proving the
// requirement propagated through the descriptor on the consumer's classpath.
val producer = project
  .enablePlugins(SNXPlugin)
  .settings(
    scalaVersion := "3.8.4",
    SNX.libraries := { case _ => Seq(NativeLibrary("snx_propagated_absent")) }
  )

val consumer = project
  .enablePlugins(SNXPlugin)
  .dependsOn(producer)
  .settings(
    scalaVersion := "3.8.4",
    SNX.deliverable := Executable
  )

val checkDescriptor = taskKey[Unit]("assert the producer wrote its usage descriptor")
checkDescriptor := Def.uncached {
  val _ = (producer / Compile / resources).value
  val descriptor = (producer / Compile / resourceManaged).value / "META-INF" / "scala-native" / "native.json"
  assert(descriptor.exists, s"the producer did not write a descriptor at $descriptor")
  val content = IO.read(descriptor)
  assert(content.contains("snx_propagated_absent"), s"the descriptor omits the exported requirement:\n$content")
  streams.value.log.info("snx resolution/usage: the producer wrote its usage descriptor")
}
