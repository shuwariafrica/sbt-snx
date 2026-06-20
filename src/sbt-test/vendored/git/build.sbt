enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

SNX.deliverable := Executable

// A vendored Git source cloned from a local repository (created offline by setupRepo, no network) at the pinned tag
// v1, built with CMake and linked into the binary. The file URI is built via Path.toUri so it is well-formed on
// every OS (file:///... including a Windows drive letter), keeping the fixture host-agnostic.
SNX.libraries += NativeLibrary(
  "answer",
  Vendored.git((target.value / "answer-repo").toPath.toUri.toString, "v1").cmake("answer"))

// Build a throwaway git repo where tag v1 returns 42 but a LATER commit on the default branch returns 0. So a run
// printing 42 proves the plugin checked out the pinned ref v1, not HEAD - the behaviour that distinguishes
// git(uri, ref) from a plain clone. Offline (no network); signing forced off so a global signing config needs no key.
val setupRepo = taskKey[Unit]("create a local git repo where tag v1 returns 42 and a later HEAD returns 0")
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
  IO.write(repo / "answer.c", "#include \"answer.h\"\n\nint snx_answer(void) { return 0; }\n")
  git("-c", "user.email=snx@example.com", "-c", "user.name=snx", "-c", "commit.gpgSign=false", "commit", "-aqm", "answer 0")
  streams.value.log.info(s"snx vendored/git: created repo at $repo (tag v1 -> 42, HEAD -> 0)")
}
