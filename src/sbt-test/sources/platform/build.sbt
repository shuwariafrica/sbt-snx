enablePlugins(SNXPlugin)

scalaVersion := "3.8.3"

SNX.target := TargetPlatform(OS.Linux, Arch.X86_64)
SNX.Native / crossPaths := true

val check = taskKey[Unit]("verify the active target's source and resource dirs are registered")
check := {
  val srcs = (Compile / unmanagedSourceDirectories).value.map(_.getName).toSet
  assert(srcs.contains("scala-linux"), s"scala-linux source dir not registered: $srcs")
  assert(srcs.contains("scala-linux-x86_64"), s"scala-linux-x86_64 source dir not registered: $srcs")
  val res = (Compile / unmanagedResourceDirectories).value.map(_.getName).toSet
  assert(res.contains("resources-linux"), s"resources-linux not registered: $res")
  assert(res.contains("resources-linux-x86_64"), s"resources-linux-x86_64 not registered: $res")
  // Only the active target is suffixed - no dir for a non-active target.
  assert(!res.contains("resources-osx"), s"non-active osx resource dir leaked: $res")
  streams.value.log.info("snx sources/platform: active-target source and resource dirs registered, no leak")
}
