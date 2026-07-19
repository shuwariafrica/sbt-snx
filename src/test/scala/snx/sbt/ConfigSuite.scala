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
import snx.SNXError

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
  // Mirrors a real backend: a Static request yields an archive, a Dynamic one a shared library.
  private val stub: (Vendored, Linkage) => Artefacts =
    (_, linkage) => if linkage == Linkage.Static then Artefacts(Seq(archive), Seq.empty) else Artefacts(Seq(shared), Seq.empty)
  private def vendored = NativeLibrary("foo", Vendored.local("vendor/foo").cmake("foo"))

  test("the rebind renders an unprovisioned default -l, replaces a vendored name with its archive, keeps link order"):
    val requirements = Usage(Seq("a", "foo", "b"), Nil, Nil, Nil, Nil, false)
    val (config, _) = SNXPlugin.rebind(NativeConfig.empty, requirements, Map("foo" -> vendored), glibc, stub)
    assertEquals(config.linkingOptions, Seq("-la", archivePath, "-lb"))

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

  test("cmakeBuildType maps each Scala Native mode name to its CMake build type - debug and size distinct, releases Release"):
    // Driven by Scala Native's real Mode values: the mapping matches on Mode.name strings, so this pins that coupling -
    // a wrong/renamed name would silently fall through to "Release" (CMake does not error on an unknown build type).
    assertEquals(Backend.cmakeBuildType(Mode.debug), "Debug")
    assertEquals(Backend.cmakeBuildType(Mode.releaseFast), "Release")
    assertEquals(Backend.cmakeBuildType(Mode.releaseFull), "Release")
    assertEquals(Backend.cmakeBuildType(Mode.releaseSize), "MinSizeRel")

  test("compilerPin pins the CMake C/C++ compiler on Linux and macOS, and leaves CMake's default detection on Windows"):
    val clang = new java.io.File("bin/clang")
    val clangPP = new java.io.File("bin/clang++")
    val pin = Seq(s"-DCMAKE_C_COMPILER=${clang.getAbsolutePath}", s"-DCMAKE_CXX_COMPILER=${clangPP.getAbsolutePath}")
    // Linux/macOS: the vendored C is built by the same clang the SN link uses (honouring SNX.clang). Windows: MSVC is
    // CMake's default and ABI-compatible with the clang-windows-msvc link, so it is left to CMake's own detection.
    assertEquals(Backend.compilerPin(glibc, clang, clangPP), pin, "Linux pins the resolved clang")
    assertEquals(Backend.compilerPin(darwin, clang, clangPP), pin, "macOS pins the resolved clang")
    assertEquals(Backend.compilerPin(msvc, clang, clangPP), Seq.empty[String], "MSVC leaves CMake's default (cl.exe)")
    assertEquals(Backend.compilerPin(mingw, clang, clangPP), Seq.empty[String], "MinGW (Windows) emits no pin")

  test("isFullSha recognises a full SHA-1/SHA-256 hex commit and rejects a tag, branch, short, uppercase, or non-hex ref"):
    assert(SNXPlugin.isFullSha("a" * 40), "40-hex is a SHA-1")
    assert(SNXPlugin.isFullSha("0" * 64), "64-hex is a SHA-256")
    assert(!SNXPlugin.isFullSha("v1.0.0"), "a tag is not a SHA")
    assert(!SNXPlugin.isFullSha("main"), "a branch is not a SHA")
    assert(!SNXPlugin.isFullSha("abc1234"), "a short hash is not a full SHA")
    assert(!SNXPlugin.isFullSha("A" * 40), "uppercase hex is not accepted - git SHAs are lowercase")
    assert(!SNXPlugin.isFullSha("g" * 40), "non-hex of length 40 is not a SHA")
end ConfigSuite
