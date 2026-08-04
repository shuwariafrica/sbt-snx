import scala.sys.process.Process

enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

SNX.deliverable := Executable

// A vendored Git source built by the Command escape hatch, which is the only backend that sees the staged source
// directory, so it is the only place the staging contract can be asserted from a build. The backend refuses to build
// unless the directory it is handed is a plain worktree: a repository staged inside it is rewritten by git's detached
// automatic maintenance after the fetch returns, so any backend walking the source races files that are enumerated and
// then deleted. The walk below is that same enumerate-then-open, kept because it is the operation that fails in the
// field. The repository is cloned offline by setupRepo, and the file URI is built via Path.toUri so it is well-formed
// on every OS (file:///... including a Windows drive letter).
SNX.libraries += NativeLibrary(
  "answer",
  Vendored.git((target.value / "answer-repo").toPath.toUri.toString, "v1").command("worktree-1") { ctx =>
    val stray = ctx.source.listFiles.toSeq.filter(_.getName == ".git")
    assert(stray.isEmpty, s"Origin.Git staged a repository inside the worktree: ${stray.map(_.getAbsolutePath).mkString(", ")}")
    val walked = (ctx.source ** "*").get().filter(_.isFile)
    val vanished = walked.filterNot(_.exists)
    assert(vanished.isEmpty, s"staged sources vanished mid-walk: ${vanished.map(_.getAbsolutePath).mkString(", ")}")

    val clang = ctx.clang
    val clangDir = clang.getParentFile
    val exe = if (clang.getName.endsWith(".exe")) ".exe" else ""
    val obj = ctx.staging / "answer.o"
    val lib = ctx.staging / "libanswer.a"
    val include = ctx.staging / "include"
    IO.createDirectory(ctx.staging)
    IO.copyDirectory(ctx.source / "include", include)
    val compiled =
      Process(
        Seq(clang.getAbsolutePath, "-c", (ctx.source / "answer.c").getAbsolutePath, "-I", include.getAbsolutePath, "-o", obj.getAbsolutePath)).!
    assert(compiled == 0, s"answer.c failed to compile (exit $compiled)")
    val msvc = ctx.runtime match
      case NativeRuntime.Windows(_, ABI.Msvc) => true
      case _                                  => false
    // The archiver is not always beside clang (alpine has /usr/bin/ar but no llvm-ar): try beside clang, then PATH.
    val names = (if (msvc) Seq("llvm-lib", "lib") else Seq("llvm-ar", "ar")).map(_ + exe)
    def onPath(name: String): Seq[File] =
      sys.env.getOrElse("PATH", "").split(java.io.File.pathSeparator).toSeq.map(dir => new File(dir, name))
    val archiver =
      (names.map(name => new File(clangDir, name)) ++ names.flatMap(onPath)).find(_.canExecute).getOrElse(sys.error("no archiver found"))
    val archive =
      if (msvc) Seq(archiver.getAbsolutePath, "-out:" + lib.getAbsolutePath, obj.getAbsolutePath)
      else Seq(archiver.getAbsolutePath, "rcs", lib.getAbsolutePath, obj.getAbsolutePath)
    assert(Process(archive).! == 0, s"libanswer.a failed to build using ${archiver.getName}")
    Artefacts(Seq(lib), Seq(include))
  }
)

// Build a throwaway git repo offline (no network) whose tag v1 carries the answer sources. Signing is forced off so a
// global signing config needs no key.
val setupRepo = taskKey[Unit]("create a local git repo holding the answer sources at tag v1")
setupRepo := {
  val repo = target.value / "answer-repo"
  IO.delete(repo)
  IO.copyDirectory(baseDirectory.value / "answer-src", repo)
  def git(args: String*): Unit = {
    val rc = scala.sys.process.Process("git" +: args, repo).!
    if (rc != 0) sys.error(s"git ${args.mkString(" ")} failed in $repo")
  }
  git("init", "-q")
  git("add", ".")
  git("-c", "user.email=snx@example.com", "-c", "user.name=snx", "-c", "commit.gpgSign=false", "commit", "-q", "-m", "answer 42")
  git("-c", "tag.gpgSign=false", "-c", "tag.forceSignAnnotated=false", "tag", "v1")
  streams.value.log.info(s"snx vendored/worktree: created repo at $repo (tag v1)")
}
