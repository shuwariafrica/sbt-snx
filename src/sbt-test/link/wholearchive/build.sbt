import scala.scalanative.build.Discover
import scala.sys.process.Process

enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

SNX.deliverable := Executable

// The static archive force-linked below; built per-platform by `buildArchive` before the link.
val archive = settingKey[File]("the static archive whose whole content is force-linked")
archive := target.value / "wholearchive" / "libextra.a"

SNX.modifiers += Modifier.wholeArchive(archive.value)

val ranFile = settingKey[File]("the sentinel the force-linked constructor writes at startup")
ranFile := target.value / "whole-archive-ran"

Compile / run / envVars += ("SNX_WHOLEARCHIVE_SENTINEL" -> ranFile.value.getAbsolutePath)

val checkWholeArchive = taskKey[Unit]("assert the otherwise-unreferenced archive was force-linked")
checkWholeArchive := {
  assert(ranFile.value.exists, s"the force-linked archive's constructor did not run: ${ranFile.value}")
  streams.value.log.info("snx link/wholearchive: the otherwise-unreferenced archive was force-linked and its constructor ran")
}

val buildArchive = taskKey[Unit]("compile the unreferenced C source into a static archive for the resolved platform")
buildArchive := Def.uncached {
  val clang = Discover.clang()
  val clangDir = clang.getParent.toFile
  val exe = if (clang.getFileName.toString.endsWith(".exe")) ".exe" else ""
  val source = baseDirectory.value / "src" / "main" / "c" / "extra.c"
  val out = archive.value
  IO.createDirectory(out.getParentFile)
  val obj = out.getParentFile / "extra.o"
  val compiled = Process(Seq(clang.toString, "-c", source.getAbsolutePath, "-o", obj.getAbsolutePath)).!
  assert(compiled == 0, s"extra.c failed to compile (exit $compiled)")

  val msvc = SNX.runtime.value match
    case NativeRuntime.Windows(_, ABI.Msvc) => true
    case _                                  => false
  // The archiver is not always beside clang (alpine has /usr/bin/ar but no llvm-ar): try beside clang, then PATH.
  val names = (if msvc then Seq("llvm-lib", "lib") else Seq("llvm-ar", "ar")).map(_ + exe)
  def onPath(name: String): Seq[File] =
    sys.env.getOrElse("PATH", "").split(java.io.File.pathSeparator).toSeq.map(dir => new java.io.File(dir, name))
  val candidates = names.map(name => new java.io.File(clangDir, name)) ++ names.flatMap(onPath)
  val archiver = candidates.find(_.canExecute).getOrElse(sys.error(s"no archiver found (${names.mkString(", ")})"))
  val command =
    if msvc then Seq(archiver.getAbsolutePath, "-out:" + out.getAbsolutePath, obj.getAbsolutePath)
    else Seq(archiver.getAbsolutePath, "rcs", out.getAbsolutePath, obj.getAbsolutePath)
  val archived = Process(command).!
  assert(archived == 0, s"the static archive failed to build (exit $archived) using ${archiver.getName}")
  streams.value.log.info(s"snx link/wholearchive: built ${out.getName} via ${archiver.getName} for ${SNX.runtime.value}")
}
