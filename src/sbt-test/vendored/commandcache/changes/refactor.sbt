enablePlugins(SNXPlugin)

scalaVersion := sys.props("scala.version")

// The same recipe as before, its code restructured: what it returns is equal, so its key is.
def compileStep(ctx: BuildContext, base: File, build: File, obj: File): Step = {
  val headers: Seq[String | File | Argument | Matching] =
    Seq("-include", base / "config" / "extra.h", arg"-include${base / "config" / "note.h"}")
  Step.run(ctx.clang, (Seq[String | File | Argument | Matching]("-DSNX_VARIANT=2", Warnings.flag) ++ headers ++
    Seq[String | File | Argument | Matching]("-c", build / "answer.c", "-o", obj))*)
}

SNX.libraries += {
  val base = baseDirectory.value
  NativeLibrary(
    "answer",
    Vendored.local("vendor/answer").command { ctx =>
      val name = "answer"
      val build = ctx.staging / "build"
      val obj = ctx.staging / s"$name.o"
      val copies = Seq(Step.copy(ctx.source, build), Step.copy(base / "overlay", build))
      val library = Toolchain.archive(ctx, name)
      Recipe((copies :+ compileStep(ctx, base, build, obj) :+ Toolchain.archived(ctx, Toolchain.archiver(ctx), library, obj))*)
        .libraries(library)
    }
  )
}
