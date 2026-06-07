enablePlugins(SNXPlugin)

scalaVersion := "3.8.3"

SNX.target := TargetPlatform(OS.Linux, Arch.X86_64)

SNX.vendored += NativeSource.Local("answer", NativeBackend.CMake(Seq("answer")))

// The cmake build directory; it exists only when the backend actually built (a cache miss). The cached output is
// the install prefix, not this directory, so on a cache hit (build skipped) it is not recreated.
val buildDir = settingKey[File]("the answer library's cmake build directory")
buildDir := target.value / "snx" / "vendored" / "answer" / "build"

val assertBuilt = taskKey[Unit]("assert the cmake build ran (build dir present)")
assertBuilt := {
  val d = buildDir.value
  assert(d.isDirectory, s"expected the cmake build to have run (build dir present): $d")
  streams.value.log.info("snx vendored/cache: build ran")
}

val assertCached = taskKey[Unit]("assert the cmake build was skipped (build dir absent)")
assertCached := {
  val d = buildDir.value
  assert(!d.exists, s"expected a cache hit to skip the cmake build (build dir absent): $d")
  // The restored artefact must be real, not a dangling reference.
  val archives = (Compile / nativeLink / nativeConfig).value.linkingOptions.filter(_.endsWith("libanswer.a"))
  assert(archives.nonEmpty, "cache hit produced no archive in linkingOptions")
  assert(archives.forall(path => file(path).isFile), s"cache hit restored a missing archive: $archives")
  streams.value.log.info("snx vendored/cache: cache hit, restored archive present")
}

val removeBuild = taskKey[Unit]("delete the cmake build dir")
removeBuild := IO.delete(buildDir.value)
