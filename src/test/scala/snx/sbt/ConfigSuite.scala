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

import scala.scalanative.build.NativeConfig

import snx.ABI
import snx.Arch
import snx.NativeRuntime
import snx.SNXError

class ConfigSuite extends munit.FunSuite:

  private val glibc = NativeRuntime.Linux(Arch.X86_64, ABI.Glibc)
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

  test("ownRequirements maps native libraries to channels by mode and adds the flags residual"):
    val libraries = Seq(NativeLibrary("a"), NativeLibrary("b").wholeArchive, NativeLibrary.framework("Sec"))
    assertEquals(
      SNXPlugin.ownRequirements(libraries, Flags.defines("X") ++ Flags.multithreaded),
      Usage(Seq("a"), Seq("Sec"), Seq("b"), Seq("X"), Nil, true))

  test("requireProvisioned fails a no-system-default library left System-provisioned, accepts a defaulted or provisioned one"):
    val _ = intercept[SNXError.UnprovisionedLibrary](SNXPlugin.requireProvisioned(Seq(NativeLibrary("foo").noSystemDefault)))
    SNXPlugin.requireProvisioned(Seq(NativeLibrary("foo")))
    SNXPlugin.requireProvisioned(Seq(NativeLibrary("foo", Vendored.local("v").cmake("x")).noSystemDefault))

  private val stub: Vendored => Artefacts = _ => Artefacts(Seq(new java.io.File("/x/libfoo.a")), Seq.empty)
  private def vendored = Provisioning.Vendored(Vendored.local("vendor/foo").cmake("foo"))

  test("the rebind renders an unprovisioned default -l, replaces a vendored name with its archive, keeps link order"):
    val requirements = Usage(Seq("a", "foo", "b"), Nil, Nil, Nil, Nil, false)
    val (config, _) = SNXPlugin.rebind(NativeConfig.empty, requirements, Map("foo" -> vendored), glibc, stub)
    assertEquals(config.linkingOptions, Seq("-la", "/x/libfoo.a", "-lb"))

  test("the rebind whole-archives a vendored archive in WholeArchive mode and the platform's linker syntax"):
    val requirements = Usage(Nil, Nil, Seq("foo"), Nil, Nil, false)
    val (config, _) = SNXPlugin.rebind(NativeConfig.empty, requirements, Map("foo" -> vendored), glibc, stub)
    assertEquals(config.linkingOptions, Seq("-Wl,--whole-archive", "/x/libfoo.a", "-Wl,--no-whole-archive"))

  test("the rebind suppresses an Unmanaged library's default and renders an unclaimed name's default"):
    val requirements = Usage(Seq("compiled_in", "sys"), Nil, Nil, Nil, Nil, false)
    val (config, _) = SNXPlugin.rebind(NativeConfig.empty, requirements, Map("compiled_in" -> Provisioning.Unmanaged), glibc, stub)
    assertEquals(config.linkingOptions, Seq("-lsys"))

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
end ConfigSuite
