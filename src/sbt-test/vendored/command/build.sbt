enablePlugins(SNXPlugin)

scalaVersion := sys.props("scala.version")

SNX.deliverable := Executable

// A vendored library built from a recipe: the plugin runs clang and an archiver, no CMake, so it builds on every
// toolchain, MinGW included. It compiles vendor/answer into two archives - libanswer, whose snx_answer calls
// snx_answer_base in libbase - and names libbase first, an order GNU ld rejects unless the plugin groups the archives,
// so the link proves the group on Linux and MinGW and the ungrouped archives on macOS and MSVC. The archiver is named
// by its bare name and found on the PATH the step sets, clang's directory first, so every cell resolves a program by
// name. On Windows the headers reach the staging through a .cmd the recipe copies there and runs by its bare name,
// found through PATHEXT; elsewhere through a copy.
SNX.libraries += {
  val headersCmd = baseDirectory.value / "tools" / "headers.cmd"
  NativeLibrary(
    "answer",
    Vendored.local("vendor/answer").command { ctx =>
      val include = ctx.staging / "include"
      val archiver = Toolchain.archiver(ctx)
      val name = archiver.getName.stripSuffix(".exe")
      val path = (ctx.clang.getParentFile.getAbsolutePath +: Toolchain.path).mkString(java.io.File.pathSeparator)
      def compiled(source: String): (Step, File) = {
        val obj = ctx.staging / s"$source.o"
        (Step.run(ctx.clang, "-c", ctx.source / s"$source.c", "-I", include, "-o", obj), obj)
      }
      val headers =
        if (Toolchain.windows(ctx))
          Seq(
            Step.copy(headersCmd, ctx.staging / "bin" / "headers.cmd"),
            Step
              .run("headers", ctx.source / "include" / "answer.h", include)
              .env("PATH" -> ((ctx.staging / "bin").getAbsolutePath +: Toolchain.path).mkString(java.io.File.pathSeparator)))
        else Seq(Step.copy(ctx.source / "include", include))
      val libraries = Seq("base", "answer").map { source =>
        val (compile, obj) = compiled(source)
        val library = Toolchain.archive(ctx, source)
        (Seq(compile, Toolchain.archived(ctx, name, library, obj).env("PATH" -> path)), library)
      }
      Recipe((headers ++ libraries.flatMap(_._1))*).libraries(libraries.map(_._2)*).includes(include)
    }
  )
}

// On Windows the cold build ran the copied .cmd; elsewhere there is none to run.
val cmdStep = taskKey[Unit]("assert the .cmd step ran on Windows")
cmdStep := Def.uncached {
  val log = streams.value.log
  if (scala.util.Properties.isWin) {
    val ran = (target.value / "snx" / "vendored" / "main" * "*" / "bin" / "headers.cmd").get()
    assert(ran.nonEmpty, "the recipe's .cmd was not copied into the staging")
    log.info(s"snx vendored/command: the .cmd step ran from ${ran.mkString}")
  } else log.info("snx vendored/command: the .cmd step is Windows-only; skipped on this host")
}
