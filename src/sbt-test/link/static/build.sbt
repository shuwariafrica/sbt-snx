enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

SNX.deliverable := Executable
SNX.linkage := Static

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
      streams.value.log.info("snx link/static: ldd reports no dynamic dependencies")
    case other =>
      streams.value.log.info(s"snx link/static: dynamic-dependency check is Linux-only; skipped on $other")
}
