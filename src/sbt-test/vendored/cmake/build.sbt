enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

SNX.deliverable := Executable

// A local CMake project under vendor/answer, built to a static library and folded into the native link. Its
// CMakeLists.txt declares a plain (non-STATIC) library, so a static archive is produced only by the plugin's
// -DBUILD_SHARED_LIBS=OFF default. The per-platform configure flags pass -DSNX_VIA_FLAG=ON (answer.c #errors
// without it, proving the flags PF reaches cmake configure); the per-platform options carry the C defines glue.c
// requires. Both are keyed on every runtime so the fixture is host-agnostic.
SNX.vendored += Vendored
  .local("vendor/answer")
  .cmake(Seq("answer"), { case _ => Seq("-DSNX_VIA_FLAG=ON") })
  .options { case _ => _.compileOptions("-DSNX_VENDORED_COMPILE").cOptions("-DSNX_VENDORED_C") }
