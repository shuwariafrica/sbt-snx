enablePlugins(SNXPlugin)

scalaVersion := "3.8.3"

// No SNX.target pin: the active target is the host, so the static link targets whatever environment runs this test.
// The build's scripted/excludeFilter restricts it to fully-static-capable environments (musl, MSVC, MinGW) - it is
// skipped on glibc/osx where a fully-static link is unsupported or unreliable.
SNX.vendored += NativeSource.Local("answer", NativeBackend.CMake(Seq("answer")))

// Link the binary fully statically; only the static-capable environments run this test, so the option always applies.
SNX.config := Seq({ case _ => c => c.withLinkingOptions(c.linkingOptions :+ "-static") })

val check = taskKey[Unit]("the vendored static archive and the -static flag both reach the native link")
check := {
  val cfg = (Compile / nativeLink / nativeConfig).value
  assert(cfg.linkingOptions.contains("-static"), s"-static not in linkingOptions: ${cfg.linkingOptions}")
  assert(cfg.linkingOptions.exists(_.endsWith("libanswer.a")), s"vendored archive not in linkingOptions: ${cfg.linkingOptions}")
  streams.value.log.info("snx vendored/static: vendored static archive + -static reach the static link")
}
