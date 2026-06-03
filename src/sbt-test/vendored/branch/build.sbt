enablePlugins(SNXPlugin)

scalaVersion := "3.8.3"

SNX.target := TargetPlatform(OS.Linux, Arch.X86_64)

// The Git source pins a BRANCH ('dev'), which the plugin must reject - a moving ref would freeze in the cache.
SNX.vendored += NativeSource.Git(
  "answer",
  s"file://${(target.value / "answer-repo").getAbsolutePath}",
  "dev",
  NativeBackend.CMake(Seq("answer"))
)

// Build a throwaway git repo with a 'dev' branch for the Git source to resolve against.
val setupRepo = taskKey[Unit]("create a local git repo of the answer sources with a dev branch")
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
  git("-c", "user.email=snx@example.com", "-c", "user.name=snx", "-c", "commit.gpgSign=false", "commit", "-q", "-m", "answer")
  git("branch", "dev")
  streams.value.log.info(s"snx vendored/branch: created repo at $repo with a dev branch")
}
