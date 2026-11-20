import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

enablePlugins(SNXPlugin)

scalaVersion := sys.props("scala.version")

// One recipe per typed failure, chosen by probeCase; expectFailure asserts each on its cause.
val probeCase = settingKey[String]("the failure case the recipe takes")
probeCase := "none"

SNX.libraries ++= {
  val base = baseDirectory.value
  val outputs = target.value
  val repository = (base / "answer-repo").toPath.toUri.toString
  def local(recipe: BuildContext => Recipe) = NativeLibrary("answer", Vendored.local("vendor/answer").command(recipe))
  def git(recipe: BuildContext => Recipe) = NativeLibrary("answer", Vendored.git(repository, "v1").command(recipe))
  def compiled(ctx: BuildContext, sources: (String | File | Argument | Matching)*): Step =
    Step.run(ctx.clang, (Seq[String | File | Argument | Matching]("-c", "-o", ctx.staging / "answer.o") ++ sources)*)
  // The archive built, then a step that keys `named` and does nothing with it.
  def keying(named: String | File | Argument | Matching): BuildContext => Recipe = ctx => {
    val library = Toolchain.archive(ctx, "answer")
    Recipe(compiled(ctx, ctx.source / "answer.c"), Toolchain.archived(ctx, Toolchain.archiver(ctx), library, ctx.staging / "answer.o"),
      Step.run(ctx.clang, "--version", named)).libraries(library)
  }
  probeCase.value match {
    case "none"      => Seq.empty
    case "exit"      => Seq(local(ctx => Recipe(compiled(ctx, "--snx-no-such-flag", ctx.source / "answer.c"))))
    case "unwritten" => Seq(local(ctx => Recipe(compiled(ctx, ctx.source / "answer.c")).libraries(ctx.staging / "libnone.a")))
    case "program"   => Seq(local(ctx => Recipe(Step.run(base / "no-such-tool", "x"))))
    case "blocked"   =>
      Seq(local(ctx => Recipe(Step.run(ctx.clang, "-c", "-o", ctx.staging / "work", ctx.source / "answer.c"),
        Step.run(ctx.clang, "--version").in(ctx.staging / "work" / "sub"))))
    case "sjobs"     => Seq(local(ctx => Recipe(Step.run(ctx.clang, "--version", s"-j${ctx.jobs}"))))
    case "indirjobs" => Seq(local(ctx => Recipe(Step.run(ctx.clang, "--version").in(ctx.staging / s"b${ctx.jobs}"))))
    case "outside"   => Seq(local(ctx => Recipe(compiled(ctx, ctx.source / "answer.c")).libraries(base / "outside" / "libanswer.a")))
    case "intarget"  => Seq(local(ctx => Recipe(compiled(ctx, ctx.source / "answer.c"), Step.run(ctx.clang, "--version").in(outputs))))
    case "nametarget"   => Seq(local(keying(outputs)))
    case "namevendored" => Seq(local(keying(outputs / "snx")))
    case "unreadablefile"  => Seq(local(keying(base / "secret" / "x.h")))
    case "unreadablematch" => Seq(local(keying(Matching(base / "matchroot", "**/*.h"))))
    case "readsource" =>
      Seq(git(ctx => Recipe(compiled(ctx, s"-DSNX_VARIANT=${IO.read(ctx.source / "VARIANT").trim}", ctx.source / "answer.c"))))
    case "named"      => Seq(git(ctx => Recipe(compiled(ctx, ctx.source / "answer.c"))))
    case "listsource" => Seq(git(ctx => Recipe(compiled(ctx, (ctx.source * "*.c").get()*))))
    case "matching" =>
      Seq(git { ctx =>
        val library = Toolchain.archive(ctx, "answer")
        Recipe(compiled(ctx, Matching(ctx.source, "*.c")), Toolchain.archived(ctx, Toolchain.archiver(ctx), library, ctx.staging / "answer.o"))
          .libraries(library)
      })
    case "cmake" => Seq(NativeLibrary("cmakelib", Vendored.local("vendor/answer").cmake("x")))
  }
}

def vendored(target: File): File = target / "snx" / "vendored" / "main"
def stagings(target: File): Seq[File] =
  Option(vendored(target).listFiles).toSeq.flatten.filter(d => d.isDirectory && d.getName != "clones")

// expectFailure <class> <fragment...>: the configuration fails, a cause of the failure is an SNXError of that class
// (or any class, for `*`), and its message names the library and holds the fragment.
val expectFailure = inputKey[Unit]("assert the native configuration fails with the given error")
expectFailure := Def.uncached {
  val args = complete.DefaultParsers.spaceDelimited("<class> <fragment>").parsed
  val (kind, fragment) = (args.head, args.tail.mkString(" "))
  (Compile / SNX.config).result.value match {
    case Result.Value(_) => sys.error(s"expected the configuration to fail with $kind, but it resolved")
    case Result.Inc(cause) =>
      val errors = Incomplete.allExceptions(cause).toSeq.collect { case error: snx.SNXError => error }
      val matched = errors.filter(error => kind == "*" || error.getClass.getSimpleName == kind)
      assert(matched.nonEmpty, s"no $kind among the failure's causes: ${Incomplete.allExceptions(cause).mkString("; ")}")
      assert(matched.exists(_.getMessage.contains(fragment)), s"no $kind says '$fragment': ${matched.map(_.getMessage).mkString("; ")}")
      assert(matched.exists(_.getMessage.contains("snx: vendored library '")), s"$kind does not name its library")
      streams.value.log.info(s"snx vendored/commandfail: ${matched.head.getMessage}")
  }
}

