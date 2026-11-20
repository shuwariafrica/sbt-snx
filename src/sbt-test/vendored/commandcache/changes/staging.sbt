enablePlugins(SNXPlugin)

scalaVersion := sys.props("scala.version")

// A recipe that lists its staging directory. It is keyed against an empty staging and runs against a cleared one, so
// a warm build hits whatever the last build left there.
SNX.libraries += NativeLibrary(
  "answer",
  Vendored.local("vendor/answer").command { ctx =>
    val obj = ctx.staging / "answer.o"
    val library = Toolchain.archive(ctx, "answer")
    val listed = Option(ctx.staging.listFiles).toSeq.flatten.map(file => s"-DSNX_LISTED_${file.getName.replaceAll("[^A-Za-z0-9_]", "_")}")
    Recipe(
      Step.run(ctx.clang, (Seq[String | File | Argument | Matching]("-DSNX_VARIANT=4", "-c", ctx.source / "answer.c", "-o", obj) ++ listed)*),
      Toolchain.archived(ctx, Toolchain.archiver(ctx), library, obj)
    ).libraries(library)
  }
)
