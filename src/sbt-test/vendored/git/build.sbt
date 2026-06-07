enablePlugins(SNXPlugin)

scalaVersion := "3.8.3"

SNX.target := TargetPlatform(OS.Linux, Arch.X86_64)

// A vendored Git source cloned from a local repository (created by setupRepo) at the pinned tag v1.
SNX.vendored += NativeSource.Git(
  "answer",
  s"file://${(target.value / "answer-repo").getAbsolutePath}",
  "v1",
  NativeBackend.CMake(Seq("answer"))
)

// Build a throwaway git repository of the answer sources, tagged v1, for the Git source to clone.
val setupRepo = taskKey[Unit]("create a local git repo of the answer sources, tagged v1")
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
  // A plain lightweight tag, with signing forced off so a signing global config does not require a key or message.
  git("-c", "tag.gpgSign=false", "-c", "tag.forceSignAnnotated=false", "tag", "v1")
  streams.value.log.info(s"snx vendored/git: created repo at $repo tagged v1")
}

val check = taskKey[Unit]("verify the cloned-and-built archive and include dir reach nativeConfig")
check := {
  val cfg = (Compile / nativeLink / nativeConfig).value
  assert(cfg.linkingOptions.exists(_.endsWith("libanswer.a")), s"built archive not in linkingOptions: ${cfg.linkingOptions}")
  assert(
    cfg.compileOptions.exists(o => o.startsWith("-I") && o.endsWith("include")),
    s"vendored include dir not in compileOptions: ${cfg.compileOptions}")
  streams.value.log.info("snx vendored/git: cloned, built, archive and include dir reach nativeConfig")
}
