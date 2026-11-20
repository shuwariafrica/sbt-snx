package snx.sbt

import java.io.File

import scala.scalanative.build.Mode

// What keying a recipe costs per build where it names a host tool: the archiver run as a step's program, kept by its
// stamp once hashed; the same file named in a step's text, hashed on every build; and the compiler's directory passed
// whole as a File, every file in it hashed on every build. Printed, not asserted: on Windows LLVM's tools are large
// static binaries, and these lines are that cell's figures.
object KeyTiming {
  def report(staging: File, runtime: snx.NativeRuntime, clang: File, archiver: File, build: Seq[File], targets: Seq[File], log: sbt.util.Logger): Unit = {
    val context = BuildContext(new File(staging, "source"), staging, runtime, Linkage.Static, Mode.releaseFast, clang, clang, Argument.jobs)
    val vendoring = Backend.Vendoring("timing", build, targets, staging)
    def mean(recipe: Recipe, runs: Int): Double = {
      val _ = Backend.recipeKey(recipe, context, vendoring)
      val start = System.nanoTime()
      (1 to runs).foreach(_ => Backend.recipeKey(recipe, context, vendoring))
      (System.nanoTime() - start) / 1e6 / runs
    }
    val size = archiver.length / 1e6
    log.info(f"probe: the archiver ($size%.1f MB) as a step's program keys in ${mean(Recipe(Step.run(archiver, "x")), 20)}%.3f ms per build, kept")
    log.info(f"probe: the archiver named in text (AR=...) keys in ${mean(Recipe(Step.run("make", s"AR=${archiver.getAbsolutePath}")), 5)}%.1f ms per build, hashed")
    val tree = clang.getParentFile
    val files = Option(tree.listFiles).toSeq.flatten.filter(_.isFile)
    // A host whose compiler sits among files no user may read (Linux's /usr/bin holds setuid chfn) cannot key that
    // directory, which the plugin refuses with InputUnreadable; the figure is for hosts that can.
    try log.info(f"probe: the compiler's directory as a File (${files.size} files, ${files.map(_.length).sum / 1e6}%.0f MB) keys in ${mean(Recipe(Step.run("make", tree)), 3)}%.1f ms per build")
    catch {
      case unreadable: snx.SNXError.InputUnreadable =>
        log.info(s"probe: the compiler's directory as a File is not measured on this host: ${unreadable.getMessage}")
    }
  }
}
