enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

SNX.deliverable := Executable

// `SNX.modifiers += SNX.staticRuntime` links the C runtime statically for the deliverable. What "static" means is
// platform-specific, so the scripted excludeFilter gates this fixture to the two toolchains that support it (musl and
// MSVC), and checkStatic asserts the platform-appropriate result:
//   - musl:  `-static` -> a FULLY static executable (libc and all) with no dynamic dependencies at all.
//   - MSVC:  `/MT` -> a static C RUNTIME (libcmt + libvcruntime + libucrt baked in). A Windows binary cannot be fully
//            static - the Win32 system DLLs (kernel32, ntdll, ws2_32, ...) are always dynamic - so "static" here means
//            the binary imports NO CRT DLL (no vcruntime, no UCRT api-ms-win-crt-*, no msvcp140).
// glibc and macOS cannot link a static C runtime and SNX.staticRuntime fails fast there (covered by LinkSuite).
SNX.modifiers += SNX.staticRuntime

val checkStatic = taskKey[Unit]("assert the linked executable is statically linked, in the platform's sense of static")
checkStatic := Def.uncached {
  val binary = (Compile / SNX.link).value
  val log = streams.value.log
  SNX.runtime.value match
    case NativeRuntime.Linux(_, ABI.Musl) =>
      val ldd = scala.sys.process.Process(Seq("sh", "-c", s"ldd '${binary.getAbsolutePath}' 2>&1 || true")).!!.toLowerCase
      assert(ldd.contains("not a") || ldd.contains("statically"), s"musl: the executable is not fully static:\n$ldd")
      log.info("snx link/static: musl - ldd reports no dynamic dependencies (fully static)")
    case NativeRuntime.Windows(_, ABI.Msvc) =>
      // dumpbin lists the binary's DIRECT imports; it is on PATH via the vcvars environment CI exports. A static (/MT)
      // CRT imports none of the CRT DLLs; a dynamic (/MD) CRT imports vcruntime<v>.dll, the api-ms-win-crt-* UCRT
      // forwarders, and msvcp140.dll. The Win32 system DLLs are expected and ignored.
      val deps = scala.sys.process.Process(Seq("dumpbin", "/dependents", binary.getAbsolutePath)).!!.toLowerCase
      val dynamicCrt = Seq("vcruntime", "api-ms-win-crt", "msvcp140").filter(deps.contains)
      assert(
        dynamicCrt.isEmpty,
        s"MSVC: the executable dynamically links the CRT (${dynamicCrt.mkString(", ")}); expected a static /MT runtime:\n$deps")
      log.info("snx link/static: MSVC - the executable imports no CRT DLL (static /MT runtime)")
    case other =>
      sys.error(s"snx link/static: unsupported platform $other reached a musl/MSVC-gated fixture (excludeFilter drift?)")
}
