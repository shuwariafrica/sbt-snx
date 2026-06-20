import scala.scalanative.build.Discover
import scala.sys.process.Process

// Whole-archive de-duplication. Two NIR libraries (a, b) each export the SAME whole-archive requirement. The consumer
// resolves both and folds their descriptors; the combined whole-archive is de-duplicated, so the archive is
// force-loaded ONCE and the link succeeds. Were it not de-duplicated the archive's objects would be force-loaded twice
// and the link would fail with a duplicate-symbol error, so a successful link is the proof. libdup.a carries a
// non-static global (snx_dup), the symbol that collides on a double load. The name-form whole-archive exists only on
// GNU ld and MSVC (macOS has none), so the fixture is Linux-gated by the build's scripted/excludeFilter.
scalaVersion := "3.8.4"

val archive = settingKey[File]("the static archive both libraries' descriptors force-load")
val buildDupArchive = taskKey[Unit]("compile dup.c into libdup.a, exporting a non-static global, for the resolved platform")

val a = project.enablePlugins(SNXPlugin).settings(SNX.libraries := { case _ => Seq(NativeLibrary("dup").wholeArchive) })
val b = project.enablePlugins(SNXPlugin).settings(SNX.libraries := { case _ => Seq(NativeLibrary("dup").wholeArchive) })

val consumer = project
  .enablePlugins(SNXPlugin)
  .dependsOn(a, b)
  .settings(
    SNX.deliverable := Executable,
    archive := target.value / "dedup" / "libdup.a",
    SNX.libDirs := Seq(archive.value.getParentFile),
    buildDupArchive := Def.uncached {
      val clang = Discover.clang()
      val clangDir = clang.getParent.toFile
      val source = baseDirectory.value / "src" / "main" / "c" / "dup.c"
      val out = archive.value
      IO.createDirectory(out.getParentFile)
      val obj = out.getParentFile / "dup.o"
      val compiled = Process(Seq(clang.toString, "-c", source.getAbsolutePath, "-o", obj.getAbsolutePath)).!
      assert(compiled == 0, s"dup.c failed to compile (exit $compiled)")
      // The archiver is not always beside clang (alpine has /usr/bin/ar but no llvm-ar): try beside clang, then PATH.
      val names = Seq("llvm-ar", "ar")
      def onPath(name: String): Seq[File] =
        sys.env.getOrElse("PATH", "").split(java.io.File.pathSeparator).toSeq.map(dir => new java.io.File(dir, name))
      val candidates = names.map(name => new java.io.File(clangDir, name)) ++ names.flatMap(onPath)
      val archiver = candidates.find(_.canExecute).getOrElse(sys.error(s"no archiver found (${names.mkString(", ")})"))
      val archived = Process(Seq(archiver.getAbsolutePath, "rcs", out.getAbsolutePath, obj.getAbsolutePath)).!
      assert(archived == 0, s"libdup.a failed to build (exit $archived) using ${archiver.getName}")
      streams.value.log.info(s"snx resolution/dedup: built ${out.getName} via ${archiver.getName}")
    }
  )
