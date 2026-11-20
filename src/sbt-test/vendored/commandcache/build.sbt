enablePlugins(SNXPlugin)

scalaVersion := sys.props("scala.version")

// One recipe whose inputs each reach the build by their own route, each one the test edits: the variant in the
// recipe's text, a warning flag from a helper in project/Warnings.scala, a header passed as a File, one inside an
// arg"..." part, and an overlay copied onto the copied source.
SNX.libraries += {
  val base = baseDirectory.value
  NativeLibrary(
    "answer",
    Vendored.local("vendor/answer").command { ctx =>
      val build = ctx.staging / "build"
      val obj = ctx.staging / "answer.o"
      val library = Toolchain.archive(ctx, "answer")
      Recipe(
        Step.copy(ctx.source, build),
        Step.copy(base / "overlay", build),
        Step.run(ctx.clang, "-DSNX_VARIANT=1", Warnings.flag, "-include", base / "config" / "extra.h",
          arg"-include${base / "config" / "note.h"}", "-c", build / "answer.c", "-o", obj),
        Toolchain.archived(ctx, Toolchain.archiver(ctx), library, obj)
      ).libraries(library)
    }
  )
}
