enablePlugins(ScalaNativePlugin, SnxPlugin)

scalaVersion := "3.8.3"

snxTarget := TargetPlatform(Os.Linux, Arch.X86_64)

// A real JVM artefact published with OS/arch classifiers, declared with `%` (no Scala/platform
// suffix) and lifted bare via the given Conversion. Exercises a genuine classified-artefact download.
platformDependencies += "io.netty" % "netty-transport-native-epoll" % "4.1.115.Final"

val check = taskKey[Unit]("verify the injected classifier resolves a real artefact")
check := {
  val resolved = update.value.toSeq.collect {
    case (_, module, artifact, file) if module.name.contains("netty-transport-native-epoll") =>
      (artifact.classifier, file.toString)
  }.distinct
  assert(
    resolved.exists { case (c, f) => c.contains("linux-x86_64") && f.contains("linux-x86_64") },
    s"epoll not resolved with the injected linux-x86_64 classifier: $resolved"
  )
  streams.value.log.info("snx scripted check: real classified download OK")
}

val checkClassifiers = taskKey[Unit]("verify sources/doc resolution (updateClassifiers) coexists with the injected classifier")
checkClassifiers := {
  val epoll = updateClassifiers.value.toSeq.collect {
    case (_, module, artifact, file) if module.name.contains("netty-transport-native-epoll") =>
      (artifact.classifier, file.toString)
  }.distinct
  streams.value.log.info(s"updateClassifiers epoll artifacts: $epoll")
  assert(
    epoll.exists { case (c, f) => c.contains("sources") && f.endsWith("-sources.jar") },
    s"sources jar not resolved by updateClassifiers: $epoll"
  )
}
