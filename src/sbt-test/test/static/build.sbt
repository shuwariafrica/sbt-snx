enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

libraryDependencies += "org.scalameta" %% "munit" % "1.3.2" % Test
testFrameworks += new TestFramework("munit.Framework")

// A NIR deliverable (default): the main artefact is a jar and is never linked. Its test binary is always an
// application, and `Test / SNX.modifiers += SNX.staticRuntime` opts THAT into a static C runtime - scoped to the test
// binary, leaving the (unlinked) deliverable untouched. Gated to the static-capable toolchains (musl/MSVC);
// checkStaticTest asserts the platform-appropriate result (see link/static for the per-platform meaning of "static": a
// fully-static binary on musl, a static /MT C runtime on MSVC).
Test / SNX.modifiers += SNX.staticRuntime

val checkStaticTest = taskKey[Unit]("assert the test binary is statically linked, in the platform's sense of static")
checkStaticTest := Def.uncached {
  val binary = (Test / SNX.link).value
  val log = streams.value.log
  assert(binary.isFile, s"no linked test binary at $binary")
  SNX.runtime.value match
    case NativeRuntime.Linux(_, ABI.Musl) =>
      val ldd = scala.sys.process.Process(Seq("sh", "-c", s"ldd '${binary.getAbsolutePath}' 2>&1 || true")).!!.toLowerCase
      assert(ldd.contains("not a") || ldd.contains("statically"), s"musl: the test binary is not fully static:\n$ldd")
      log.info("snx test/static: musl - the test binary is fully static (ldd reports no dynamic dependencies)")
    case NativeRuntime.Windows(_, ABI.Msvc) =>
      // dumpbin lists the binary's DIRECT imports (on PATH via the vcvars environment CI exports). A static /MT CRT
      // imports no CRT DLL; a dynamic /MD CRT imports vcruntime<v>.dll, the api-ms-win-crt-* UCRT forwarders, and
      // msvcp140.dll. The Win32 system DLLs are expected and ignored.
      val deps = scala.sys.process.Process(Seq("dumpbin", "/dependents", binary.getAbsolutePath)).!!.toLowerCase
      val dynamicCrt = Seq("vcruntime", "api-ms-win-crt", "msvcp140").filter(deps.contains)
      assert(
        dynamicCrt.isEmpty,
        s"MSVC: the test binary dynamically links the CRT (${dynamicCrt.mkString(", ")}); expected a static /MT runtime:\n$deps")
      log.info("snx test/static: MSVC - the test binary imports no CRT DLL (static /MT runtime)")
    case other =>
      sys.error(s"snx test/static: unsupported platform $other reached a musl/MSVC-gated fixture (excludeFilter drift?)")
}