val removeStaging = taskKey[Unit]("delete the staging, the Git clones kept")
removeStaging := Def.uncached(IO.delete(stagings(target.value)))

val removeClones = taskKey[Unit]("delete the Git clones")
removeClones := Def.uncached(IO.delete(vendored(target.value) / "clones"))

val removeVendored = taskKey[Unit]("delete every vendored directory under target")
removeVendored := Def.uncached(IO.delete(target.value / "snx"))

val expectBuilt = taskKey[Unit]("assert the library was built this run")
expectBuilt := Def.uncached {
  assert(stagings(target.value).exists(staging => (staging / "answer.o").isFile), "expected a cache miss: the build to have compiled answer.o")
}

val expectCached = taskKey[Unit]("assert the library was served from the cache")
expectCached := Def.uncached {
  val config = (Compile / SNX.config).value.toString
  val archive = "[^\\s,()\\[\\]]+(?:libanswer\\.a|answer\\.lib)".r.findFirstIn(config).map(file).getOrElse(sys.error(s"no answer archive in $config"))
  assert(archive.isFile && !(archive.getParentFile / "answer.o").exists, s"expected a cache hit: no build to have compiled answer.o beside $archive")
}

val expectNoStepRan = taskKey[Unit]("assert no step of the failed build ran")
expectNoStepRan := Def.uncached {
  val ran = stagings(target.value).map(_ / "answer.o").filter(_.exists)
  assert(ran.isEmpty, s"a step ran before the refusal: $ran")
}

// A complete clone is one holding its completion marker - what the plugin decides by. On Windows a delete can leave a
// clone's read-only git objects behind, so a directory alone says nothing.
val expectClones = inputKey[Unit]("assert a complete Git clone is present or absent")
expectClones := Def.uncached {
  val present = complete.DefaultParsers.spaceDelimited("<present|absent>").parsed.head == "present"
  val markers = (vendored(target.value) / "clones" * "*" / ".snx-complete").get().nonEmpty
  assert(markers == present, s"expected a complete clone ${if (present) "present" else "absent"}")
}

// lock <path>: make a file or directory unreadable where the host's permissions bind, recording that they did; on a
// host where they do not (Windows, or a run as root) the cases that need it say so and are skipped.
val locked = settingKey[File]("the record that a lock took")
locked := target.value / "locked"
val lock = inputKey[Unit]("make the given path unreadable")
lock := Def.uncached {
  val path = (baseDirectory.value / complete.DefaultParsers.spaceDelimited("<path>").parsed.head).toPath
  val bound = scala.util.Try(Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("---------"))).isSuccess &&
    !Files.isReadable(path)
  if (bound) IO.touch(locked.value) else IO.delete(locked.value)
  streams.value.log.info(s"snx vendored/commandfail: $path ${if (bound) "made unreadable" else "stays readable on this host"}")
}
val unlock = inputKey[Unit]("make the given path readable again")
unlock := Def.uncached {
  val path = (baseDirectory.value / complete.DefaultParsers.spaceDelimited("<path>").parsed.head).toPath
  val _ = scala.util.Try(Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x")))
  IO.delete(locked.value)
}
val expectUnreadable = taskKey[Unit]("assert the configuration fails with InputUnreadable where the lock took")
expectUnreadable := Def.uncached {
  val result = (Compile / SNX.config).result.value
  if (!locked.value.exists) streams.value.log.info("snx vendored/commandfail: permissions do not bind on this host; skipped")
  else result match {
    case Result.Value(_) => sys.error("expected the configuration to fail with InputUnreadable, but it resolved")
    case Result.Inc(cause) =>
      val errors = Incomplete.allExceptions(cause).toSeq.collect { case error: snx.SNXError.InputUnreadable => error }
      assert(errors.exists(_.getMessage.contains("snx: vendored library 'answer'")), s"no InputUnreadable naming the library: $cause")
  }
}

// A local repository, made offline, whose tag v1 holds the answer source.
val setupRepo = taskKey[Unit]("create a local git repo holding the answer sources at tag v1")
setupRepo := {
  val repo = baseDirectory.value / "answer-repo"
  IO.delete(repo)
  IO.copyDirectory(baseDirectory.value / "answer-src", repo)
  def git(args: String*): Unit = {
    val rc = scala.sys.process.Process("git" +: args, repo).!
    if (rc != 0) sys.error(s"git ${args.mkString(" ")} failed in $repo")
  }
  git("init", "-q")
  git("add", ".")
  git("-c", "user.email=snx@example.com", "-c", "user.name=snx", "-c", "commit.gpgSign=false", "commit", "-q", "-m", "answer")
  git("-c", "tag.gpgSign=false", "-c", "tag.forceSignAnnotated=false", "tag", "v1")
}
