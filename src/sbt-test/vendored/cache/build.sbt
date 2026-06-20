enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

SNX.libraries += NativeLibrary("answer", Vendored.local("vendor/answer").cmake("answer"))

// The cmake build directory exists only when the backend actually built (a cache miss). The cached output is the
// install prefix under the action cache, not this directory, so on a cache hit (build skipped) it is not recreated.
// This path mirrors the plugin's staging scheme: target/snx/vendored/<config>/<origin-slug>/build (main config,
// directory "vendor/answer" slugged to "vendor-answer").
val buildDir = settingKey[File]("the answer library's cmake build directory")
buildDir := target.value / "snx" / "vendored" / "main" / "vendor-answer" / "build"

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
removeBuild := IO.delete(buildDir.value)
