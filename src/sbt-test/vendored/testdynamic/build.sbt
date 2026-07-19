enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

libraryDependencies += "org.scalameta" %% "munit" % sys.props("munit.version") % Test
testFrameworks += new TestFramework("munit.Framework")

// A NIR library (default deliverable) folds a DYNAMICALLY-linked vendored CMake library into its TEST link only - the
// user's headline case: a test executable (here of a NIR item) linked against an external library DYNAMICALLY. A NIR
// library publishes its C as source, so a vendored library for it belongs in the Test link. `% Test` scopes it there;
// `.linkage(Dynamic)` builds libanswer.so and links `-lanswer -L<builtdir>` + `-rpath`, so the TestAdapter-launched
// test binary resolves libanswer.so at runtime through that rpath. Linux-only (the excludeFilter gates `testdynamic`).
SNX.libraries +=
  NativeLibrary("answer", Vendored.local("vendor/answer").cmake("answer")).linkage(Dynamic) % Test

val checkTestDynamic = taskKey[Unit]("assert the test binary depends dynamically on the vendored libanswer.so")
checkTestDynamic := Def.uncached {
  val binary = (Test / SNX.link).value
  SNX.runtime.value match
    case NativeRuntime.Linux(_, _) =>
      val output = scala.sys.process.Process(Seq("sh", "-c", s"ldd '${binary.getAbsolutePath}' 2>&1 || true")).!!
      assert(
        output.toLowerCase.contains("libanswer.so"),
        s"ldd does not report a dynamic dependency on libanswer.so:\n$output")
      streams.value.log.info("snx vendored/testdynamic: the test binary depends dynamically on libanswer.so")
    case other =>
      streams.value.log.info(s"snx vendored/testdynamic: dynamic-dependency check is Linux-only; skipped on $other")
}
