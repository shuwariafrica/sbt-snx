enablePlugins(SNXPlugin)

scalaVersion := sys.props("scala.version")

SNX.deliverable := Executable

// Libraries whose sources slug alike or onto the plugin's own directories: a Git origin (whose clone the plugin
// keeps), a local source named `clones`, and local sources `a-b` and `a/b`. Built in this order, each must keep its own
// archive and leave the others', and the Git clone, in place.
SNX.libraries ++= Seq(
  NativeLibrary("answer", Vendored.git((target.value / "answer-repo").toPath.toUri.toString, "v1").cmake("answer")),
  NativeLibrary("clones", Vendored.local("clones").cmake("clones")),
  NativeLibrary("dash", Vendored.local("a-b").cmake("dash")),
  NativeLibrary("nested", Vendored.local("a/b").cmake("nested"))
)

// A local repository whose tag v1 returns 42; offline, and with signing forced off.
val setupRepo = taskKey[Unit]("create a local git repo whose tag v1 returns 42")
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
}

// A complete Git clone is one holding its completion marker - what the plugin decides by.
val expectClone = taskKey[Unit]("assert the Git clone the build fetched is still complete")
expectClone := Def.uncached {
  val clones = target.value / "snx" / "vendored" / "main" / "clones"
  assert((clones * "*" / ".snx-complete").get().nonEmpty, s"expected a complete Git clone under $clones: the local source `clones` removed it")
}

// Each library links from a staging of its own, `<origin slug>-<16 hex digits of its build's key>`: `a-b` and `a/b`
// both slug to `a-b`, and stage apart by their keys.
val expectStagings = taskKey[Unit]("assert each library links from a staging of its own")
expectStagings := Def.uncached {
  val config = (Compile / SNX.config).value.toString.replace('\\', '/')
  val stagings = "/snx/vendored/main/([A-Za-z0-9-]+-[0-9a-f]{16})/".r.findAllMatchIn(config).map(_.group(1)).toSet
  val origins = stagings.toSeq.map(_.dropRight(17).replaceAll("^git-[0-9a-f]{8}$", "git")).sorted
  assert(origins == Seq("a-b", "a-b", "clones", "git"), s"the libraries link from the stagings $stagings")
}
