import scala.sys.process.Process

enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

SNX.deliverable := Executable

// A vendored library built by the Command escape hatch: the function drives clang and the archiver itself (no CMake),
// so it works on every toolchain, MinGW included. `token` keys the build cache. It compiles vendor/answer/answer.c
// into libanswer.a and exposes vendor/answer/include; the resulting Artefacts fold into the native link exactly as the
// CMake backend's do. It uses ctx.clang - the compiler the Scala Native link uses, honouring an SNX.clang override - so
// the command build is toolchain-consistent with the link; the archiver resolution mirrors link/wholearchive.
SNX.libraries += NativeLibrary("answer", Vendored.local("vendor/answer").command("answer-1") { ctx =>
  val clang = ctx.clang
  val clangDir = clang.getParentFile
  val exe = if (clang.getName.endsWith(".exe")) ".exe" else ""
  val obj = ctx.staging / "answer.o"
  val lib = ctx.staging / "libanswer.a"
  // The vendored fold caches the returned paths, so every output (the archive and the headers) must live under the
  // build context's staging directory; copy the headers there rather than exposing the source tree directly.
  val include = ctx.staging / "include"
  IO.createDirectory(ctx.staging)
  IO.copyDirectory(ctx.source / "include", include)
  val compiled =
    Process(
      Seq(clang.getAbsolutePath, "-c", (ctx.source / "answer.c").getAbsolutePath, "-I", include.getAbsolutePath, "-o", obj.getAbsolutePath)).!
  assert(compiled == 0, s"answer.c failed to compile (exit $compiled)")
  val msvc = ctx.runtime match
    case NativeRuntime.Windows(_, ABI.Msvc) => true
    case _                                  => false
  // The archiver is not always beside clang (alpine has /usr/bin/ar but no llvm-ar): try beside clang, then PATH.
  val names = (if (msvc) Seq("llvm-lib", "lib") else Seq("llvm-ar", "ar")).map(_ + exe)
  def onPath(name: String): Seq[File] =
    sys.env.getOrElse("PATH", "").split(java.io.File.pathSeparator).toSeq.map(dir => new File(dir, name))
  val archiver =
    (names.map(name => new File(clangDir, name)) ++ names.flatMap(onPath)).find(_.canExecute).getOrElse(sys.error("no archiver found"))
  val archive =
    if (msvc) Seq(archiver.getAbsolutePath, "-out:" + lib.getAbsolutePath, obj.getAbsolutePath)
    else Seq(archiver.getAbsolutePath, "rcs", lib.getAbsolutePath, obj.getAbsolutePath)
  assert(Process(archive).! == 0, s"libanswer.a failed to build using ${archiver.getName}")
  ctx.log.info(s"snx vendored/command: built ${lib.getName} via ${archiver.getName} for ${ctx.runtime}")
  Artefacts(Seq(lib), Seq(include))
})
