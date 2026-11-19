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

import sbt.librarymanagement.Configurations

import scala.scalanative.build.Mode
import scala.scalanative.build.NativeConfig

import snx.ABI
import snx.Arch
import snx.NativeRuntime
import snx.OS
import snx.SNXError
import snx.TargetPlatform

class ConfigSuite extends munit.FunSuite:

  private val glibc = NativeRuntime.Linux(Arch.X86_64, ABI.Glibc)
  private val darwin = NativeRuntime.Darwin(Arch.Aarch64)
  private val msvc = NativeRuntime.Windows(Arch.X86_64, ABI.Msvc)
  private val mingw = NativeRuntime.Windows(Arch.X86_64, ABI.MinGw)
  private val mainConfigs = Set("compile", "runtime")
  private val testConfigs = Set("compile", "runtime", "test")

  test("visible scopes a library by its configurations: unscoped is everywhere, `% Test` is the test link only"):
    val plain = NativeLibrary("z")
    val testOnly = NativeLibrary("helper") % Configurations.Test
    val compound = NativeLibrary("both") % "compile,test"
    assert(SNXPlugin.visible(plain, mainConfigs) && SNXPlugin.visible(plain, testConfigs), "unscoped is visible everywhere")
    assert(!SNXPlugin.visible(testOnly, mainConfigs), "a `% Test` library is not visible at the main link")
    assert(SNXPlugin.visible(testOnly, testConfigs), "a `% Test` library is visible at the test link")
    assert(
      SNXPlugin.visible(compound, mainConfigs) && SNXPlugin.visible(compound, testConfigs),
      "a compound config is visible where it names")

  test("crossStrip drops the prefixed host search paths only when cross-targeting"):
    val compile = Seq("-I/usr/local/include", "-Qunused-arguments", "-I/opt/include")
    assertEquals(SNXPlugin.crossStrip(compile, "-I", cross = true), Seq("-Qunused-arguments"))
    assertEquals(SNXPlugin.crossStrip(compile, "-I", cross = false), compile)

  test("crossStrip strips only the given prefix"):
    val linking = Seq("-L/usr/local/lib", "-lpthread")
    assertEquals(SNXPlugin.crossStrip(linking, "-L", cross = true), Seq("-lpthread"))

  test("compileSearchOptions puts user -I first and demotes discovered -I to -idirafter on a host build"):
    val discovered = Seq("-I/opt/homebrew/include", "-I/usr/local/include", "-Qunused-arguments")
    val user = Seq(new java.io.File("vendor/include"))
    val userFlag = "-I" + user.head.getAbsolutePath
    // -I always beats -idirafter, so a user (and, appended later, a vendored) -I out-ranks the discovered
    // package-manager prefixes; the non-`-I` residual is preserved.
    assertEquals(
      SNXPlugin.compileSearchOptions(discovered, user, cross = false),
      Seq(userFlag, "-Qunused-arguments", "-idirafter", "/opt/homebrew/include", "-idirafter", "/usr/local/include")
    )

  test("compileSearchOptions drops the discovered host -I entirely when cross-targeting, keeping user -I and residual"):
    val discovered = Seq("-I/opt/homebrew/include", "-Qunused-arguments")
    val user = Seq(new java.io.File("vendor/include"))
    val userFlag = "-I" + user.head.getAbsolutePath
    assertEquals(SNXPlugin.compileSearchOptions(discovered, user, cross = true), Seq(userFlag, "-Qunused-arguments"))

  test("ownRequirements maps native libraries to channels by mode and adds the flags residual"):
    val libraries = Seq(NativeLibrary("a"), NativeLibrary("b").wholeArchive, NativeLibrary.framework("Sec"))
    assertEquals(
      SNXPlugin.ownRequirements(libraries, Flags.defines("X") ++ Flags.multithreaded),
      Usage(Seq("a"), Seq("Sec"), Seq("b"), Seq("X"), Nil, true))

  test("requireProvisioned fails a no-system-default library left System-provisioned, accepts a defaulted or provisioned one"):
    val _ = intercept[SNXError.UnprovisionedLibrary](SNXPlugin.requireProvisioned(Seq(NativeLibrary("foo").noSystemDefault)))
    SNXPlugin.requireProvisioned(Seq(NativeLibrary("foo")))
    SNXPlugin.requireProvisioned(Seq(NativeLibrary("foo", Vendored.local("v").cmake("x")).noSystemDefault))

  // The mis-bind this guards is silent: an unmatched name leaves the inherited requirement on its `-l<name>` default
  // and links the misnamed library beside it, so only the declared intent distinguishes a typo from a new library.
  test("requireInherited accepts a declaration a dependency requires, in any channel, and rejects one nothing requires"):
    val vendored = Vendored.local("v").cmake("x")
    val plain = Usage(Seq("ssl"), Nil, Nil, Nil, Nil, false)
    SNXPlugin.requireInherited(Seq(NativeLibrary.inherited("ssl", vendored)), plain)
    SNXPlugin.requireInherited(Seq(NativeLibrary.inherited("Sec", Provisioning.Unmanaged)), Usage(Nil, Seq("Sec"), Nil, Nil, Nil, false))
    SNXPlugin.requireInherited(Seq(NativeLibrary.inherited("w", vendored)), Usage(Nil, Nil, Seq("w"), Nil, Nil, false))
    // An undeclared library is unconstrained: a project's own libraries need not answer any inherited requirement.
    SNXPlugin.requireInherited(Seq(NativeLibrary("sssl", vendored)), plain)
    val typo = intercept[SNXError.UnmatchedLibrary](SNXPlugin.requireInherited(Seq(NativeLibrary.inherited("sssl", vendored)), plain))
    assert(clue(typo.toString).contains("sssl"))
    assert(clue(typo.toString).contains("ssl"))
    val none = intercept[SNXError.UnmatchedLibrary](SNXPlugin.requireInherited(Seq(NativeLibrary.inherited("ssl", vendored)), Usage.empty))
    assert(clue(none.toString).contains("no dependency requires a native library"))

  // Verbatim output from each linker the plugin drives. Scala Native reports a failed link as "Failed to link <path>",
  // naming the output rather than the cause, so these lines are the only place the missing library is named - which
  // makes the exact wording load-bearing, and it varies by runner as well as by toolchain. The two Windows/MSVC cells
  // disagree: x86_64 links through link.exe and aarch64 through lld-link, and an earlier pattern written from recalled
  // documentation (LNK1181) matched neither, so attribution silently did nothing on both.
  test("unresolvedLibraries recognises an unresolved library in every linker's wording, and nothing else"):
    val reported = Seq(
      "/usr/bin/ld.bfd: cannot find -lfoo: No such file or directory" -> Seq("foo"),
      "ld.lld: error: unable to find library -lcrypto" -> Seq("crypto"),
      "ld: library not found for -lssl" -> Seq("ssl"),
      "ld: library 'z' not found" -> Seq("z"),
      "LINK : fatal error LNK1104: cannot open file 'snx_modifier_absent.lib'" -> Seq("snx_modifier_absent"),
      "lld-link: error: could not open 'snx_propagated_absent.lib': no such file or directory" -> Seq("snx_propagated_absent"),
      "LINK : fatal error LNK1181: cannot open input file 'sqlite3.lib'" -> Seq("sqlite3")
    )
    reported.foreach: (line, expected) =>
      assertEquals(SNXPlugin.unresolvedLibraries(Seq(line)), expected, line)
    // Unrelated diagnostics must stay silent: speaking on a misparse would misdirect the reader.
    assertEquals(SNXPlugin.unresolvedLibraries(Seq("undefined reference to `foo'", "Failed to link /tmp/out", "")), Nil)
    // One failure repeats the same name across several lines; the reader wants it once.
    assertEquals(SNXPlugin.unresolvedLibraries(Seq("cannot find -lfoo", "cannot find -lfoo")), Seq("foo"))

  // Which stream carries a fatal linker error is the linker's choice, and Scala Native routes stdout to info and
  // stderr to error - so severity cannot be used to decide what is worth retaining. link.exe writes LNK1104 to
  // stdout, arriving as info; retaining only the error channel lost it entirely and attribution did nothing on that
  // runner while working on its sibling.
  test("the recording logger retains every level above debug, so a diagnosis on stdout is not lost"):
    val diagnostics = Seq.newBuilder[String]
    val logger = SNXPlugin.recordingLogger(sbt.util.Logger.Null, diagnostics)
    logger.info("LINK : fatal error LNK1104: cannot open file 'foo.lib'")
    logger.warn("a warning")
    logger.error("lld-link: error: could not open 'bar.lib': no such file or directory")
    logger.debug("noise no diagnosis arrives on")
    val retained = diagnostics.result()
    assertEquals(retained.size, 3, clue(retained).toString)
    assertEquals(SNXPlugin.unresolvedLibraries(retained), Seq("foo", "bar"))

  test("unresolvedMessage leads with the descriptor provenance for an inherited name, and gives the remedy either way"):
    val inherited = SNXPlugin.unresolvedMessage(Seq("ssl"), Set("ssl"))
    assert(clue(inherited).contains("required by a resolved dependency's descriptor"))
    assert(clue(inherited).contains("SNX.libraries"))
    val own = SNXPlugin.unresolvedMessage(Seq("ssl"), Set.empty)
    assert(!clue(own).contains("required by a resolved dependency"))
    assert(clue(own).contains("SNX.libraries"))

  // Resolution takes the operating system from the target and the ABI from the triple, so neither catches the two
  // disagreeing. `gnu` is legal for Linux AND Windows, so an unchecked contradiction resolved to a plausible wrong
  // answer - a Linux target reading a MinGW triple yielded glibc - and every platform-keyed decision after it was then
  // made for the wrong platform.
  test("requireAgreement rejects a target whose operating system the toolchain's triple contradicts"):
    val compiler = java.nio.file.Paths.get("/usr/bin/clang").nn
    val onLinux = TargetPlatform(OS.Linux, Arch.X86_64)
    val onWindows = TargetPlatform(OS.Windows, Arch.X86_64)
    val onDarwin = TargetPlatform(OS.Darwin, Arch.Aarch64)
    // Agreement in every real form, including the classic MinGW triple that names Windows from the OS slot.
    SNXPlugin.requireAgreement(onLinux, "x86_64-suse-linux", compiler)
    SNXPlugin.requireAgreement(onLinux, "x86_64-alpine-linux-musl", compiler)
    SNXPlugin.requireAgreement(onWindows, "x86_64-w64-mingw32", compiler)
    SNXPlugin.requireAgreement(onWindows, "x86_64-pc-windows-msvc", compiler)
    SNXPlugin.requireAgreement(onDarwin, "arm64-apple-darwin24", compiler)
    // A triple naming no operating system we know contradicts nothing, so it stays silent and later steps decide.
    SNXPlugin.requireAgreement(onLinux, "wasm32-unknown-unknown", compiler)
    val crossed = intercept[SNXError.TargetMismatch](SNXPlugin.requireAgreement(onLinux, "x86_64-w64-windows-gnu", compiler))
    assert(clue(crossed.toString).contains("Linux"))
    assert(clue(crossed.toString).contains("Windows"))
    assert(clue(crossed.toString).contains("x86_64-w64-windows-gnu"))
    val _ = intercept[SNXError.TargetMismatch](SNXPlugin.requireAgreement(onWindows, "x86_64-pc-linux-gnu", compiler))
    intercept[SNXError.TargetMismatch](SNXPlugin.requireAgreement(onDarwin, "x86_64-pc-linux-gnu", compiler))

  test("requireLinkageApplicable rejects a resolving linkage on an Unmanaged library, ignoring a non-matching one and other provisionings"):
    val unmanagedStatic = NativeLibrary("x", Provisioning.Unmanaged).linkage { case _ => Linkage.Static }
    val _ = intercept[SNXError.UnsupportedLinkage](SNXPlugin.requireLinkageApplicable(Seq(unmanagedStatic), glibc))
    val unmanagedLinuxOnly = NativeLibrary("x", Provisioning.Unmanaged).linkage { case NativeRuntime.Linux(_, _) => Linkage.Static }
    SNXPlugin.requireLinkageApplicable(Seq(unmanagedLinuxOnly), darwin)
    SNXPlugin.requireLinkageApplicable(Seq(NativeLibrary("z").linkage { case _ => Linkage.Static }), glibc)

  private val archive = new java.io.File("/x/libfoo.a")
  private val archivePath = archive.getAbsolutePath.nn
  private val shared = new java.io.File("/x/lib/libfoo.so")
  private val sharedDir = shared.getParentFile.nn.getAbsolutePath.nn
  // Mirrors a real backend: a Static request yields an archive, a Dynamic one a shared library; `true` = built this run.
  private val stub: (Vendored, Linkage) => (Artefacts, Boolean) =
    (_, linkage) => (if linkage == Linkage.Static then Artefacts(Seq(archive), Seq.empty) else Artefacts(Seq(shared), Seq.empty), true)
  private def vendored = NativeLibrary("foo", Vendored.local("vendor/foo").cmake("foo"))

  test("the rebind renders an unprovisioned default -l, replaces a vendored name with its archive, keeps link order"):
    val requirements = Usage(Seq("a", "foo", "b"), Nil, Nil, Nil, Nil, false)
    val (config, _) = SNXPlugin.rebind(NativeConfig.empty, requirements, Map("foo" -> vendored), glibc, stub)
    assertEquals(config.linkingOptions, Seq("-la", archivePath, "-lb"))

  test("the rebind reports whether a vendored library was built this run or served from the cache"):
    val requirements = Usage(Seq("foo"), Nil, Nil, Nil, Nil, false)
    val cached: (Vendored, Linkage) => (Artefacts, Boolean) = (_, _) => (Artefacts(Seq(archive), Seq.empty), false)
    val (_, builtReport) = SNXPlugin.rebind(NativeConfig.empty, requirements, Map("foo" -> vendored), glibc, stub)
    val (_, cachedReport) = SNXPlugin.rebind(NativeConfig.empty, requirements, Map("foo" -> vendored), glibc, cached)
    assert(builtReport.exists(_.contains("vendored, built")), s"expected a 'built' report, got $builtReport")
    assert(cachedReport.exists(_.contains("vendored, cache hit")), s"expected a 'cache hit' report, got $cachedReport")

  test("the rebind whole-archives a vendored archive in WholeArchive mode and the platform's linker syntax"):
    val requirements = Usage(Nil, Nil, Seq("foo"), Nil, Nil, false)
    val (config, _) = SNXPlugin.rebind(NativeConfig.empty, requirements, Map("foo" -> vendored), glibc, stub)
    assertEquals(config.linkingOptions, Seq("-Wl,--whole-archive", archivePath, "-Wl,--no-whole-archive"))

  test("the rebind suppresses an Unmanaged library's default and renders an unclaimed name's default"):
    val requirements = Usage(Seq("compiled_in", "sys"), Nil, Nil, Nil, Nil, false)
    val library = NativeLibrary("compiled_in", Provisioning.Unmanaged)
    val (config, _) = SNXPlugin.rebind(NativeConfig.empty, requirements, Map("compiled_in" -> library), glibc, stub)
    assertEquals(config.linkingOptions, Seq("-lsys"))

  test("provisioningDefault derives the linkage a provisioning defaults to: System dynamic, Vendored static"):
    assertEquals(SNXPlugin.provisioningDefault(Provisioning.System), Linkage.Dynamic)
    assertEquals(SNXPlugin.provisioningDefault(Provisioning.Vendored(Vendored.local("v").cmake("x"))), Linkage.Static)

  test("the rebind brackets a static system library with -Bstatic on GNU, keeping the libc default dynamic"):
    val requirements = Usage(Seq("z"), Nil, Nil, Nil, Nil, false)
    val library = NativeLibrary("z").linkage { case _ => Linkage.Static }
    val (config, _) = SNXPlugin.rebind(NativeConfig.empty, requirements, Map("z" -> library), glibc, stub)
    assertEquals(config.linkingOptions, Seq("-Wl,-Bstatic", "-lz", "-Wl,-Bdynamic"))

  test("a per-platform library linkage brackets where it matches static and falls to the provisioning default elsewhere"):
    val requirements = Usage(Seq("z"), Nil, Nil, Nil, Nil, false)
    val library = NativeLibrary("z").linkage { case NativeRuntime.Linux(_, _) => Linkage.Static }
    val (onGlibc, _) = SNXPlugin.rebind(NativeConfig.empty, requirements, Map("z" -> library), glibc, stub)
    assertEquals(onGlibc.linkingOptions, Seq("-Wl,-Bstatic", "-lz", "-Wl,-Bdynamic"), "Linux matched: static bracket")
    val (onDarwin, _) = SNXPlugin.rebind(NativeConfig.empty, requirements, Map("z" -> library), darwin, stub)
    assertEquals(onDarwin.linkingOptions, Seq("-lz"), "Darwin unmatched: falls to the System dynamic default")

  test("systemLink renders the per-platform static form: GNU and MinGW bracket; MSVC and macOS fail fast"):
    assertEquals(SNXPlugin.systemLink(glibc, LinkMode.Plain, Linkage.Static, "z"), Seq("-Wl,-Bstatic", "-lz", "-Wl,-Bdynamic"))
    assertEquals(SNXPlugin.systemLink(mingw, LinkMode.Plain, Linkage.Static, "z"), Seq("-Wl,-Bstatic", "-lz", "-Wl,-Bdynamic"))
    // MSVC has no -Bstatic (static selection is by lib name), macOS cannot force a -l static; neither is a silent no-op.
    val _ = intercept[SNXError.UnsupportedLinkage](SNXPlugin.systemLink(msvc, LinkMode.Plain, Linkage.Static, "z"))
    intercept[SNXError.UnsupportedLinkage](SNXPlugin.systemLink(darwin, LinkMode.Plain, Linkage.Static, "z"))

  test("a static whole-archive system library brackets the whole-archive inside -Bstatic on GNU"):
    assertEquals(
      SNXPlugin.systemLink(glibc, LinkMode.WholeArchive, Linkage.Static, "foo"),
      Seq("-Wl,-Bstatic", "-Wl,--whole-archive", "-lfoo", "-Wl,--no-whole-archive", "-Wl,-Bdynamic")
    )

  test("a static macOS framework fails fast - a framework cannot be linked statically"):
    val _ = intercept[SNXError.UnsupportedLinkage](SNXPlugin.systemLink(glibc, LinkMode.Framework, Linkage.Static, "Accelerate"))

  test("a dynamically-linked vendored library renders like a system dynamic link plus its build search path"):
    val requirements = Usage(Seq("foo"), Nil, Nil, Nil, Nil, false)
    val dynamicVendored = vendored.linkage { case _ => Linkage.Dynamic }
    val (config, _) = SNXPlugin.rebind(NativeConfig.empty, requirements, Map("foo" -> dynamicVendored), glibc, stub)
    assertEquals(config.linkingOptions, Seq("-lfoo", "-L" + sharedDir, "-Wl,-rpath," + sharedDir))

  test("a whole-archive vendored library cannot be linked dynamically - whole-archive is a static-archive operation"):
    val requirements = Usage(Nil, Nil, Seq("foo"), Nil, Nil, false)
    val library = vendored.wholeArchive.linkage { case _ => Linkage.Dynamic }
    intercept[SNXError.UnsupportedLinkage](SNXPlugin.rebind(NativeConfig.empty, requirements, Map("foo" -> library), glibc, stub))

  test("a dynamically-linked vendored library on Windows fails fast - DLL redistribution is a follow-on"):
    val requirements = Usage(Seq("foo"), Nil, Nil, Nil, Nil, false)
    val dynamicVendored = vendored.linkage { case _ => Linkage.Dynamic }
    intercept[SNXError.UnsupportedLinkage](SNXPlugin.rebind(NativeConfig.empty, requirements, Map("foo" -> dynamicVendored), msvc, stub))

  private val second = new java.io.File("/x/libbar.a")
  private val secondPath = second.getAbsolutePath.nn
  private val group = Seq("-Wl,--start-group", archivePath, secondPath, "-Wl,--end-group")

  test("archiveLink groups two or more archives on Linux and MinGW, in Plain and Framework mode, and not one"):
    assertEquals(SNXPlugin.archiveLink(glibc, LinkMode.Plain, Seq(archive, second)), group)
    assertEquals(SNXPlugin.archiveLink(mingw, LinkMode.Plain, Seq(archive, second)), group)
    assertEquals(SNXPlugin.archiveLink(glibc, LinkMode.Framework, Seq(archive, second)), group)
    assertEquals(SNXPlugin.archiveLink(glibc, LinkMode.Plain, Seq(archive)), Seq(archivePath))
    assertEquals(SNXPlugin.archiveLink(mingw, LinkMode.Plain, Seq(archive)), Seq(archivePath))

  test("archiveLink takes several archives ungrouped on macOS and MSVC"):
    assertEquals(SNXPlugin.archiveLink(darwin, LinkMode.Plain, Seq(archive, second)), Seq(archivePath, secondPath))
    assertEquals(SNXPlugin.archiveLink(msvc, LinkMode.Plain, Seq(archive, second)), Seq(archivePath, secondPath))

  test("archiveLink whole-archives each archive on its own, with no group, on every runtime"):
    assertEquals(
      SNXPlugin.archiveLink(glibc, LinkMode.WholeArchive, Seq(archive, second)),
      Seq("-Wl,--whole-archive", archivePath, "-Wl,--no-whole-archive", "-Wl,--whole-archive", secondPath, "-Wl,--no-whole-archive")
    )
    Seq(mingw, darwin, msvc).foreach: runtime =>
      val each = Seq(archivePath, secondPath).flatMap(Modifier.wholeArchivePath(runtime, _))
      assertEquals(SNXPlugin.archiveLink(runtime, LinkMode.WholeArchive, Seq(archive, second)), each, runtime.toString)

  test("the rebind links a vendored library's several archives as one group, its closure after the group"):
    val requirements = Usage(Seq("foo"), Nil, Nil, Nil, Nil, false)
    val library = NativeLibrary("foo", Vendored.local("vendor/foo").cmake("foo").options { case _ => Flags.libraries("m") })
    val pair: (Vendored, Linkage) => (Artefacts, Boolean) = (_, _) => (Artefacts(Seq(archive, second), Seq.empty), true)
    val (config, _) = SNXPlugin.rebind(NativeConfig.empty, requirements, Map("foo" -> library), glibc, pair)
    assertEquals(config.linkingOptions, group :+ "-lm")

  test("collected keeps a CMake install's archives, sorted by their path under the prefix, whatever the input order"):
    val prefix = new java.io.File("/staging/prefix")
    def under(paths: String*) = paths.map(new java.io.File(prefix, _))
    val found = under("lib/libzz_top.a", "lib64/libmm_mid.a", "include/multi.h", "lib/libaa_base.a", "lib/libshared.so")
    assertEquals(Backend.collected(glibc, Linkage.Static, prefix, found), under("lib/libaa_base.a", "lib/libzz_top.a", "lib64/libmm_mid.a"))
    assertEquals(Backend.collected(glibc, Linkage.Dynamic, prefix, found), under("lib/libshared.so"))
    val msvcFound = under("lib/zz_top.lib", "lib/libaa_base.a", "bin/zz_top.dll", "lib/aa_base.lib")
    assertEquals(Backend.collected(msvc, Linkage.Static, prefix, msvcFound), under("lib/aa_base.lib", "lib/zz_top.lib"))

  private val manifestDir = new java.io.File("target/snx-manifest-test")
  private val installPrefix = new java.io.File(manifestDir, "prefix")
  private def manifest(name: String, paths: java.io.File*): java.io.File =
    val file = new java.io.File(manifestDir, name)
    // CMake ends the manifest without a final newline.
    sbt.io.IO.write(file, paths.map(_.getAbsolutePath).mkString("\n"))
    file

  test("requireInstalled passes a CMake install manifest whose every path lies under the staging prefix"):
    val inside = Seq(new java.io.File(installPrefix, "lib/libfoo.a"), new java.io.File(installPrefix, "share/man/man3/foo.3"))
    Backend.requireInstalled(manifest("inside.txt", inside*), installPrefix)

  test("requireInstalled fails naming each installed path outside the prefix, read through its normal form"):
    val inside = new java.io.File(installPrefix, "lib/libfoo.a")
    val sibling = new java.io.File(manifestDir, "prefix-other/foo.3")
    val climbed = new java.io.File(installPrefix, "../escape.txt")
    val error =
      intercept[SNXError.OutputOutsideStaging](Backend.requireInstalled(manifest("outside.txt", inside, sibling, climbed), installPrefix))
    assert(clue(error.toString).contains(sibling.getAbsolutePath))
    assert(clue(error.toString).contains(climbed.getAbsolutePath))
    assert(!clue(error.toString).contains(inside.getAbsolutePath))

  test("requireInstalled reads the manifest's unterminated last line"):
    val last = new java.io.File(manifestDir, "outside/foo.1")
    val error = intercept[SNXError.OutputOutsideStaging](
      Backend.requireInstalled(manifest("last.txt", new java.io.File(installPrefix, "lib/libfoo.a"), last), installPrefix))
    assert(clue(error.toString).contains(last.getAbsolutePath))

  test("requireInstalled fails when the install left no manifest to check"):
    intercept[SNXError.CMakeBuildFailed](Backend.requireInstalled(new java.io.File(manifestDir, "absent.txt"), installPrefix))

  test("projectDirectory accepts a directory inside the vendored source and gives its normal form"):
    assertEquals(Backend.projectDirectory("."), java.nio.file.Paths.get("").nn)
    assertEquals(Backend.projectDirectory("build/cmake"), java.nio.file.Paths.get("build", "cmake").nn)
    assertEquals(Backend.projectDirectory("build/../build/cmake"), java.nio.file.Paths.get("build", "cmake").nn)

  test("projectDirectory refuses an absolute directory and one that climbs out of the vendored source"):
    Seq("/x", "../x", "build/../../x").foreach: directory =>
      val error = intercept[SNXError.DirectoryOutsideSource](Backend.projectDirectory(directory))
      assert(clue(error.toString).contains(s"'$directory'"))

  test("projectDirectory refuses the Windows rooted, drive-absolute and drive-relative forms"):
    assume(java.io.File.separatorChar == '\\', "java.nio gives these forms a root only under Windows path semantics")
    Seq("\\x", "C:\\x", "C:x").foreach: directory =>
      intercept[SNXError.DirectoryOutsideSource](Backend.projectDirectory(directory))

  private def cmakeKey(library: Vendored): Seq[String] = library.backend.cacheKey(glibc)

  test("the CMake cache key changes with the project directory, and equivalent spellings share one key"):
    val base = Vendored.local("v").cmake("t")
    assertNotEquals(cmakeKey(base), cmakeKey(base.directory("build/cmake")))
    assertEquals(cmakeKey(base.directory("build/cmake")), cmakeKey(base.directory("build/../build/cmake")))
    assertEquals(cmakeKey(base), cmakeKey(base.directory("./")))

  test("the CMake setters chain in any order, each replacing only its own setting"):
    val modules = new java.io.File("cmake-modules")
    val library = Vendored
      .local("v")
      .cmake("a", "b")
      .options { case _ => Flags.libraries("m") }
      .directory("first")
      .configure { case NativeRuntime.Linux(_, _) => Seq("-DX=ON") }
      .moduleOverrides(modules)
      .directory("build/cmake")
    library.backend match
      case cmake: Backend.CMake =>
        assertEquals(cmake.targets, Seq("a", "b"))
        assertEquals(cmake.directory, "build/cmake")
        assertEquals(cmake.moduleOverrides, Some(modules))
        assertEquals(cmake.flags.applyOrElse(glibc, (_: NativeRuntime) => Nil), Seq("-DX=ON"))
        assert(!cmake.flags.isDefinedAt(darwin))
      case Backend.Command(_, _) => fail("a CMake library carries a CMake backend")
    assertEquals(library.closureFor(glibc), Flags.libraries("m"))

  test("a CMake setter takes the directory as a path string and is not offered on a command build"):
    val file = compiletime.testing.typeCheckErrors("""Vendored.local("v").cmake("x").directory(new java.io.File("build/cmake"))""")
    assert(file.exists(_.message.contains("java.io.File")), file.map(_.message).mkString("\n"))
    val command = compiletime.testing.typeCheckErrors("""Vendored.local("v").command("t")(_ => Artefacts.empty).directory("build/cmake")""")
    assert(command.exists(_.message.contains("directory is not a member of snx.sbt.Vendored")), command.map(_.message).mkString("\n"))

  test("enforceMultithreading forces multithreading on, leaves it untouched, or fails when the project disabled it"):
    assertEquals(SNXPlugin.enforceMultithreading(NativeConfig.empty, required = true, None).multithreading, Some(true))
    assertEquals(
      SNXPlugin.enforceMultithreading(NativeConfig.empty, required = false, None).multithreading,
      NativeConfig.empty.multithreading)
    intercept[SNXError.MultithreadingRequired](SNXPlugin.enforceMultithreading(NativeConfig.empty, required = true, Some(false)))

  test("requireStaged accepts outputs under the staging directory and rejects those outside it"):
    val staging = new java.io.File("target/snx-staging-test")
    SNXPlugin.requireStaged(Seq(new java.io.File(staging, "prefix/lib/libanswer.a")), staging)
    intercept[SNXError.OutputOutsideStaging](SNXPlugin.requireStaged(Seq(new java.io.File("vendor/answer/include")), staging))

  test("contentDigest hashes an enumerated file set, and fails when a listed file is gone by the time it is read"):
    val directory = new java.io.File("target/snx-digest-test")
    val present = new java.io.File(directory, "answer.c")
    sbt.io.IO.write(present, "int answer(void) { return 42; }")
    assert(Vendored.contentDigest(directory, Seq(present)).startsWith("answer.c:"))
    // The enumerate-then-read window: a file the walk listed and the read no longer finds. Skipping it would digest a
    // file set that never existed on disk, which can collide with one that did and serve a stale cached archive.
    val vanished = new java.io.File(directory, "removed.c")
    intercept[SNXError.SourceChanged](Vendored.contentDigest(directory, Seq(present, vanished)))

  test("cmakeBuildType maps each Scala Native mode name to its CMake build type - debug and size distinct, releases Release"):
    // Driven by Scala Native's real Mode values: the mapping matches on Mode.name strings, so this pins that coupling -
    // a wrong/renamed name would silently fall through to "Release" (CMake does not error on an unknown build type).
    assertEquals(Backend.cmakeBuildType(Mode.debug), "Debug")
    assertEquals(Backend.cmakeBuildType(Mode.releaseFast), "Release")
    assertEquals(Backend.cmakeBuildType(Mode.releaseFull), "Release")
    assertEquals(Backend.cmakeBuildType(Mode.releaseSize), "MinSizeRel")

  test("compilerPin pins the CMake C/C++ compiler through CC and CXX on Linux and macOS, and leaves CMake's default detection on Windows"):
    val clang = new java.io.File("bin/clang")
    val clangPP = new java.io.File("bin/clang++")
    val pin = Seq("CC" -> clang.getAbsolutePath.nn, "CXX" -> clangPP.getAbsolutePath.nn)
    // Linux/macOS: the vendored C is built by the same clang the SN link uses (honouring SNX.clang). Windows: MSVC is
    // CMake's default and ABI-compatible with the clang-windows-msvc link, so it is left to CMake's own detection.
    assertEquals(Backend.compilerPin(glibc, clang, clangPP), pin, "Linux pins the resolved clang")
    assertEquals(Backend.compilerPin(darwin, clang, clangPP), pin, "macOS pins the resolved clang")
    assertEquals(Backend.compilerPin(msvc, clang, clangPP), Seq.empty[(String, String)], "MSVC leaves CMake's default (cl.exe)")
    assertEquals(Backend.compilerPin(mingw, clang, clangPP), Seq.empty[(String, String)], "MinGW (Windows) emits no pin")

  test("isFullSha recognises a full SHA-1/SHA-256 hex commit and rejects a tag, branch, short, uppercase, or non-hex ref"):
    assert(SNXPlugin.isFullSha("a" * 40), "40-hex is a SHA-1")
    assert(SNXPlugin.isFullSha("0" * 64), "64-hex is a SHA-256")
    assert(!SNXPlugin.isFullSha("v1.0.0"), "a tag is not a SHA")
    assert(!SNXPlugin.isFullSha("main"), "a branch is not a SHA")
    assert(!SNXPlugin.isFullSha("abc1234"), "a short hash is not a full SHA")
    assert(!SNXPlugin.isFullSha("A" * 40), "uppercase hex is not accepted - git SHAs are lowercase")
    assert(!SNXPlugin.isFullSha("g" * 40), "non-hex of length 40 is not a SHA")
end ConfigSuite
