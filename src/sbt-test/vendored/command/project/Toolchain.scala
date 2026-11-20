import sbt.*

import snx.sbt.SNXImports.*

// The archive a runtime links and the archiver that makes it: llvm-lib or lib writing a .lib on MSVC, llvm-ar or ar
// writing a .a elsewhere, the archiver found beside the compiler first, then along the PATH (alpine has ar but no
// llvm-ar).
object Toolchain {
  def msvc(runtime: NativeRuntime): Boolean = runtime match {
    case NativeRuntime.Windows(_, ABI.Msvc) => true
    case _                                  => false
  }
  def msvc(context: BuildContext): Boolean = msvc(context.runtime)

  def windows(runtime: NativeRuntime): Boolean = runtime match {
    case NativeRuntime.Windows(_, _) => true
    case _                           => false
  }
  def windows(context: BuildContext): Boolean = windows(context.runtime)

  // The host's PATH, read through System.getenv, whose lookup ignores case on Windows as the variable's name does.
  def path: Seq[String] = Option(System.getenv("PATH")).toSeq.flatMap(_.split(java.io.File.pathSeparator)).filter(_.nonEmpty)

  def archive(context: BuildContext, name: String): File =
    context.staging / (if (msvc(context)) s"$name.lib" else s"lib$name.a")

  def archiver(context: BuildContext): File = archiver(context.runtime, context.clang)

  def archiver(runtime: NativeRuntime, clang: File): File = {
    val exe = if (windows(runtime)) ".exe" else ""
    val names = (if (msvc(runtime)) Seq("llvm-lib", "lib") else Seq("llvm-ar", "ar")).map(_ + exe)
    val beside = names.map(name => clang.getParentFile / name)
    val onPath = names.flatMap(name => path.map(dir => new File(dir, name)))
    (beside ++ onPath).find(file => file.isFile && file.canExecute).getOrElse(sys.error("no archiver found"))
  }

  // `program` archiving `objects` into `library`, in the archiver's own syntax.
  def archived(context: BuildContext, program: String | File, library: File, objects: File*): Step.Run =
    if (msvc(context)) Step.run(program, (arg"-out:$library" +: objects)*)
    else Step.run(program, (Seq[String | File]("rcs", library) ++ objects)*)
}
