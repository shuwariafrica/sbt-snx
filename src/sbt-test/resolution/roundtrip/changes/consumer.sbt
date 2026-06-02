// Consumer phase: resolve the classified native library the producer published, via SNX.dependencies (which
// injects the OS/arch classifier for SNX.target).
enablePlugins(SNXPlugin)

scalaVersion := "3.8.3"

SNX.target := TargetPlatform(OS.Linux, Arch.X86_64)

resolvers += "repo" at (baseDirectory.value / "target" / "repo").toURI.toString

SNX.dependencies += "africa.shuwari" %% "snxlib" % "0.1.0"

val check = taskKey[Unit]("the published classified jar resolves via the injected classifier")
check := {
  val resolved = update.value.toSeq.collect {
    case (_, module, art, file) if module.name.contains("snxlib") => (art.classifier, file.toString)
  }.distinct
  assert(
    resolved.exists { case (c, f) =>
      c.contains("linux-x86_64") && f.contains("snxlib_native0.5_3") && f.contains("linux-x86_64")
    },
    s"published classified jar not resolved through the injected classifier: $resolved"
  )
  streams.value.log.info("snx roundtrip: published classified jar resolved via the injected classifier")
}
