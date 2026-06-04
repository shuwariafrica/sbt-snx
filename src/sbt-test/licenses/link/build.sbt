enablePlugins(SNXPlugin)

scalaVersion := "3.8.3"

SNX.target := TargetPlatform(OS.Linux, Arch.X86_64)

// A vendored library declaring an MIT licence and linked into the binary. Producing the link must auto-aggregate the
// third-party native licences into an SPDX document beside the build output, with no explicit licenseReport call.
SNX.vendored += NativeSource
  .Local("answer", NativeBackend.CMake(Seq("answer")))
  .licensed("MIT", file("LICENSE"))

val check = taskKey[Unit]("the native link auto-writes the aggregated SPDX report containing the vendored licence")
check := {
  val report = target.value / "snx" / "licenses" / "compile" / "native-licenses.spdx.json"
  assert(report.isFile, s"the link did not auto-write the aggregate report at $report")
  val flat = IO.read(report).replaceAll("\\s+", "")
  assert(flat.contains("\"name\":\"answer\""), s"vendored library missing from the auto report: $flat")
  assert(flat.contains("\"licenseDeclared\":\"MIT\""), s"MIT licence missing from the auto report: $flat")
  streams.value.log.info("snx licenses/link: nativeLink auto-produced the aggregated SPDX report")
}
