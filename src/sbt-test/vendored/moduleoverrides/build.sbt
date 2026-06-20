enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

SNX.deliverable := Executable

// A local CMake project whose CMakeLists `include(AnswerModule)` resolves only because the plugin prepends the
// moduleOverrides directory to CMAKE_MODULE_PATH; without it, cmake configure aborts. The module sets ANSWER_VALUE,
// compile-defined into answer.c, so a successful build and run proves the 3-arg cmake overload routed the overrides
// directory into the configure (and content-hashed it into the cache key).
SNX.libraries += NativeLibrary(
  "answer",
  Vendored.local("vendor/answer").cmake(Seq("answer"), PartialFunction.empty, baseDirectory.value / "cmake-modules"))
