enablePlugins(ScalaNativePlugin, SnxPlugin)

scalaVersion := "3.8.3"

// snxTarget defaults to the host. The check asserts the plugin's host os/arch detection and its toolchain
// libc/ABI resolution against the ground truth (snx.expect.{os,arch,env}) the build forwards from CI.
snxNative := Seq({
  case NativePlatform.Linux(_, LinuxLibc.Glibc)    => c => c.withLinkingOptions(c.linkingOptions :+ "-lsnx-linux-glibc")
  case NativePlatform.Linux(_, LinuxLibc.Musl)     => c => c.withLinkingOptions(c.linkingOptions :+ "-lsnx-linux-musl")
  case NativePlatform.Osx(_)                       => c => c.withLinkingOptions(c.linkingOptions :+ "-lsnx-osx")
  case NativePlatform.Windows(_, WindowsAbi.Msvc)  => c => c.withLinkingOptions(c.linkingOptions :+ "-lsnx-windows-msvc")
  case NativePlatform.Windows(_, WindowsAbi.Mingw) => c => c.withLinkingOptions(c.linkingOptions :+ "-lsnx-windows-mingw")
})

val flagFor = Map(
  ("linux", "glibc") -> "-lsnx-linux-glibc",
  ("linux", "musl") -> "-lsnx-linux-musl",
  ("osx", "") -> "-lsnx-osx",
  ("windows", "msvc") -> "-lsnx-windows-msvc",
  ("windows", "mingw") -> "-lsnx-windows-mingw"
)

val check = taskKey[Unit]("assert plugin platform detection matches the injected ground truth")
check := Def.uncached {
  def prop(k: String): String = sys.props.getOrElse(s"snx.expect.$k", sys.error(s"snx.expect.$k not set"))
  val classifier = prop("classifier")
  val os = prop("os")
  val env = sys.props.getOrElse("snx.expect.env", "")

  assert(host.classifier == classifier, s"host detection ${host.classifier} != $classifier")
  assert(snxTarget.value.classifier == classifier, s"snxTarget ${snxTarget.value.classifier} != $classifier")

  val expected = flagFor.getOrElse((os, env), sys.error(s"unhandled ground truth: os=$os env=$env"))
  val flags = (Compile / nativeLink / nativeConfig).value.linkingOptions
  assert(flags.contains(expected), s"expected $expected for $os/$env, got: $flags")
  flagFor.values.filterNot(_ == expected).foreach { other =>
    assert(!flags.contains(other), s"platform flag $other leaked for $os/$env: $flags")
  }
  streams.value.log.info(s"snx platform/detect: $classifier env='$env' resolved correctly")
}
