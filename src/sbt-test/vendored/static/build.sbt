enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

SNX.deliverable := Executable
SNX.linkage := Static

// A vendored CMake static archive folded into a FULLY-STATIC executable. The scripted excludeFilter gates the
// `static` leaf to musl or MSVC (the toolchains that link static executables). Proves the collected archive links into
// a static binary; checkStatic then asserts the binary carries no dynamic dependencies.
SNX.libraries += NativeLibrary("answer", Vendored.local("vendor/answer").cmake("answer"))

val checkStatic = taskKey[Unit]("assert the linked executable carries no dynamic dependencies")
checkStatic := Def.uncached {
  val binary = (Compile / SNX.link).value
  SNX.runtime.value match
    case NativeRuntime.Linux(_, _) =>
      val output =
        scala.sys.process.Process(Seq("sh", "-c", s"ldd '${binary.getAbsolutePath}' 2>&1 || true")).!!.toLowerCase
      assert(
        output.contains("not a") || output.contains("statically"),
        s"ldd does not report a static binary; it appears dynamically linked:\n$output")
      streams.value.log.info("snx vendored/static: ldd reports no dynamic dependencies")
    case other =>
      streams.value.log.info(s"snx vendored/static: dynamic-dependency check is Linux-only; skipped on $other")
}
