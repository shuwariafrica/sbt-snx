/****************************************************************
 * Copyright © 2026 Shuwari Africa Ltd.                         *
 *                                                              *
 * This file is licensed to you under the terms of the Apache   *
 * License Version 2.0 (the "License"); you may not use this    *
 * file except in compliance with the License. You may obtain   *
 * a copy of the License at:                                    *
 *                                                              *
 *     https://www.apache.org/licenses/LICENSE-2.0              *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, *
 * either express or implied. See the License for the specific  *
 * language governing permissions and limitations under the     *
 * License.                                                     *
 ****************************************************************/
package snx.sbt

import sbt.io.syntax.*

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import scala.scalanative.build.Mode
import scala.util.Try

import snx.ABI
import snx.Arch
import snx.NativeRuntime
import snx.SNXError
import snx.sbt.SNXImports.arg

// A command build's recipe: its key, the walks and digests the key reads, and the steps the plugin runs, over a
// temporary build whose files each case makes for itself.
class RecipeSuite extends munit.FunSuite:

  private val glibc = NativeRuntime.Linux(Arch.X86_64, ABI.Glibc)
  private val msvc = NativeRuntime.Windows(Arch.X86_64, ABI.Msvc)
  private val isWindows = scala.util.Properties.isWin

  // Real, so a temporary directory reached through a link (macOS's /var) or an 8.3 name (a Windows runner's profile)
  // is spelt as the key spells what it reaches.
  private val root: File = Files.createTempDirectory("snx-recipe").nn.toRealPath().nn.toFile.nn
  private val base = root / "build" // the sbt build
  private val beside = root / "beside" // a directory outside it
  private val target = base / "target"
  private val source = base / "source-not-yet-fetched"

  private def write(file: File, text: String): Unit =
    val _ = Files.createDirectories(file.toPath.nn.getParent)
    val _ = Files.write(file.toPath, text.getBytes(StandardCharsets.UTF_8))

  private def written(file: File, text: String): File =
    write(file, text)
    file

  // Files, so a key that read the link's compilers would see them.
  private val clang = written(beside / "llvm/bin/clang", "clang")
  private val clangPP = written(beside / "llvm/bin/clang++", "clang++")

  private def context(config: String): BuildContext =
    BuildContext(source, base / s"shape/snx/vendored/$config/lib", glibc, Linkage.Static, Mode.releaseFast, clang, clangPP, Argument.jobs)
  private val main = context("main")
  private val vendoring = Backend.Vendoring("lib", Seq(base), Seq(target), main.staging)
  private def keyOf(recipe: Recipe): Seq[String] = Backend.recipeKey(recipe, main, vendoring)
  private def keyOf(recipe: Recipe, at: BuildContext): Seq[String] = Backend.recipeKey(recipe, at, vendoring)
  private def keyOf(recipe: Recipe, on: Backend.Vendoring): Seq[String] = Backend.recipeKey(recipe, main, on)

  override def afterAll(): Unit = sbt.io.IO.delete(root)

  private def directory(file: File): Unit =
    val _ = Files.createDirectories(file.toPath)

  private def mkdirs(file: File): File =
    directory(file)
    file

  // A symbolic link at `at` to `to`, or the case is skipped where the host does not permit one (Windows without the
  // privilege).
  private def link(at: File, to: File): Unit =
    val _ = Files.deleteIfExists(at.toPath)
    directory(at.getAbsoluteFile.nn.getParentFile.nn)
    val made = Try(Files.createSymbolicLink(at.toPath, to.toPath)).isSuccess
    assume(made, s"this host does not permit a symbolic link at $at")

  private def linked(at: File, to: File): File =
    link(at, to)
    at

  // `file` made unreadable, or the case skipped where permissions do not bind (Windows, or a run as root).
  private def unreadable(file: File): File =
    val _ = file.setReadable(false, false)
    if file.isDirectory then
      val _ = file.setExecutable(false, false)
    val denied = if file.isDirectory then Option(file.list).isEmpty else !Files.isReadable(file.toPath)
    if !denied then readable(file)
    assume(denied, s"permissions do not stop this process reading $file")
    file

  private def readable(file: File): Unit =
    val _ = file.setReadable(true, false)
    if file.isDirectory then
      val _ = file.setExecutable(true, false)

  private def refused[E <: SNXError](value: => Any)(using tag: scala.reflect.ClassTag[E]): E =
    intercept[E](value)

  // Refused with `E`, its message naming the library.
  private def refusedNaming[E <: SNXError](value: => Any)(using tag: scala.reflect.ClassTag[E]): Unit =
    assert(clue(intercept[E](value).getMessage).contains("vendored library 'lib'"))

  // A recipe touching every field, and one variant per field.
  final private case class Knobs(
    program: String,
    argument: String,
    directory: String,
    envName: String,
    envValue: String,
    copyTo: String,
    library: String,
    include: String,
    swapSteps: Boolean,
    swapLibraries: Boolean)
  private val knobs = Knobs("make", "NO_THREADS=1", "build", "CFLAGS", "-O2", "build", "build/liba.a", "build/include", false, false)
  private val patch = base / "project-files/patch.diff"

  private def recipe(k: Knobs)(c: BuildContext): Recipe =
    val copy = Step.copy(c.source, c.staging / k.copyTo)
    val make = Step
      .run(k.program, "-C", c.staging / "build", k.argument, s"--prefix=${(c.staging / "prefix").getAbsolutePath}", patch)
      .in(c.staging / k.directory)
      .env(k.envName -> k.envValue)
    val libraries = Seq(c.staging / k.library, c.staging / "build/libb.a")
    Recipe((if k.swapSteps then Seq(make, copy) else Seq(copy, make))*)
      .libraries((if k.swapLibraries then libraries.reverse else libraries)*)
      .includes(c.staging / k.include)
  private def key(k: Knobs): Seq[String] = keyOf(recipe(k)(main))

  test("the key is computed with the source absent, a source path keyed relative to the source"):
    write(patch, "patch 1")
    val baseKey = key(knobs)
    assert(!source.exists)
    assert(clue(baseKey).exists(_.contains("from=${source}/")))

  test("a staging path in a string is keyed relative to the staging"):
    write(patch, "patch 1")
    // The argument is written with the host's separator (`getAbsolutePath`), which the token keeps after it.
    assert(clue(key(knobs)).exists(_.contains(s"--prefix=$${staging}${File.separator}prefix")))

  test("the main and test builds of one recipe share a key"):
    write(patch, "patch 1")
    assertEquals(keyOf(recipe(knobs)(context("test")), context("test")), key(knobs))

  test("each field of a recipe changes its key, and an equal recipe gives an equal key"):
    write(patch, "patch 1")
    val variants = Seq(
      "program" -> knobs.copy(program = "gmake-not-installed"),
      "argument" -> knobs.copy(argument = "NO_THREADS=0"),
      "working directory" -> knobs.copy(directory = "build2"),
      "environment name" -> knobs.copy(envName = "CPPFLAGS"),
      "environment value" -> knobs.copy(envValue = "-O3"),
      "copy destination" -> knobs.copy(copyTo = "build2"),
      "library" -> knobs.copy(library = "build/libc.a"),
      "include" -> knobs.copy(include = "build/inc"),
      "step order" -> knobs.copy(swapSteps = true),
      "library order" -> knobs.copy(swapLibraries = true)
    )
    assertEquals(key(knobs), key(knobs))
    variants.foreach((field, variant) => assertNotEquals(key(variant), key(knobs), field))
    assertEquals(variants.map(variant => key(variant._2)).distinct.size, variants.size)

  test("the Command backend's key is its recipe's key"):
    write(patch, "patch 1")
    val one = Backend.Command(recipe(knobs))
    val other = Backend.Command(recipe(knobs.copy(argument = "NO_THREADS=0")))
    assertEquals(one.cacheKey(main, vendoring), "command" +: keyOf(recipe(knobs)(main)))
    assertNotEquals(other.cacheKey(main, vendoring), one.cacheKey(main, vendoring))

  test("a File argument outside the source is keyed by its content"):
    write(patch, "patch 1")
    val before = key(knobs)
    write(patch, "patch 2")
    assertNotEquals(key(knobs), before)
    write(patch, "patch 1")
    assertEquals(key(knobs), before)

  // Paths written in text are keyed by what they name: a file by its content wherever it is, a directory of the build
  // by its content, a directory outside the build as the text alone.
  test("a file of the build named in text is keyed by its content"):
    val header = written(base / "text/config/extra.h", "#define A 1\n")
    val inText = () => keyOf(Recipe(Step.run("cc", s"-include${header.getAbsolutePath}")))
    val before = inText()
    assert(clue(before).exists(_.contains(s"reads:")))
    write(header, "#define A 2\n")
    assertNotEquals(inText(), before)

  test("a file outside the build named in text is keyed by its content"):
    val header = written(beside / "host-header.h", "#define H 1\n")
    val inText = () => keyOf(Recipe(Step.run("cc", s"CFLAGS=-include ${header.getAbsolutePath} -O2")))
    val before = inText()
    write(header, "#define H 22\n")
    assertNotEquals(inText(), before)

  test("a spelling through a symbolic link is keyed by the file it reaches"):
    val header = written(base / "linked/config/extra.h", "#define A 1\n")
    val spelt = linked(beside / "build-link", base)
    val key = keyOf(Recipe(Step.run("cc", s"-include${spelt.getAbsolutePath}/linked/config/extra.h")))
    assert(clue(key).exists(_.contains(header.toPath.nn.toRealPath().toString)))

  test("a relative spelling is resolved against the step's directory, which need not exist"):
    val header = written(base / "relative/extra.h", "#define A 1\n")
    val relative = main.staging.toPath.nn.relativize(header.toPath).toString
    assert(!main.staging.exists)
    assert(clue(keyOf(Recipe(Step.run("cc", s"-include$relative")))).exists(_.contains(header.toPath.nn.toRealPath().toString)))

  test("a directory outside the build named in text is keyed as the text alone"):
    val outside = mkdirs(beside / "include")
    write(outside / "x.h", "1")
    val named = keyOf(Recipe(Step.run("cc", s"-I${outside.getAbsolutePath}")))
    assert(clue(named).exists(entry => entry.contains("-I") && entry.endsWith("reads:")))
    write(outside / "x.h", "2")
    assertEquals(keyOf(Recipe(Step.run("cc", s"-I${outside.getAbsolutePath}"))), named)

  test("a directory of the build named in text is keyed by its content"):
    val include = mkdirs(base / "dirtext/config")
    val header = written(include / "extra.h", "#define A 1\n")
    val inText = () => keyOf(Recipe(Step.run("cc", s"-I${include.getAbsolutePath}")))
    val before = inText()
    assert(clue(before).exists(_.contains(s"${include.toPath.nn.toRealPath()}@")))
    write(header, "#define A 3\n")
    assertNotEquals(inText(), before)

  test("a file under sbt's target named in text is keyed by its content"):
    val output = written(target / "out/text/x.o", "1")
    val inText = () => keyOf(Recipe(Step.run("cc", output.getAbsolutePath)))
    val before = inText()
    assert(clue(before).exists(_.contains("x.o@")))
    write(output, "2")
    assertNotEquals(inText(), before)

  test("a File under sbt's target is keyed by its content"):
    val output = written(target / "out/file/gen.h", "1")
    val before = keyOf(Recipe(Step.run("cc", output)))
    write(output, "22")
    assertNotEquals(keyOf(Recipe(Step.run("cc", output))), before)

  test("a directory named inside target is walked whole"):
    val output = written(target / "out/whole/gen.h", "1")
    val before = keyOf(Recipe(Step.run("cc", target / "out/whole")))
    write(output, "4444")
    assertNotEquals(keyOf(Recipe(Step.run("cc", target / "out/whole"))), before)

  test("a directory's digest leaves out the build's outputs beneath it"):
    val output = written(target / "out/digest/gen.h", "1")
    val before = keyOf(Recipe(Step.run("cc", base)))
    write(output, "333")
    assertEquals(keyOf(Recipe(Step.run("cc", base))), before)

  test("a directory's digest leaves out its hidden directories"):
    val tree = mkdirs(base / "config-tree")
    directory(tree / "target")
    write(tree / "a.h", "a")
    write(tree / ".git/index", "1")
    val before = keyOf(Recipe(Step.run("cc", new File(tree, "."))))
    write(tree / ".git/index", "changed")
    assertEquals(keyOf(Recipe(Step.run("cc", new File(tree, ".")))), before)

  test("a host path in text with no file there is keyed as text"):
    assert(clue(keyOf(Recipe(Step.run("cc", "-I/no/such/dir")))).contains("argument=text:14:-I/no/such/dirreads:"))

  test("the link's compiler in a String is keyed by its token, and not read"):
    val key = keyOf(Recipe(Step.run("make", s"CC=${clang.getAbsolutePath} --target=x")))
    assert(clue(key).exists(_.endsWith("CC=${clang} --target=xreads:")))

  test("an Argument in s\"...\" text is refused with ArgumentInText"):
    refusedNaming[SNXError.ArgumentInText](keyOf(Recipe(Step.run("make", s"-j${main.jobs}"))))

  test("a recipe that throws while computed fails with RecipeFailed naming the library"):
    refusedNaming[SNXError.RecipeFailed](Backend.Command(_ => sys.error("reads the source")).cacheKey(main, vendoring))

  test("a backend's failure is named for its library"):
    assertEquals(
      SNXError.forLibrary("lib", SNXError.CMakeBuildFailed("snx: no CMakeLists.txt")).getMessage,
      "snx: vendored library 'lib': no CMakeLists.txt")

  test("an Argument keys its staging part relative, shared by main and test, and its outside File part by content"):
    val header = written(beside / "argument/patch.h", "patch 1")
    val joined = (c: BuildContext) => Recipe(Step.run("cc", arg"CFLAGS=-include $header -I${c.staging / "inc"}"))
    val before = keyOf(joined(main))
    assert(clue(before).exists(_.contains("file:${staging}/inc")))
    assertEquals(keyOf(joined(context("test")), context("test")), before)
    write(header, "patch 3")
    assertNotEquals(keyOf(joined(main)), before)

  test("the job count is keyed as ${jobs}"):
    assert(clue(keyOf(Recipe(Step.run("make", arg"-j${main.jobs}")))).exists(_.contains("7:${jobs}")))

  test("an Argument's parts key apart from one part holding their rendering"):
    assertNotEquals(keyOf(Recipe(Step.run("cc", arg"a${"b"}x"))), keyOf(Recipe(Step.run("cc", arg"areads:text:breads:text:x"))))

  test("env called twice keeps both variables"):
    val both = keyOf(Recipe(Step.run("make").env("A" -> "1").env("B" -> "2")))
    assert(clue(both).exists(_.startsWith("env=1:A")) && both.exists(_.startsWith("env=1:B")))

  test("libraries and includes called twice keep both"):
    val added = keyOf(
      Recipe().libraries(main.staging / "a.a").libraries(main.staging / "b.a").includes(main.staging / "i").includes(main.staging / "j"))
    assertEquals(added.count(_.startsWith("library=")), 2, added)
    assertEquals(added.count(_.startsWith("include=")), 2, added)

  test("a root is substituted only where what follows cannot continue a name"):
    assertEquals(Backend.substitute("/a/bc/x /a/b/y", Seq("${staging}" -> "/a/b"), false), "/a/bc/x ${staging}/y")
    assertEquals(Backend.substitute("-I/a/b/x", Seq("${staging}" -> "/a/b"), false), "-I${staging}/x")

  test("on Windows a root is substituted without case and in its forward-slash spelling"):
    assertEquals(Backend.substitute("-Ic:\\work\\lib\\inc", Seq("${staging}" -> "C:\\Work\\Lib"), true), "-I${staging}\\inc")
    assertEquals(Backend.substitute("-DP=C:/work/lib/prefix", Seq("${staging}" -> "C:\\work\\lib"), true), "-DP=${staging}/prefix")

  // A program named without a directory, as a file this host would run from its PATH.
  private val toolName = "snx-shape-tool"
  private def tool(bin: File): File =
    val made = written(bin / (if isWindows then s"$toolName.CMD" else toolName), if isWindows then "@echo off\r\n" else "#!/bin/sh\n")
    made.setExecutable(true)
    made

  test("a program is found on the step's own PATH, and not on the inherited one alone"):
    val bin = mkdirs(beside / "path-bin")
    val made = tool(bin)
    assert(Backend.resolve(Step.run(toolName).env("PATH" -> bin.getAbsolutePath), main.staging).exists(_.equals(made)))
    assert(Backend.resolve(Step.run(toolName), main.staging).isEmpty)

  test("on Windows a name with a dot not in PATHEXT tries each extension, along a quoted entry"):
    val bin = mkdirs(beside / "pathext-bin")
    val cmd = written(bin / "tool.v2.CMD", "@echo off")
    cmd.setExecutable(true)
    val path = Some(s"${(beside / "empty").getAbsolutePath};\"${bin.getAbsolutePath}\"")
    assert(Backend.resolve("tool.v2", main.staging, path, Some(".EXE;.CMD"), true).exists(_.equals(cmd)))
    assert(Backend.resolve("tool.v2.CMD", main.staging, path, Some(".EXE;.CMD"), true).exists(_.equals(cmd)))
    assert(Backend.resolve("tool.v2", main.staging, path, Some(".EXE"), true).isEmpty)

  test("elsewhere PATH splits on ':' and names are exact"):
    assume(!isWindows, "a POSIX PATH cannot hold this host's drive-letter paths")
    val bin = mkdirs(beside / "posix-bin")
    val made = written(bin / toolName, "#!/bin/sh\n")
    made.setExecutable(true)
    assert(Backend.resolve(toolName, main.staging, Some(s"/nonexistent:${bin.getAbsolutePath}"), None, false).exists(_.equals(made)))

  test("a program with a directory resolves against the step's directory and is keyed relative to the staging"):
    assert(Backend.resolve("./configure", main.staging, None, None, false).exists(_.equals(new File(main.staging, "./configure"))))
    assert(clue(keyOf(Recipe(Step.run("./configure")))).contains("program=${staging}/configure"))

  test("the link's compiler as a program is keyed by the toolchain identity, not read"):
    assert(clue(keyOf(Recipe(Step.run(clang, "-c")))).contains("program=${clang}"))

  test("a Matching pattern: `*` within a segment, `**` at any depth, `?` one character"):
    def matches(pattern: String, path: String): Boolean = Backend.globRegex(pattern).matches(path)
    assert(matches("*.o", "a.o") && !matches("*.o", "x/a.o"))
    assert(matches("**/*.o", "a.o") && matches("**/*.o", "x/y/a.o"))
    assert(matches("src/?.c", "src/a.c") && !matches("src/?.c", "src/ab.c"))

  private def listed(pattern: Matching, targets: Seq[Path]): Seq[String] =
    Backend.matched(pattern, pattern.directory, files = true, targets, (_, error) => throw error).map(_.getName) // scalafix:ok

  test("a Matching's files come sorted by their relative path"):
    val objects = mkdirs(base / "objects")
    Seq("b.o", "a.o", "sub/c.o", "sub/d.c").foreach(name => write(objects / name, name))
    assertEquals(listed(Matching(objects, "**/*.o"), Seq.empty), Seq("a.o", "b.o", "c.o"))

  test("a Matching under the staging is keyed by its pattern, unlisted"):
    val key = keyOf(Recipe().libraries(Matching(main.staging / "build/src", "**/*.o")))
    assert(clue(key).exists(entry => entry.contains("${staging}/build/src:6:**/*.o") && !entry.contains("@")))

  test("a Matching outside the source and staging is keyed by what it matches"):
    val objects = mkdirs(base / "matched-objects")
    write(objects / "a.o", "a")
    val before = keyOf(Recipe(Step.run("cc", Matching(objects, "*.o"))))
    write(objects / "a.o", "changed")
    assertNotEquals(keyOf(Recipe(Step.run("cc", Matching(objects, "*.o")))), before)

  test("'**' does not enter a hidden directory, and a hidden directory named as a literal leading segment is"):
    val tree = mkdirs(base / "hidden-tree")
    Seq("src/a.c", "src/.hidden/b.c", "src/sub/c.c", ".cfg/d.h").foreach(name => write(tree / name, name))
    assertEquals(listed(Matching(tree, "src/**/*.c"), Seq.empty), Seq("a.c", "c.c"))
    assertEquals(listed(Matching(tree, ".cfg/*.h"), Seq.empty), Seq("d.h"))

  // The kept digest of a program outside the build.
  test("a program modified in its hashing's tick, at the same size, is hashed again"):
    val racy = written(beside / "racy-tool", "AAAA")
    // Modified after it is hashed, so the digest is untrusted however long the case takes.
    val ahead = System.currentTimeMillis + 60000
    racy.setLastModified(ahead)
    val before = keyOf(Recipe(Step.run(racy)))
    write(racy, "BBBB")
    racy.setLastModified(ahead)
    assertNotEquals(keyOf(Recipe(Step.run(racy))), before)

  test("a program reached through a link switched to a same-sized file of the same time is keyed anew"):
    val storeA = written(beside / "store-a", "1111")
    val storeB = written(beside / "store-b", "2222")
    Seq(storeA, storeB).foreach(_.setLastModified(1000L))
    val profile = linked(beside / "profile", storeA)
    val before = keyOf(Recipe(Step.run(profile)))
    link(profile, storeB)
    assertNotEquals(keyOf(Recipe(Step.run(profile))), before)

  test("a host tool changed in place gets a new digest"):
    val host = written(beside / "host-tool", "#!/bin/sh\n# 1\n")
    host.setLastModified(1000000L)
    val before = keyOf(Recipe(Step.run(host)))
    write(host, "#!/bin/sh\n# version 2\n")
    assertNotEquals(keyOf(Recipe(Step.run(host))), before)

  // Links inside a keyed directory are followed, a cycle entered once.
  test("a link inside a directory of the build is keyed by its target's content: named in text, as a File, through a Matching"):
    val shared = mkdirs(beside / "shared")
    val header = written(shared / "real.h", "#define R 1\n")
    val linkDir = mkdirs(base / "linkdir")
    link(linkDir / "a.h", header)
    link(linkDir / "loop", linkDir)
    val view = mkdirs(beside / "linkview")
    link(view / "v.h", header)
    val inText = () => keyOf(Recipe(Step.run("cc", s"-I${linkDir.getAbsolutePath}")))
    val asFile = () => keyOf(Recipe(Step.run("cc", linkDir)))
    val matching = () => keyOf(Recipe(Step.run("cc", Matching(view, "*.h"))))
    val text = inText()
    val file = asFile()
    val matched = matching()
    write(header, "#define R 22\n")
    assertNotEquals(inText(), text)
    assertNotEquals(asFile(), file)
    assertEquals(listed(Matching(view, "*.h"), Seq.empty), Seq("v.h"))
    assertNotEquals(matching(), matched)

  test("a link inside the build to a directory outside it is keyed as the text alone"):
    val shared = mkdirs(beside / "vendor-shared")
    write(shared / "v.h", "1")
    val vendorLink = linked(base / "vendorlink", shared)
    assert(clue(keyOf(Recipe(Step.run("cc", s"-I${vendorLink.getAbsolutePath}")))).exists(entry =>
      entry.contains("-I") && entry.endsWith("reads:")))

  test("an unreadable directory beneath a named one fails with InputUnreadable naming the library"):
    val data = mkdirs(base / "dataroot")
    val locked = unreadable(mkdirs(data / "locked"))
    try refusedNaming[SNXError.InputUnreadable](keyOf(Recipe(Step.run("cc", s"-I${data.getAbsolutePath}"))))
    finally readable(locked)

  test("an unreadable file named in text, or passed as a File, fails with InputUnreadable naming the library"):
    val secret = unreadable(written(base / "secret/x.h", "x"))
    try
      refusedNaming[SNXError.InputUnreadable](keyOf(Recipe(Step.run("cc", s"-include${secret.getAbsolutePath}"))))
      refusedNaming[SNXError.InputUnreadable](keyOf(Recipe(Step.run("cc", secret))))
    finally readable(secret)

  test("an unreadable directory under a Matching fails with InputUnreadable naming the library"):
    val matchRoot = mkdirs(base / "matchroot")
    val locked = unreadable(mkdirs(matchRoot / "locked"))
    try refusedNaming[SNXError.InputUnreadable](keyOf(Recipe(Step.run("cc", Matching(matchRoot, "**/*.h")))))
    finally readable(locked)

  test("a file of the build edited at the same size and time is seen"):
    val pinned = written(base / "pinned/pinned.h", "AAAA")
    pinned.setLastModified(1000000L)
    val before = keyOf(Recipe(Step.run("cc", pinned)))
    write(pinned, "BBBB")
    pinned.setLastModified(1000000L)
    assertNotEquals(keyOf(Recipe(Step.run("cc", pinned))), before)

  test("a host program edited at the same size and time is seen by its change time"):
    val pinned = written(beside / "host-pinned", "AAAA")
    pinned.setLastModified(1000000L)
    assume(Try(Files.getAttribute(pinned.toPath, "unix:ctime")).isSuccess, "this platform keeps no change time")
    Thread.sleep(10)
    val before = keyOf(Recipe(Step.run(pinned)))
    Thread.sleep(10)
    write(pinned, "BBBB")
    pinned.setLastModified(1000000L)
    assertNotEquals(keyOf(Recipe(Step.run(pinned))), before)

  // The rules the program memo rests on, through their seams, with the stamp Windows gives (no change time, no file
  // key), so each holds on every host as it does there.
  private val windowsStamps = (path: Path) => Backend.stamp(path).copy(change = "", key = "")
  private def keyOn(recipe: Recipe, programs: Backend.ProgramDigests): Seq[String] =
    Backend.recipeKey(recipe, main, vendoring, programs, windowsStamps)

  test("a file outside the build edited at the same size and time is seen where the platform keeps no change time"):
    val named = written(beside / "named-pinned.h", "AAAA")
    named.setLastModified(1000000L)
    val programs = new Backend.ProgramDigests
    val before = keyOn(Recipe(Step.run("cc", named)), programs)
    write(named, "BBBB")
    named.setLastModified(1000000L)
    assertNotEquals(keyOn(Recipe(Step.run("cc", named)), programs), before)

  test("a program modified within two seconds of its hashing is hashed again, and one modified earlier is kept"):
    val program = written(beside / "memo-tool", "AAAA").toPath
    val hashes = new java.util.concurrent.atomic.AtomicInteger(0)
    val counted = (_: Path) =>
      val _ = hashes.incrementAndGet()
      "digest"
    val recent = Backend.Stamp(4, System.currentTimeMillis + 60000, "", "")
    val fresh = new Backend.ProgramDigests
    (1 to 2).foreach(_ => fresh.digest(program, recent, counted))
    assertEquals(hashes.get, 2)
    hashes.set(0)
    val older = Backend.Stamp(4, 1000000L, "", "")
    (1 to 2).foreach(_ => fresh.digest(program, older, counted))
    assertEquals(hashes.get, 1)
    assert(!Backend.trusted(older, older.modified + 2000) && Backend.trusted(older, older.modified + 2001))

  test("a program's link switched to a same-sized file of the same time is keyed anew where the platform keeps no file key"):
    val toolA = written(beside / "win-tool-a", "1111")
    val toolB = written(beside / "win-tool-b", "2222")
    Seq(toolA, toolB).foreach(_.setLastModified(1000L))
    val toolLink = linked(beside / "win-tool", toolA)
    val programs = new Backend.ProgramDigests
    val before = keyOn(Recipe(Step.run(toolLink)), programs)
    link(toolLink, toolB)
    assertNotEquals(keyOn(Recipe(Step.run(toolLink)), programs), before)

  // A Matching leaves sbt's target directories out of its walk, at key and at run time, unless it starts in one.
  final private case class Project(dir: File):
    val vendoring: Backend.Vendoring = Backend.Vendoring("lib", Seq(base), Seq(dir / "target"), main.staging)
    val targets: Seq[Path] = Seq((dir / "target").toPath.nn.toAbsolutePath.nn.normalize.nn)
  private def project(name: String, headers: String*): Project =
    val dir = mkdirs(base / name)
    headers.foreach(header => write(dir / header, header))
    Project(dir)

  test("a Matching over a directory holding sbt's target leaves the target out of its key and of what it passes"):
    val at = project("matchproject", "inc/a.h", "target/out/gen.h")
    val before = keyOf(Recipe(Step.run("cc", Matching(at.dir, "**/*.h"))), at.vendoring)
    write(at.dir / "target/out/gen.h", "changed")
    assertEquals(keyOf(Recipe(Step.run("cc", Matching(at.dir, "**/*.h"))), at.vendoring), before)
    assertEquals(listed(Matching(at.dir, "**/*.h"), at.targets), Seq("a.h"))

  test("a Matching starting inside target walks it, beneath its start too"):
    val at = project("matchinside", "target/out/gen.h", "target/gen/sub/deep.h")
    assertEquals(listed(Matching(at.dir, "target/out/*.h"), at.targets), Seq("gen.h"))
    assertEquals(listed(Matching(at.dir, "target/gen/**/*.h"), at.targets), Seq("deep.h"))

  // A directory in target holding this library's own staging cannot key the build, by any route; one above target, or
  // one in target beside the staging, keys as before.
  private val stagedRoot = base / "tgtproject"
  private val staged = stagedRoot / "tgt/out/snx/vendored/main/lib"
  private val stagedVendoring = Backend.Vendoring("lib", Seq(base), Seq(stagedRoot / "tgt"), staged)
  private def stagedKey(recipe: Recipe): Seq[String] = Backend.recipeKey(recipe, main.copy(staging = staged), stagedVendoring)

  test("naming sbt's target, which holds the library's staging, fails with InputHoldsStaging naming the library"):
    directory(stagedRoot / "tgt")
    refusedNaming[SNXError.InputHoldsStaging](stagedKey(Recipe(Step.run("cc", stagedRoot / "tgt"))))

  test("a directory in target above the staging is refused before the staging exists"):
    directory(stagedRoot / "tgt/out/snx")
    assert(!staged.exists)
    val _ = refused[SNXError.InputHoldsStaging](stagedKey(Recipe(Step.run("cc", stagedRoot / "tgt/out/snx"))))

  test("target is refused named in text, as a Matching's directory, and as a Matching starting there"):
    directory(stagedRoot / "tgt")
    val _ = refused[SNXError.InputHoldsStaging](stagedKey(Recipe(Step.run("cc", s"-I${(stagedRoot / "tgt").getAbsolutePath}"))))
    val _ = refused[SNXError.InputHoldsStaging](stagedKey(Recipe(Step.run("cc", Matching(stagedRoot / "tgt", "**/*.h")))))
    val _ = refused[SNXError.InputHoldsStaging](stagedKey(Recipe(Step.run("cc", Matching(stagedRoot, "tgt/**/*.h")))))

  test("a directory in target beside the staging is keyed by its content, and one above target with target left out"):
    write(stagedRoot / "tgt/gen/g.h", "g")
    assert(clue(stagedKey(Recipe(Step.run("cc", stagedRoot / "tgt/gen")))).exists(_.contains("gen@")))
    val _ = stagedKey(Recipe(Step.run("cc", stagedRoot)))

  test("an Argument in a run directory is refused with ArgumentInText, before the step's arguments are resolved"):
    refusedNaming[SNXError.ArgumentInText](keyOf(Recipe(Step.run("cc", "-x").in(main.staging / s"b${main.jobs}"))))

  test("an Argument in a File's path is refused with ArgumentInText"):
    refusedNaming[SNXError.ArgumentInText](keyOf(Recipe(Step.run("cc", new File(s"/tmp/x${main.jobs}")))))

  test("an Argument in a Matching's pattern is refused with ArgumentInText"):
    refusedNaming[SNXError.ArgumentInText](keyOf(Recipe(Step.run("cc", Matching(base, s"*${main.jobs}")))))

  test("kufuli's ported recipe keys alike for main and test, on Linux and on MSVC"):
    def argon2(c: BuildContext): Recipe =
      val build = c.staging / "build"
      def make(cc: String): Step = Step.run("make", "-C", build, "libargon2.a", s"CC=$cc", "NO_THREADS=1", "OPTTARGET=")
      c.runtime match
        case NativeRuntime.Windows(_, ABI.Msvc) =>
          Recipe(Step.copy(c.source, build), make(s"${c.clang.getAbsolutePath} --target=x86_64-pc-windows-msvc -fms-runtime-lib=static"))
            .libraries(Matching(build / "src", "**/*.o"))
            .includes(build / "include")
        case _ =>
          Recipe(Step.copy(c.source, build), make(c.clang.getAbsolutePath)).libraries(build / "libargon2.a").includes(build / "include")
    Seq(main, main.copy(runtime = msvc)).foreach: at =>
      val test = at.copy(staging = context("test").staging)
      assertEquals(keyOf(argon2(test), test), keyOf(argon2(at), at), at.runtime.toString)

  // Read 5: a path whose spaces or apostrophe a shell escapes, a link into target, text naming target before it exists,
  // and a write location in target.
  test("a file named in text with its spaces escaped by a backslash is keyed by its content"):
    assume(!isWindows, "on Windows a backslash is a separator")
    val header = written(base / "One Drive - Co/extra.h", "#define A 1\n")
    val escaped = header.getAbsolutePath.replace(" ", "\\ ")
    val inText = () => keyOf(Recipe(Step.run("make", s"CFLAGS=-include $escaped")))
    val before = inText()
    write(header, "#define A 2\n")
    assertNotEquals(inText(), before)

  test("a link into target inside the project leaves the staging out of the project's digest and its Matching"):
    val dir = mkdirs(base / "linkedtarget")
    val projectTarget = dir / "target"
    val linkedStaging = projectTarget / "out/snx/vendored/main/lib"
    write(dir / "inc/a.h", "a")
    write(linkedStaging / "src/answer.h", "1")
    link(dir / "out", projectTarget)
    val on = Backend.Vendoring("lib", Seq(base), Seq(projectTarget), linkedStaging)
    val at = main.copy(staging = linkedStaging)
    val asFile = () => Backend.recipeKey(Recipe(Step.run("cc", dir)), at, on)
    val matching = () => Backend.recipeKey(Recipe(Step.run("cc", Matching(dir, "**/*.h"))), at, on)
    val file = asFile()
    val matched = matching()
    write(linkedStaging / "src/answer.h", "22")
    assertEquals(asFile(), file)
    assertEquals(matching(), matched)
    val targets = Seq(projectTarget.toPath.nn.toAbsolutePath.nn.normalize.nn)
    assertEquals(listed(Matching(dir, "**/*.h"), targets), Seq("a.h"))

  test("a Matching whose directory is spelt through a link leaves the target it reaches out of its walk"):
    val dir = mkdirs(base / "spelttarget")
    write(dir / "inc/a.h", "a")
    write(dir / "target/out/gen.h", "1")
    val spelt = linked(beside / "spelt-project", dir)
    val on = Backend.Vendoring("lib", Seq(base), Seq(dir / "target"), main.staging)
    val before = keyOf(Recipe(Step.run("cc", Matching(spelt, "**/*.h"))), on)
    write(dir / "target/out/gen.h", "22")
    assertEquals(keyOf(Recipe(Step.run("cc", Matching(spelt, "**/*.h"))), on), before)
    assertEquals(listed(Matching(spelt, "**/*.h"), Seq((dir / "target").toPath.nn.toAbsolutePath.nn.normalize.nn)), Seq("a.h"))

  test("text naming a directory in target above the staging is refused before that directory exists"):
    val fresh = base / "freshtarget"
    val freshStaging = fresh / "tgt/out/snx/vendored/main/lib"
    directory(fresh / "tgt")
    val on = Backend.Vendoring("lib", Seq(base), Seq(fresh / "tgt"), freshStaging)
    assert(!(fresh / "tgt/out/snx").exists)
    val _ = refused[SNXError.InputHoldsStaging](
      Backend.recipeKey(Recipe(Step.run("cc", s"-I${(fresh / "tgt/out/snx").getAbsolutePath}")), main.copy(staging = freshStaging), on))

  test("a write location in target is not refused as an input"):
    directory(stagedRoot / "tgt")
    val tgt = stagedRoot / "tgt"
    val _ = stagedKey(Recipe(Step.run("cc").in(tgt)))
    val _ = stagedKey(Recipe(Step.copy(base, tgt)))
    val _ = stagedKey(Recipe().includes(tgt))
    val _ = stagedKey(Recipe().libraries(Matching(tgt, "*.a")))

  // Every meta-build's target: sbt compiles each loaded build's .sbt files into its `project/target`, and loads
  // `project` as a further build while it holds anything but its `target`.
  test("sbt's targets include each meta-build level's, as deep as sbt loads one"):
    val build = mkdirs(base / "metabuild")
    write(build / "project/plugins.sbt", "")
    write(build / "project/project/extra.sbt", "")
    // project/project/project holds its target alone, so sbt loads no build there and uses no target beneath it.
    directory(build / "project/project/project/target")
    val listed = SNXPlugin.targets(build, build, build / "target").map(_.getAbsoluteFile.nn)
    assertEquals(
      listed.toSet,
      Set("target", "project/target", "project/project/target", "project/project/project/target").map(level =>
        (build / level).getAbsoluteFile.nn))

  test("a recipe naming the project directory keys alike when a reload rewrites the meta-build's compiled .sbt files"):
    val build = mkdirs(base / "reloaded")
    write(build / "build.sbt", "")
    write(build / "project/plugins.sbt", "")
    val compiled = build / "project/project/target/config-classes/$Wrap1.class"
    write(compiled, "1")
    val on = Backend.Vendoring("lib", Seq(base), SNXPlugin.targets(build, build, build / "target"), main.staging)
    val before = keyOf(Recipe(Step.run("cc", s"-I${build.getAbsolutePath}")), on)
    write(compiled, "22")
    assertEquals(keyOf(Recipe(Step.run("cc", s"-I${build.getAbsolutePath}")), on), before)

  // The steps the plugin runs, through a portable program: the JDK running Tool.java, whose operations are its
  // arguments.
  private val tools = mkdirs(beside / "tools")
  private val jvm = new File(System.getProperty("java.home"), if isWindows then "bin/java.exe" else "bin/java")
  private val toolSource = written(
    tools / "Tool.java",
    """import java.nio.file.*;
      |public class Tool {
      |  public static void main(String[] a) throws Exception {
      |    Path out = Path.of(a[1]).toAbsolutePath();
      |    if (!a[0].equals("exit")) Files.createDirectories(out.getParent());
      |    switch (a[0]) {
      |      case "append" -> Files.writeString(out, a[2] + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      |      case "env" -> Files.writeString(out, String.valueOf(System.getenv(a[2])));
      |      case "pwd" -> Files.writeString(out, Path.of("").toAbsolutePath().toString());
      |      case "args" -> Files.write(out, java.util.Arrays.asList(a).subList(2, a.length));
      |      case "exit" -> System.exit(Integer.parseInt(a[1]));
      |      default -> throw new IllegalArgumentException(a[0]);
      |    }
      |  }
      |}
      |""".stripMargin
  )
  // Tool.java under a bare name, on a PATH only a step names.
  private val wrapperName = "snx-step-tool"
  private val wrapped =
    val wrapper =
      if isWindows then written(tools / "bin" / s"$wrapperName.cmd", s"@\"$jvm\" \"$toolSource\" %*\r\n")
      else written(tools / "bin" / wrapperName, s"#!/bin/sh\nexec '$jvm' '$toolSource' \"$$@\"\n")
    wrapper.setExecutable(true)
    tools / "bin"

  private def step(arguments: (String | File | Argument | Matching)*): Step.Run =
    Step.run(jvm, (Seq[String | File | Argument | Matching](toolSource) ++ arguments)*)

  final private class Lines extends sbt.util.Logger:
    val lines = new java.util.concurrent.ConcurrentLinkedQueue[String]
    def trace(t: => Throwable): Unit = ()
    def success(message: => String): Unit = ()
    def log(level: sbt.util.Level.Value, message: => String): Unit =
      val _ = lines.add(s"$level $message")

  // Each execution case runs in a staging of its own.
  private val runs = new java.util.concurrent.atomic.AtomicInteger(0)
  final private case class Ran(staging: File, artefacts: Artefacts)
  private def run(recipe: BuildContext => Recipe): Ran = run(recipe, vendoring.targets)
  private def run(recipe: BuildContext => Recipe, targets: Seq[File]): Ran =
    val at = main.copy(staging = base / s"runs/${runs.incrementAndGet()}/lib")
    Ran(at.staging, Backend.execute(recipe(at), at, Backend.Vendoring("lib", Seq(base), targets, at.staging), new Lines))
  private def read(file: File): String = new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)

  test("the steps run in order, each in its directory, the staging by default, created if absent"):
    val ran = run(c =>
      Recipe(
        step("append", c.staging / "order.txt", "first"),
        step("pwd", c.staging / "one.txt").in(c.staging / "one/deep"),
        step("append", c.staging / "order.txt", "second"),
        step("pwd", c.staging / "default.txt")
      ))
    assertEquals(read(ran.staging / "order.txt"), "first\nsecond\n")
    assertEquals(new File(read(ran.staging / "one.txt")).getCanonicalFile, (ran.staging / "one/deep").getCanonicalFile)
    assertEquals(new File(read(ran.staging / "default.txt")).getCanonicalFile, ran.staging.getCanonicalFile)

  test("a step's env is set over the compiler pin, and adds across calls, a variable set twice taking its last value"):
    val ran = run(c =>
      Recipe(
        step("env", c.staging / "pinned.txt", "CC"),
        step("env", c.staging / "own.txt", "CC").env("CC" -> "mine"),
        step("env", c.staging / "a.txt", "SNX_A").env("SNX_A" -> "1").env("SNX_B" -> "2").env("SNX_A" -> "3"),
        step("env", c.staging / "b.txt", "SNX_B").env("SNX_A" -> "1").env("SNX_B" -> "2")
      ))
    assertEquals(read(ran.staging / "pinned.txt"), clang.getAbsolutePath)
    assertEquals(read(ran.staging / "own.txt"), "mine")
    assertEquals(read(ran.staging / "a.txt"), "3")
    assertEquals(read(ran.staging / "b.txt"), "2")

  test("a step runs the program found on its own PATH"):
    val ran = run(c => Recipe(Step.run(wrapperName, "append", c.staging / "ran.txt", "found").env("PATH" -> wrapped.getAbsolutePath)))
    assertEquals(read(ran.staging / "ran.txt"), "found\n")

  test("a directory copied onto another replaces the older files of the same path"):
    val upstream = mkdirs(beside / "overlay-source")
    write(upstream / "x.c", "old")
    write(upstream / "y.c", "kept")
    val overlay = mkdirs(beside / "overlay")
    val _ = written(overlay / "x.c", "new").setLastModified(1000000L)
    val ran = run(c => Recipe(Step.copy(upstream, c.staging / "build"), Step.copy(overlay, c.staging / "build")))
    assertEquals(read(ran.staging / "build/x.c"), "new")
    assertEquals(read(ran.staging / "build/y.c"), "kept")

  test("a Matching argument expands to its matches when the step starts, sbt's target left out"):
    val at = project("runproject", "inc/a.h", "target/out/gen.h")
    val ran = run(c => Recipe(step("args", c.staging / "args.txt", Matching(at.dir, "**/*.h"))), Seq(at.dir / "target"))
    assertEquals(read(ran.staging / "args.txt").linesIterator.toSeq, Seq((at.dir / "inc/a.h").getAbsolutePath))

  test("the outputs are the named libraries and include directories, a Matching resolved after the steps"):
    val ran = run(c =>
      Recipe(step("append", c.staging / "lib/libb.a", "b"), step("append", c.staging / "lib/liba.a", "a"))
        .libraries(c.staging / "lib/libb.a")
        .libraries(Matching(c.staging / "lib", "liba.a"))
        .includes(c.staging / "lib"))
    assertEquals(ran.artefacts.libraries, Seq(ran.staging / "lib/libb.a", ran.staging / "lib/liba.a"))
    assertEquals(ran.artefacts.includes, Seq(ran.staging / "lib"))

  private def failsNaming(recipe: BuildContext => Recipe, fragment: String): Unit =
    val error = intercept[SNXError.CommandBuildFailed](run(recipe))
    assert(clue(error.getMessage).contains("vendored library 'lib'"))
    assert(clue(error.getMessage).contains(fragment))

  test("a step exiting non-zero fails with CommandBuildFailed naming the library and the exit"):
    failsNaming(_ => Recipe(step("exit", "3")), "command exited 3")

  test("a missing program, and one that cannot be started, fail with CommandBuildFailed naming the library"):
    failsNaming(_ => Recipe(Step.run(beside / "no-such-tool", "x")), "was not found")
    val data = written(beside / "data.txt", "not a program")
    failsNaming(_ => Recipe(Step.run(data, "x")), "could not start")

  test("a step directory that cannot be made fails with CommandBuildFailed naming the library"):
    failsNaming(c => Recipe(step("append", c.staging / "work", "a file"), step("exit", "0").in(c.staging / "work/sub")), "could not create")

  test("a failed copy fails with CommandBuildFailed naming the library"):
    failsNaming(c => Recipe(Step.copy(beside / "absent", c.staging / "build")), "nothing to copy")

  test("an output its steps did not write, and a Matching that matches nothing, fail with CommandBuildFailed"):
    failsNaming(c => Recipe(step("exit", "0")).libraries(c.staging / "libnone.a"), "did not write")
    failsNaming(c => Recipe(step("exit", "0")).libraries(Matching(c.staging, "*.a")), "matched nothing")
    failsNaming(c => Recipe(step("args", c.staging / "args.txt", Matching(c.staging, "*.none"))), "matched no file")

  test("an output outside the staging fails with OutputOutsideStaging before any step runs"):
    val marker = beside / "outside-marker.txt"
    val error = intercept[SNXError.OutputOutsideStaging](run(_ => Recipe(step("append", marker, "ran")).libraries(beside / "libx.a")))
    assert(clue(error.getMessage).contains("vendored library 'lib'"))
    assert(!marker.exists)

  test("a run directory or copy destination outside the staging fails with OutputOutsideStaging before any step runs"):
    val marker = beside / "outside-steps.txt"
    val _ = intercept[SNXError.OutputOutsideStaging](run(_ => Recipe(step("append", marker, "ran"), step("exit", "0").in(beside))))
    val _ = intercept[SNXError.OutputOutsideStaging](run(_ => Recipe(step("append", marker, "ran"), Step.copy(patch, beside / "x"))))
    assert(!marker.exists)

  test("a write location in target fails with OutputOutsideStaging when the recipe runs"):
    directory(stagedRoot / "tgt")
    val at = main.copy(staging = staged)
    val error = intercept[SNXError.OutputOutsideStaging](
      Backend.execute(Recipe(step("exit", "0").in(stagedRoot / "tgt")), at, stagedVendoring, new Lines))
    assert(clue(error.getMessage).contains("vendored library 'lib'"))

  test("a clone directory without its completion marker is removed and built again"):
    val clone = mkdirs(root / "clones" / "partial")
    val leftover = written(clone / "git" / "objects" / "pack" / "pack-1.pack", "left by an incomplete delete")
    val _ = leftover.setWritable(false)
    val built = new java.util.concurrent.atomic.AtomicInteger
    val _ = SNXPlugin.materialise(clone) { val _ = built.incrementAndGet() }
    assertEquals(built.get, 1)
    assert(!leftover.exists, s"the read-only leftover $leftover is removed")
    assert((clone / SNXPlugin.completionMarker).isFile)

  test("a clone directory with its completion marker is kept"):
    val clone = mkdirs(root / "clones" / "complete")
    val kept = written(clone / "source" / "answer.c", "int answer(void);")
    val _ = written(clone / SNXPlugin.completionMarker, "")
    val built = new java.util.concurrent.atomic.AtomicInteger
    val _ = SNXPlugin.materialise(clone) { val _ = built.incrementAndGet() }
    val _ = SNXPlugin.materialise(clone) { val _ = built.incrementAndGet() }
    assertEquals(built.get, 0)
    assert(kept.isFile)

  test("a clone whose build fails leaves no completion marker, so the next build fetches it again"):
    val clone = root / "clones" / "failed"
    val error = intercept[IllegalStateException](SNXPlugin.materialise(clone) {
      val _ = written(clone / "git" / "HEAD", "ref: refs/heads/main")
      throw new IllegalStateException("fetch failed") // scalafix:ok DisableSyntax.throw
    })
    assertEquals(error.getMessage, "fetch failed")
    assert(!(clone / SNXPlugin.completionMarker).exists)
    val built = new java.util.concurrent.atomic.AtomicInteger
    val _ = SNXPlugin.materialise(clone) { val _ = built.incrementAndGet() }
    assertEquals(built.get, 1)

  test("removing a directory that is not writable deletes what it holds"):
    val locked = mkdirs(root / "removal" / "locked")
    val held = written(locked / "inner.txt", "held")
    val _ = locked.setWritable(false)
    SNXPlugin.remove(locked)
    assert(!locked.exists, s"$locked is removed, $held with it")

  test("a clone that cannot be deleted fails the build with DeleteFailed naming it"):
    val parent = mkdirs(root / "removal" / "unwritable-parent")
    val clone = mkdirs(parent / "clone")
    val _ = written(clone / "git" / "HEAD", "ref: refs/heads/main")
    val _ = parent.setWritable(false)
    try
      assume(!Files.isWritable(parent.toPath), s"permissions do not stop this process writing $parent")
      val error = intercept[SNXError.DeleteFailed](SNXPlugin.materialise(clone)(()))
      assert(clue(error.getMessage).contains(clone.getAbsolutePath))
      assert(clone.exists)
    finally
      val _ = parent.setWritable(true)

  test("removing a clone deletes a link it holds, never what the link points to"):
    val outside = mkdirs(root / "removal" / "outside")
    val kept = written(outside / "kept.txt", "the developer's own file")
    val clone = mkdirs(root / "removal" / "linking-clone")
    val _ = linked(clone / "out", outside)
    val _ = outside.setWritable(false)
    try
      val _ = SNXPlugin.materialise(clone)(())
      assert(!(clone / "out").exists && !Files.isSymbolicLink((clone / "out").toPath), "the link itself is deleted")
      assert(kept.isFile, s"$kept, outside the clone, is kept")
      assert(!Files.isWritable(outside.toPath), s"$outside is not made writable through the link")
    finally
      val _ = outside.setWritable(true)

  test("an error named for its library is not named again"):
    val once = SNXError.forLibrary("lib", SNXError.DeleteFailed("snx: could not delete '/x'"))
    assertEquals(SNXError.forLibrary("lib", once).getMessage, "snx: vendored library 'lib': could not delete '/x'")
end RecipeSuite
