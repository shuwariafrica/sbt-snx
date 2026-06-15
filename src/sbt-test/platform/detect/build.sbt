enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

// The check asserts host OS/arch detection and toolchain ABI resolution against the ground truth
// (snx.expect.{classifier,env}) the build forwards from the CI matrix.
val check = taskKey[Unit]("assert plugin platform detection matches the injected ground truth")
check := Def.uncached {
  def prop(k: String): String = sys.props.getOrElse(s"snx.expect.$k", sys.error(s"snx.expect.$k not set"))
  val classifier = prop("classifier")
  val env = sys.props.getOrElse("snx.expect.env", "")

  assert(SNX.host.classifier == classifier, s"host detection ${SNX.host.classifier} != $classifier")
  assert(SNX.target.value.classifier == classifier, s"SNX.target ${SNX.target.value.classifier} != $classifier")

  val resolved = SNX.runtime.value match
    case NativeRuntime.Linux(_, ABI.Glibc)   => "glibc"
    case NativeRuntime.Linux(_, ABI.Musl)    => "musl"
    case NativeRuntime.Darwin(_)             => ""
    case NativeRuntime.Windows(_, ABI.Msvc)  => "msvc"
    case NativeRuntime.Windows(_, ABI.MinGw) => "mingw"
  assert(resolved == env, s"resolved toolchain env '$resolved' != ground truth '$env'")
  streams.value.log.info(s"snx platform/detect: $classifier env='$env' resolved correctly")
}
