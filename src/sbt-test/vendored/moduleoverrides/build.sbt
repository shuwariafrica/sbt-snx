enablePlugins(SNXPlugin)

scalaVersion := sys.props("scala.version")

SNX.deliverable := Executable

// A local CMake project whose CMakeLists `include(AnswerModule)` resolves only because the plugin prepends the
// moduleOverrides directory to CMAKE_MODULE_PATH; without it, cmake configure aborts. The module sets ANSWER_VALUE,
// compile-defined into answer.c, so a successful build and run proves the moduleOverrides setter routed the overrides
// directory into the configure (and content-hashed it into the cache key).
SNX.libraries += NativeLibrary(
  "answer",
  Vendored.local("vendor/answer").cmake("answer").moduleOverrides(baseDirectory.value / "cmake-modules"))
