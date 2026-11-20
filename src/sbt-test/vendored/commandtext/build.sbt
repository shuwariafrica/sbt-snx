import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

enablePlugins(SNXPlugin)

scalaVersion := sys.props("scala.version")

// A path a recipe writes in its text is keyed by what it names. Each case, chosen by probeCase, builds the archive and
// then runs a step whose text names the case's path - `clang --version`, which reads nothing it is passed - so the key
// is all the case exercises. `rebuilds`, `hits` and `steady` drive a case through its builds and its edit, and skip a
// case its host cannot form, saying so.
val probeCase = settingKey[String]("the text case the recipe takes")
probeCase := "none"

val windows = scala.util.Properties.isWin

def shared(base: File): File = base.getParentFile / s"${base.getName}-shared"
def outsideLink(base: File): File = base.getParentFile / s"${base.getName}-link"
def oneDrive(base: File): File = base / (if (windows) "OneDrive - Contoso" else "One Drive - Co")
def capabilities(base: File): File = base / ".caps"

// The Windows spellings of a path: rooted on its drive, UNC through the drive's administrative share, and msys.
def rooted(file: File): String = file.getAbsolutePath.drop(2)
def unc(file: File): String = s"\\\\localhost\\${file.getAbsolutePath.take(1)}$$${rooted(file)}"
def msys(file: File): String = s"/${file.getAbsolutePath.take(1).toLowerCase}${rooted(file).replace('\\', '/')}"

