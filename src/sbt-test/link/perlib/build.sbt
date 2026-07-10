import scala.scalanative.build.Discover
import scala.sys.process.Process

enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

SNX.deliverable := Executable

// The deliverable links dynamically (the default), so the C runtime (libc) stays dynamic; only the named library below
// is forced static - the maximal-static glibc build (own code + a static syslib + a dynamic libc).

// A static archive built in-fixture (it ships no shared variant), linked static via the per-library
// -Wl,-Bstatic -l<name> -Wl,-Bdynamic bracket. `-L` points the linker at the built archive.
val staticLibDir = settingKey[File]("directory holding the self-built libsnxperlib.a")
staticLibDir := target.value / "snx-perlib"

SNX.libDirs += staticLibDir.value
SNX.libraries += NativeLibrary("snxperlib").linkage(Static)

val buildArchive = taskKey[Unit]("compile answer.c into a static archive for the resolved platform")
buildArchive := Def.uncached {
  val clang = Discover.clang()
  val exe = if (clang.getFileName.toString.endsWith(".exe")) ".exe" else ""
  val clangDir = clang.getParent.toFile
  val source = baseDirectory.value / "src" / "main" / "c" / "answer.c"
  val dir = staticLibDir.value
  IO.createDirectory(dir)
  val obj = dir / "answer.o"
  val lib = dir / "libsnxperlib.a"
  val compiled = Process(Seq(clang.toString, "-c", source.getAbsolutePath, "-o", obj.getAbsolutePath)).!
  assert(compiled == 0, s"answer.c failed to compile (exit $compiled)")
  // The archiver is not always beside clang (alpine has /usr/bin/ar but no llvm-ar): try beside clang, then PATH.
  val names = Seq("llvm-ar", "ar").map(_ + exe)
  def onPath(name: String): Seq[File] =
    sys.env.getOrElse("PATH", "").split(java.io.File.pathSeparator).toSeq.map(d => new java.io.File(d, name))
  val candidates = names.map(name => new java.io.File(clangDir, name)) ++ names.flatMap(onPath)
  val archiver = candidates.find(_.canExecute).getOrElse(sys.error(s"no archiver found (${names.mkString(", ")})"))
  val archived = Process(Seq(archiver.getAbsolutePath, "rcs", lib.getAbsolutePath, obj.getAbsolutePath)).!
  assert(archived == 0, s"the static archive failed to build (exit $archived) using ${archiver.getName}")
  streams.value.log.info(s"snx link/perlib: built ${lib.getName} via ${archiver.getName} for ${SNX.runtime.value}")
}

val checkDynamicLibc = taskKey[Unit]("assert the binary linked the static archive but keeps libc dynamic")
checkDynamicLibc := {
  val binary = (Compile / SNX.link).value
  val ldd = Process(Seq("sh", "-c", s"ldd '${binary.getAbsolutePath}' 2>&1 || true")).!!.toLowerCase
  assert(
    !(ldd.contains("not a dynamic executable") || ldd.contains("statically linked")),
    s"the binary is fully static; the dynamic deliverable should keep libc dynamic:\n$ldd"
  )
  streams.value.log.info(s"snx link/perlib: static archive linked via the -Bstatic bracket, libc still dynamic:\n$ldd")
}
