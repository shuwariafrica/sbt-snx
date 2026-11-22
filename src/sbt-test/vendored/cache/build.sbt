enablePlugins(SNXPlugin)

scalaVersion := sys.props("scala.version")

SNX.libraries += NativeLibrary("answer", Vendored.local("vendor/answer").cmake("answer"))

// The cmake build directory exists only when the backend actually built (a cache miss). The cached output is the
// install prefix under the action cache, not this directory, so on a cache hit (build skipped) it is not recreated.
// It lies in the staging the resolved configuration links: <staging>/build, beside the <staging>/prefix/lib the archive
// is installed in.
val buildDir = taskKey[File]("the answer library's cmake build directory, in the staging the configuration links")
buildDir := Def.uncached {
  val config = (Compile / SNX.config).value.toString
  val archive = "[^\\s,()\\[\\]]+(?:libanswer\\.a|answer\\.lib)".r.findFirstIn(config).map(file).getOrElse(sys.error(s"no answer archive in $config"))
  archive.getParentFile.getParentFile.getParentFile / "build"
}

val assertBuilt = taskKey[Unit]("assert the cmake build ran (build dir present)")
assertBuilt := Def.uncached {
  val directory = buildDir.value
  assert(directory.isDirectory, s"expected the cmake build to have run (build dir present): $directory")
  streams.value.log.info("snx vendored/cache: build ran")
}

val assertCached = taskKey[Unit]("assert the cmake build was skipped (build dir absent)")
assertCached := Def.uncached {
  val directory = buildDir.value
  assert(!directory.exists, s"expected a cache hit to skip the cmake build (build dir absent): $directory")
  streams.value.log.info("snx vendored/cache: cache hit, build skipped")
}

val removeBuild = taskKey[Unit]("delete the cmake build dir")
removeBuild := Def.uncached(IO.delete(buildDir.value))
