enablePlugins(SNXPlugin)

scalaVersion := sys.props("scala.version")

// A recipe passing every header of the project to a step by a Matching, after copying its source into the staging
// with a header that fails any compile reaching it: the Matching leaves sbt's target out, at key and at run time, so
// the staged header is never passed and never keyed, and an unchanged build hits however much the staging holds.
SNX.libraries += {
  val base = baseDirectory.value
  NativeLibrary(
    "answer",
    Vendored.local("vendor/answer").command { ctx =>
      val copied = ctx.staging / "src"
      val obj = ctx.staging / "answer.o"
      val library = Toolchain.archive(ctx, "answer")
      Recipe(
        Step.copy(ctx.source, copied),
        Step.copy(base / "poison.txt", copied / "poison.h"),
        Step.run(ctx.clang, "-DSNX_VARIANT=3", "-c", copied / "answer.c", "-o", obj),
        Toolchain.archived(ctx, Toolchain.archiver(ctx), library, obj),
        Step.run(ctx.clang, "-fsyntax-only", Matching(base, "**/*.h"))
      ).libraries(library)
    }
  )
}