SNX.libraries ++= {
  val base = baseDirectory.value
  val outputs = target.value
  val name = probeCase.value
  def keying(named: BuildContext => String | File | Argument | Matching): NativeLibrary =
    NativeLibrary("answer", Vendored.local("vendor/answer").command { ctx =>
      val obj = ctx.staging / "answer.o"
      val library = Toolchain.archive(ctx, "answer")
      Recipe(
        Step.run(ctx.clang, "-c", ctx.source / "answer.c", "-o", obj),
        Toolchain.archived(ctx, Toolchain.archiver(ctx), library, obj),
        Step.run(ctx.clang, "--version", named(ctx))
      ).libraries(library)
    })
  val header = base / "win" / "w.h"
  val cases: Map[String, BuildContext => String | File | Argument | Matching] = Map(
    "absolute" -> (_ => s"-include${(base / "config" / "a.h").getAbsolutePath}"),
    "relative" -> (ctx => s"-include${ctx.staging.toPath.relativize((base / "config" / "r.h").toPath)}"),
    "linked" -> (_ => s"-include${outsideLink(base).getAbsolutePath}${java.io.File.separator}config${java.io.File.separator}l.h"),
    "includedir" -> (_ => s"-I${(base / "inc").getAbsolutePath}"),
    "projectfile" -> (_ => base / "project"),
    "hostdir" -> (_ => s"-I${shared(base).getAbsolutePath}"),
    "linktext" -> (_ => s"-I${(base / "linkdir").getAbsolutePath}"),
    "linkarg" -> (_ => arg"-I${base / "linkdir"}"),
    "gentext" -> (_ => s"-include${(outputs / "gen" / "g.h").getAbsolutePath}"),
    "genfile" -> (_ => outputs / "gen" / "g.h"),
    "apostrophe" -> (_ => s"-include${(base / "o'brien" / "x.h").getAbsolutePath}"),
    "unreadable" -> (_ => s"-I${(base / "dataroot").getAbsolutePath}"),
    "response" -> (_ => s"@${(base / "cfg" / "args.rsp").getAbsolutePath}"),
    "linkout" -> (_ => s"-I${(base / "vendorlink").getAbsolutePath}"),
    "twospacedir" -> (_ => s"-include ${(oneDrive(base) / "extra.h").getAbsolutePath} -O2"),
    "twospacefile" -> (_ => s"-include ${(base / "cfg" / "my big file.h").getAbsolutePath}"),
    "escapedquote" -> (_ => "CFLAGS=-DCFG=\\\"" + (base / "inc" / "quoted.h").getAbsolutePath + "\\\""),
    "glued" -> (_ => s"test -f ${(base / "inc" / "glued.h").getAbsolutePath}&&true"),
    "escapedspace" -> (_ => s"CFLAGS=-include ${(oneDrive(base) / "escaped.h").getAbsolutePath.replace(" ", "\\ ")}"),
    "sametime" -> (_ => s"-include${(base / "same" / "same.h").getAbsolutePath}"),
    "driveglued" -> (_ => s"-I${header.getParentFile.getAbsolutePath}"),
    "fi" -> (_ => s"/FI${header.getAbsolutePath}"),
    "rooted" -> (_ => s"-include ${rooted(header)}"),
    "unc" -> (_ => s"-include ${unc(header)}"),
    "msys" -> (_ => s"CFLAGS=-include ${msys(header)}"),
    "upper" -> (_ => s"-include ${header.getAbsolutePath.toUpperCase}"),
    "shortname" -> (_ => s"-include ${IO.read(capabilities(base) / "short").trim}\\short.h"),
    "targetlink" -> (_ => Matching(base / "project", "**/*")),
    "customtarget" -> (_ => base / "app")
  )
  cases.get(name).map(keying).toSeq
}

// What a case needs of its host, and whether this one has it, recorded by setupText.
val needs: Map[String, String] = Map(
  "linked" -> "links", "linktext" -> "links", "linkarg" -> "links", "linkout" -> "links", "targetlink" -> "links",
  "escapedspace" -> "posix", "unreadable" -> "unreadable",
  "driveglued" -> "windows", "fi" -> "windows", "rooted" -> "windows", "msys" -> "windows", "upper" -> "windows",
  "unc" -> "unc", "shortname" -> "short"
)
def runs(state: State, name: String): Boolean = {
  val need = needs.get(name)
  val has = need.forall(capability => (capabilities(state.baseDir) / capability).exists)
  if (!has) state.log.info(s"snx vendored/commandtext: $name needs ${need.mkString} on its host, which this one lacks; skipped")
  has
}
def phase(name: String): List[String] = List(s"""set probeCase := "$name"""", "removeStaging", "Compile/snxConfig", "expectBuilt")

// rebuilds <case> <file>: built, then rebuilt once the file is edited.
commands += Command.args("rebuilds", "<case> <file>") { (state, args) =>
  if (runs(state, args(0))) (phase(args(0)) ++ List(s"edit ${args(1)}", "removeStaging", "Compile/snxConfig", "expectBuilt")) ::: state
  else state
}
// rebuildsAtTime <case> <file>: built, then rebuilt once the file is changed at its size and modification time.
commands += Command.args("rebuildsAtTime", "<case> <file>") { (state, args) =>
  if (runs(state, args(0))) (phase(args(0)) ++ List(s"editSameTime ${args(1)}", "removeStaging", "Compile/snxConfig", "expectBuilt")) ::: state
  else state
}
// hits <case> <file>: built, then a hit once the file is edited - a boundary stated where users write recipes.
commands += Command.args("hits", "<case> <file>") { (state, args) =>
  if (runs(state, args(0))) (phase(args(0)) ++ List(s"edit ${args(1)}", "removeStaging", "Compile/snxConfig", "expectCached")) ::: state
  else state
}
// steady <case>: three builds in a row, the staging left as each leaves it, the second and third hits.
commands += Command.args("steady", "<case>") { (state, args) =>
  if (runs(state, args(0))) (phase(args(0)) ++ List("removeObject", "Compile/snxConfig", "expectCached", "removeObject", "Compile/snxConfig", "expectCached")) ::: state
  else state
}
// refusedUnreadable <case>: with dataroot/locked unreadable, the configuration fails with InputUnreadable naming the
// library. Locked after the `set`, since sbt 2.1 lists the project directory when it applies settings.
commands += Command.args("refusedUnreadable", "<case>") { (state, args) =>
  if (runs(state, args(0))) List(s"""set probeCase := "${args(0)}"""", "removeStaging", "lock", "expectUnreadable", "unlock") ::: state
  else state
}

def stagings(target: File): Seq[File] =
  Option((target / "snx" / "vendored" / "main").listFiles).toSeq.flatten.filter(d => d.isDirectory && d.getName != "clones")

val removeStaging = taskKey[Unit]("delete the library's staging")
removeStaging := Def.uncached(IO.delete(stagings(target.value)))

val removeObject = taskKey[Unit]("delete the object the build compiles, leaving the rest of its staging")
removeObject := Def.uncached(IO.delete(stagings(target.value).map(_ / "answer.o")))

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

val expectUnreadable = taskKey[Unit]("assert the configuration fails with InputUnreadable naming the library")
expectUnreadable := Def.uncached {
  (Compile / SNX.config).result.value match {
    case Result.Value(_) => sys.error("expected the configuration to fail with InputUnreadable, but it resolved")
    case Result.Inc(cause) =>
      val errors = Incomplete.allExceptions(cause).toSeq.collect { case error: snx.SNXError.InputUnreadable => error }
      assert(errors.exists(_.getMessage.contains("snx: vendored library 'answer'")), s"no InputUnreadable naming the library: $cause")
  }
}

// edit <file>: append a line, so its content changes. A path under @shared, @target or @onedrive is in the shared
// directory, sbt's target or the folder whose name holds spaces; @apostrophe and @bigfile name the files whose paths
// hold `'` and spaces, which a scripted command cannot quote. editSameTime <file>: change one character, keeping the file's size and modification time, as a copy
// that preserves times leaves it.
def located(base: File, outputs: File, path: String): File =
  if (path.startsWith("@shared/")) shared(base) / path.stripPrefix("@shared/")
  else if (path.startsWith("@target/")) outputs / path.stripPrefix("@target/")
  else if (path.startsWith("@onedrive/")) oneDrive(base) / path.stripPrefix("@onedrive/")
  else if (path == "@apostrophe") base / "o'brien" / "x.h"
  else if (path == "@bigfile") base / "cfg" / "my big file.h"
  else base / path
val edit = inputKey[Unit]("append a line to the given file")
edit := Def.uncached {
  val file = located(baseDirectory.value, target.value, complete.DefaultParsers.spaceDelimited("<file>").parsed.mkString(" "))
  IO.append(file, s"/* edited ${System.nanoTime} */\n")
}
val editSameTime = inputKey[Unit]("change the given file at its size and modification time")
editSameTime := Def.uncached {
  val file = located(baseDirectory.value, target.value, complete.DefaultParsers.spaceDelimited("<file>").parsed.mkString(" "))
  val modified = file.lastModified
  val text = IO.read(file)
  IO.write(file, text.replace("SNX_SAME 1", "SNX_SAME 2"))
  assert(IO.read(file) != text && IO.read(file).length == text.length, s"$file was not changed at its size")
  file.setLastModified(modified)
}

// The files the cases name, the links where the host permits them, and what the host can form, under .caps.
val setupText = taskKey[Unit]("lay out the files, links and host capabilities the cases use")
setupText := Def.uncached {
  val base = baseDirectory.value
  val outputs = target.value
  val log = streams.value.log
  val caps = capabilities(base)
  IO.createDirectory(caps)
  def header(file: File): Unit = IO.write(file, "#define SNX_NOTE 1\n")
  Seq(base / "config" / "a.h", base / "config" / "r.h", base / "config" / "l.h", base / "inc" / "note.h", base / "inc" / "quoted.h",
    base / "inc" / "glued.h", shared(base) / "s.h", shared(base) / "real.h", shared(base) / "v.h", outputs / "gen" / "g.h",
    base / "o'brien" / "x.h", base / "cfg" / "h.h", oneDrive(base) / "extra.h", oneDrive(base) / "escaped.h",
    oneDrive(base) / "short.h", base / "cfg" / "my big file.h", base / "win" / "w.h").foreach(header)
  IO.write(base / "same" / "same.h", "#define SNX_SAME 1\n")
  IO.write(base / "cfg" / "args.rsp", s"-include ${(base / "cfg" / "h.h").getAbsolutePath}\n")
  IO.createDirectory(base / "dataroot" / "locked")
  def link(at: File, to: File): Boolean = scala.util.Try { IO.delete(at); Files.createSymbolicLink(at.toPath, to.toPath) }.isSuccess
  IO.createDirectory(base / "linkdir")
  val links = link(base / "linkdir" / "a.h", shared(base) / "real.h") && link(base / "vendorlink", shared(base)) && link(outsideLink(base), base)
  def record(capability: String, has: Boolean): Unit = {
    if (has) IO.touch(caps / capability) else IO.delete(caps / capability)
    log.info(s"snx vendored/commandtext: $capability: ${if (has) "available" else "not on this host"}")
  }
  record("links", links)
  record("windows", windows)
  record("posix", !windows)
  record("unc", windows && new File(unc(base / "win" / "w.h")).isFile)
  val short =
    if (!windows) None
    else scala.util.Try(scala.sys.process.Process(Seq("cmd", "/c", s"""for %I in ("${oneDrive(base).getAbsolutePath}") do @echo %~sI""")).!!.trim)
      .toOption.filter(path => path.nonEmpty && !path.equalsIgnoreCase(oneDrive(base).getAbsolutePath))
  short.foreach(path => IO.write(caps / "short", path))
  record("short", short.nonEmpty)
  record("unreadable", locks(base))
  unlocks(base)
}

// Lock dataroot/locked where the host's permissions bind (not on Windows, nor for root), only around the case that
// needs it: every other case walks the project.
def locks(base: File): Boolean = {
  val locked = (base / "dataroot" / "locked").toPath
  scala.util.Try(Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"))).isSuccess && !Files.isReadable(locked)
}
def unlocks(base: File): Unit = {
  val _ = scala.util.Try(Files.setPosixFilePermissions((base / "dataroot" / "locked").toPath, PosixFilePermissions.fromString("rwxr-xr-x")))
}
val lock = taskKey[Unit]("make the locked directory unreadable")
lock := Def.uncached { val _ = locks(baseDirectory.value) }

val unlock = taskKey[Unit]("make the locked directory readable again")
unlock := Def.uncached(unlocks(baseDirectory.value))

// A link inside a directory to sbt's target, which holds the library's staging, as a project might keep to reach its
// deep output directory.
val linkTarget = taskKey[Unit]("link project/out to sbt's target directory, where the host permits")
linkTarget := Def.uncached {
  val made =
    scala.util.Try(Files.createSymbolicLink((baseDirectory.value / "project" / "out").toPath, (baseDirectory.value / "target").toPath)).isSuccess
  if (!made) IO.delete(capabilities(baseDirectory.value) / "links")
}

val keyTiming = taskKey[Unit]("print the key's cost for a recipe naming the archiver and the compiler's directory")
keyTiming := Def.uncached {
  val clang = SNX.clang.value.getOrElse(scala.scalanative.build.Discover.clang().toFile)
  val runtime = SNX.runtime.value
  val staging = target.value / "timing"
  snx.sbt.KeyTiming.report(staging, runtime, clang, Toolchain.archiver(runtime, clang), Seq(baseDirectory.value), Seq(target.value), streams.value.log)
}
