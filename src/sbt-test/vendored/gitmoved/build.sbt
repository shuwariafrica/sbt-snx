enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

SNX.deliverable := Executable

// A vendored Git source pinned to a MOVING branch `dev`, cloned from a local repo created offline by setupDev (no
// network); the moved-branch rebuild behaviour it exercises is described in `test`. The file URI is built via
// Path.toUri so it is well-formed on every OS, keeping the fixture host-agnostic.
SNX.libraries += NativeLibrary("answer", Vendored.git((target.value / "answer-repo").toPath.toUri.toString, "dev").cmake("answer"))

def runGit(repo: File)(args: String*): Unit = {
  val signOff = Seq("-c", "user.email=snx@example.com", "-c", "user.name=snx", "-c", "commit.gpgSign=false")
  val rc = scala.sys.process.Process("git" +: (signOff ++ args), repo).!
  if (rc != 0) sys.error(s"git ${args.mkString(" ")} failed in $repo")
}

// Create a local git repo whose branch `dev` returns 1.
val setupDev = taskKey[Unit]("create a local git repo where branch dev returns 1")
setupDev := {
  val repo = target.value / "answer-repo"
  IO.delete(repo)
  IO.copyDirectory(baseDirectory.value / "answer-src", repo)
  runGit(repo)("init", "-q")
  runGit(repo)("add", ".")
  runGit(repo)("commit", "-q", "-m", "answer 1")
  runGit(repo)("branch", "-M", "dev")
  streams.value.log.info(s"snx vendored/gitmoved: created repo at $repo (branch dev -> 1)")
}

// Advance branch `dev` to a new commit returning 2. A rebuild must pick this up (a stale-frozen key would still return
// 1), proving the cache keys on the resolved commit, not the branch name.
val moveDev = taskKey[Unit]("advance branch dev to a new commit returning 2")
moveDev := {
  val repo = target.value / "answer-repo"
  IO.write(repo / "answer.c", "#include \"answer.h\"\n\nint snx_answer(void) { return 2; }\n")
  runGit(repo)("commit", "-aqm", "answer 2")
  streams.value.log.info(s"snx vendored/gitmoved: moved branch dev -> 2")
}
