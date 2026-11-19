enablePlugins(SNXPlugin)

scalaVersion := sys.props("scala.version")

SNX.deliverable := Executable

// A vendored CMake project whose top-level CMakeLists.txt is at vendor/multi/build/cmake, building three static archives
// that reference each other in a cycle, and installing a man page to an absolute destination fixed at configure.
SNX.libraries += NativeLibrary("multi", Vendored.local("vendor/multi").cmake().directory("build/cmake"))

// The plugin's staging prefix for this library: target/snx/vendored/<config>/<origin-slug>/prefix (main config,
// directory "vendor/multi" slugged to "vendor-multi").
val prefix = settingKey[File]("the multi library's staging install prefix")
prefix := target.value / "snx" / "vendored" / "main" / "vendor-multi" / "prefix"

val assertManStaged = taskKey[Unit]("assert the man page was installed under the staging prefix")
assertManStaged := Def.uncached {
  val page = prefix.value / "share" / "man" / "man3" / "multi.3"
  assert(page.isFile, s"expected the man page under the staging prefix: $page")
  streams.value.log.info(s"snx vendored/cmakeproject: man page staged at $page")
}
