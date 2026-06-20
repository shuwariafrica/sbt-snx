enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

SNX.deliverable := Executable

// A vendored CMake library under vendor/answer, built to a static library and folded into the native link as a
// NativeLibrary. Its CMakeLists.txt declares a plain (non-STATIC) library, so a static archive is produced only by the
// plugin's -DBUILD_SHARED_LIBS=OFF default. The per-platform configure flags pass -DSNX_VIA_FLAG=ON (answer.c #errors
// without it, proving the flags PF reaches cmake configure); the per-platform options carry the library's link closure -
// here the define glue.c requires (Flags.defines -> -D into the consuming C compile). Both are keyed on every runtime so
// the fixture is host-agnostic.
SNX.libraries += NativeLibrary(
  "answer",
  Vendored
    .local("vendor/answer")
    .cmake(Seq("answer"), { case _ => Seq("-DSNX_VIA_FLAG=ON") })
    .options { case _ => Flags.defines("SNX_VENDORED") })
