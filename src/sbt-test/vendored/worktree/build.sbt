enablePlugins(SNXPlugin)

scalaVersion := sys.props("scala.version")

SNX.deliverable := Executable

// A vendored Git source built from a recipe that copies the staged worktree - a walk of every file in it - and
// compiles the copy. A repository staged inside the worktree is rewritten by git's detached automatic maintenance
// after the fetch returns, so a walk of it races files enumerated and then deleted; the copy is that walk, kept because
// it is the operation that fails in the field, and checkWorktree asserts the worktree holds no repository. The
// repository is made offline by setupRepo; its file URI comes from Path.toUri, well-formed on every OS.
SNX.libraries += {
  val repository = (target.value / "answer-repo").toPath.toUri.toString
  NativeLibrary(
    "answer",
    Vendored.git(repository, "v1").command { ctx =>
      val copied = ctx.staging / "src"
      val obj = ctx.staging / "answer.o"
      val library = Toolchain.archive(ctx, "answer")
      Recipe(
        Step.copy(ctx.source, copied),
        Step.run(ctx.clang, "-c", copied / "answer.c", "-I", copied / "include", "-o", obj),
        Toolchain.archived(ctx, Toolchain.archiver(ctx), library, obj)
      ).libraries(library).includes(copied / "include")
    }
  )
}

// The clone a cold build fetched: its worktree holds the sources at the tag and no repository.
val checkWorktree = taskKey[Unit]("assert the staged worktree holds the sources and no repository")
checkWorktree := Def.uncached {
  val worktrees = (target.value / "snx" / "vendored" / "main" / "clones" * "*" / "source").get()
  assert(worktrees.nonEmpty, "the cold build staged no worktree")
  worktrees.foreach { worktree =>
    assert((worktree / "answer.c").isFile, s"$worktree does not hold the sources")
    assert(!(worktree / ".git").exists, s"Origin.Git staged a repository inside the worktree $worktree")
  }
  streams.value.log.info(s"snx vendored/worktree: ${worktrees.mkString} holds the sources and no repository")
}

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
