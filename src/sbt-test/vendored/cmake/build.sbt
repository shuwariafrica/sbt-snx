enablePlugins(SNXPlugin)

scalaVersion := "3.8.3"

SNX.target := TargetPlatform(OS.Linux, Arch.X86_64)

// A local CMake project under vendor/answer, built to a static library and linked into the native binary. The
// per-platform options exercise every NativeOptions channel: the C define SNX_VENDORED_C (and the all-sources
// define SNX_VENDORED_COMPILE) are required by glue.c, so the link succeeds only if they reach the C compile.
SNX.vendored += NativeSource.Local("answer", NativeBackend.CMake(Seq("answer")))
  .options { case NativePlatform.Linux(_, _) =>
    NativeOptions().withLinking("-lm").withCompile("-DSNX_VENDORED_COMPILE").withC("-DSNX_VENDORED_C").withCpp("-DSNX_VENDORED_CPP")
  }

val check = taskKey[Unit]("verify the built archive, include dir, and per-source options reach nativeConfig")
check := {
  val cfg = (Compile / nativeLink / nativeConfig).value
  assert(cfg.linkingOptions.exists(_.endsWith("libanswer.a")), s"built archive not in linkingOptions: ${cfg.linkingOptions}")
  assert(
    cfg.compileOptions.exists(o => o.startsWith("-I") && o.endsWith("include")),
    s"vendored include dir not in compileOptions: ${cfg.compileOptions}")
  // The source's per-platform NativeOptions fold through to each matching nativeConfig channel.
  assert(cfg.linkingOptions.contains("-lm"), s"source linking option missing: ${cfg.linkingOptions}")
  assert(cfg.compileOptions.contains("-DSNX_VENDORED_COMPILE"), s"source compile option missing: ${cfg.compileOptions}")
  assert(cfg.cOptions.contains("-DSNX_VENDORED_C"), s"source c option missing: ${cfg.cOptions}")
  assert(cfg.cppOptions.contains("-DSNX_VENDORED_CPP"), s"source cpp option missing: ${cfg.cppOptions}")
  streams.value.log.info("snx vendored/cmake: built archive + include dir + per-source options reach nativeConfig")
}
