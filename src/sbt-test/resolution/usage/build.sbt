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

// The bare `-> consumer/Compile/snxLink` proves only that the link fails, which a parse error or any unrelated fault
// would satisfy just as well. Assert WHY it failed: the requirement reached the consumer through the producer's
// descriptor, so the failure must name the library and say where it came from rather than leaving the reader with the
// linker's own `cannot find -l...`.
val checkUnresolved = taskKey[Unit]("assert the consumer's link fails with a directed, attributed error")
checkUnresolved := Def.uncached {
  val outcome = (consumer / Compile / SNX.link).result.value
  outcome match {
    case Result.Value(_) => sys.error("the consumer link resolved a library that does not exist")
    case Result.Inc(cause) =>
      val reported = Incomplete.allExceptions(cause).toSeq.map(failure => String.valueOf(failure.getMessage)).mkString("\n")
      assert(reported.contains("snx_propagated_absent"), s"the failure does not name the unresolved library:\n$reported")
      assert(
        reported.contains("required by a resolved dependency's descriptor"),
        s"the failure does not attribute the library to the dependency that requires it:\n$reported")
      assert(reported.contains("SNX.libraries"), s"the failure does not say how to provide the library:\n$reported")
      streams.value.log.info("snx resolution/usage: the unresolved library failed with a directed, attributed error")
  }
}
