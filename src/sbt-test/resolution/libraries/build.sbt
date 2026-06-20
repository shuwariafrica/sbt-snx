// `SNX.libraries += NativeLibrary(...)` must compile at a real autoImport site: sbt `+=` infers its element type
// bottom-up, so a lift that type-checks in isolation can still fail here. The custom Append.Value/Values + the Seq
// Conversion are therefore proven, not asserted. Each form below is a distinct inference path: the bare `+=`, a `+=`
// of a builder result, a framework constructor, a `++=` of a Seq, the `:= { case ... }` partial function, and the
// `:= Seq(...)` lifting Conversion.
scalaVersion := "3.8.4"

enablePlugins(SNXPlugin)

SNX.libraries += NativeLibrary("snx_lib_plain")
SNX.libraries += NativeLibrary("snx_lib_whole").wholeArchive
SNX.libraries += NativeLibrary.framework("snx_lib_framework")
SNX.libraries ++= Seq(NativeLibrary("snx_lib_seq1"), NativeLibrary("snx_lib_seq2"))

val checkLift = taskKey[Unit]("the += lift compiles at the real site and accumulates onto every platform")
checkLift := Def.uncached {
  val runtime = NativeRuntime.Linux(Arch.X86_64, ABI.Glibc)
  val libraries = SNX.libraries.value.applyOrElse(runtime, (_: NativeRuntime) => Seq.empty[NativeLibrary])
  val names = libraries.map(_.name).toSet
  assert(
    names == Set("snx_lib_plain", "snx_lib_whole", "snx_lib_framework", "snx_lib_seq1", "snx_lib_seq2"),
    s"the += / ++= lifts did not accumulate every library onto the platform: $names")
  assert(libraries.find(_.name == "snx_lib_whole").exists(_.mode == LinkMode.WholeArchive), "whole-archive mode lost through the lift")
  assert(libraries.find(_.name == "snx_lib_framework").exists(_.mode == LinkMode.Framework), "framework mode lost through the lift")
  streams.value.log.info("snx resolution/libraries: SNX.libraries += NativeLibrary lift compiles and accumulates")
}

// The conditional partial-function form and the unconditional Seq lift compile too (proven by this project loading).
val perPlatform: SettingKey[PartialFunction[NativeRuntime, Seq[NativeLibrary]]] =
  settingKey[PartialFunction[NativeRuntime, Seq[NativeLibrary]]]("the := { case ... } and := Seq(...) forms compile")
perPlatform := { case NativeRuntime.Linux(_, _) => Seq(NativeLibrary("snx_lib_linux")) }
val unconditional: SettingKey[PartialFunction[NativeRuntime, Seq[NativeLibrary]]] =
  settingKey[PartialFunction[NativeRuntime, Seq[NativeLibrary]]]("the Seq lifting Conversion compiles")
unconditional := Seq(NativeLibrary("snx_lib_all"))
