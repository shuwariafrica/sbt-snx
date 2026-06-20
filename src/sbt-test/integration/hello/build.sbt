// Capstone integration: a multi-module Scala Native project exercising the native-library surface end to end. `core` is
// a NIR library whose bundled C calls a SYSTEM library (zlib, which Scala Native does not auto-link) and depends on a
// preprocessor define; it declares the library requirement ONCE via SNX.libraries and the define via SNX.flags. `engine`
// (a native library) and `app` (an executable) consume `core`; neither restates them - `-lz` and `-DSNX_CORE` arrive at
// their links purely through `core`'s propagated descriptor. `app` additionally provisions a native library FROM SOURCE
// with CMake (a vendored NativeLibrary), whose local Flags closure supplies the compile define its glue needs. The
// application links and RUNS, printing the zlib version and the vendored answer. Linux-only (the C + zlib path; zlib is
// present on the el10 and alpine cells). The cross-project rebind - a downstream re-provisioning a transitively-required
// library - is proven separately in resolution/rebind.
scalaVersion := "3.8.4"
organization := "snx.test"
version := "0.1.0"

val core = project
  .enablePlugins(SNXPlugin)
  .settings(
    SNX.libraries := { case Linux(_, _) => Seq(NativeLibrary("z")) },
    SNX.flags := { case Linux(_, _) => Flags.defines("SNX_CORE") }
  )

val engine = project
  .enablePlugins(SNXPlugin)
  .dependsOn(core)
  .settings(SNX.deliverable := Library)

val app = project
  .enablePlugins(SNXPlugin)
  .dependsOn(core, engine)
  .settings(
    SNX.deliverable := Executable,
    SNX.modifiers += Modifier.platform { case Linux(_, _) => _.optimize(false) },
    SNX.libraries += NativeLibrary(
      "answer",
      Vendored.local("vendor/answer").cmake("answer").options { case Linux(_, _) => Flags.defines("SNX_VENDORED") })
  )
