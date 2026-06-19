// Capstone integration: a multi-module Scala Native project exercising every major feature end to end. `core` is a
// NIR library whose bundled C (in its platform resource directory) calls a system library - zlib - that Scala Native
// does NOT auto-link; `core` declares that requirement once via SNX.usage. `engine` is a native library and `app` an
// executable, both consuming `core`. Neither `engine` nor `app` declares zlib - `-lz` arrives at their links purely
// through `core`'s propagated descriptor. `app` additionally builds a C library FROM SOURCE with CMake (SNX.vendored)
// and links it. The application links and RUNS, printing the zlib version and the vendored answer. Linux-only (the C
// + zlib path; zlib is present and linkable on the el10 and alpine cells).
scalaVersion := "3.8.4"
organization := "snx.test"
version := "0.1.0"

val core = project
  .enablePlugins(SNXPlugin)
  .settings(SNX.usage := { case Linux(_, _) => Usage.libraries("z") })

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
    SNX.vendored += Vendored
      .local("vendor/answer")
      .cmake("answer")
      .options { case Linux(_, _) => _.compileOptions("-DSNX_VENDORED").cOptions("-DSNX_VENDORED") }
  )
