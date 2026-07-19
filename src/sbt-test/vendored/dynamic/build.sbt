enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

libraryDependencies += "org.scalameta" %% "munit" % sys.props("munit.version") % Test
testFrameworks += new TestFramework("munit.Framework")

SNX.deliverable := Executable

// A vendored CMake library linked DYNAMICALLY into the executable. Provisioning (built from source) is orthogonal to
// linkage (dynamic): a vendored library is conceptually a system library whose source we happen to build here (because
// it is absent, or the wrong version, on the build host). The `.linkage(Dynamic)` drives the CMake backend to build it
// shared (BUILD_SHARED_LIBS=ON), then the link renders exactly a system dynamic link plus the build's own search path:
// `-lanswer -L<builtdir>`, with an `-rpath` so the built libanswer.so is found when the binary runs here on the build
// host (a real target would supply libanswer.so itself, so nothing is shipped). Linux-only: the excludeFilter gates
// `dynamic` off non-Linux (macOS install_name and Windows DLL redistribution are follow-ons).
SNX.libraries += NativeLibrary("answer", Vendored.local("vendor/answer").cmake("answer")).linkage(Dynamic)

val checkDynamic = taskKey[Unit]("assert the linked executable depends dynamically on the vendored libanswer.so")
checkDynamic := Def.uncached {
  val binary = (Compile / SNX.link).value
  SNX.runtime.value match
    case NativeRuntime.Linux(_, _) =>
      val output = scala.sys.process.Process(Seq("sh", "-c", s"ldd '${binary.getAbsolutePath}' 2>&1 || true")).!!
      assert(
        output.toLowerCase.contains("libanswer.so"),
        s"ldd does not report a dynamic dependency on libanswer.so:\n$output")
      streams.value.log.info("snx vendored/dynamic: ldd reports a dynamic dependency on libanswer.so")
    case other =>
      streams.value.log.info(s"snx vendored/dynamic: dynamic-dependency check is Linux-only; skipped on $other")
}
