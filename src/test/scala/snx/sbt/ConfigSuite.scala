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

import scala.scalanative.build.NativeConfig

import snx.ABI
import snx.Arch
import snx.NativeRuntime
import snx.SNXError

class ConfigSuite extends munit.FunSuite:

  private val glibc = NativeRuntime.Linux(Arch.X86_64, ABI.Glibc)
  private val darwin = NativeRuntime.Darwin(Arch.Aarch64)

  test("crossStrip drops the prefixed host search paths only when cross-targeting"):
    val compile = Seq("-I/usr/local/include", "-Qunused-arguments", "-I/opt/include")
    assertEquals(SNXPlugin.crossStrip(compile, "-I", cross = true), Seq("-Qunused-arguments"))
    assertEquals(SNXPlugin.crossStrip(compile, "-I", cross = false), compile)

  test("crossStrip strips only the given prefix"):
    val linking = Seq("-L/usr/local/lib", "-lpthread")
    assertEquals(SNXPlugin.crossStrip(linking, "-L", cross = true), Seq("-lpthread"))

  test("Usage.render renders libraries, defines, whole-archive, and raw flags into a contribution"):
    val usage = Usage(Seq("pthread"), Nil, Seq("foo"), Seq("USE=1"), Seq("-z,now"), false)
    val config = Contribution.merge(NativeConfig.empty, Usage.render(usage, glibc))
    assert(config.linkingOptions.contains("-lpthread"))
    assert(config.compileOptions.contains("-DUSE=1"))
    assert(config.linkingOptions.contains("-z,now"))
    assert(config.linkingOptions.containsSlice(Seq("-Wl,--whole-archive", "-lfoo", "-Wl,--no-whole-archive")))

  test("Usage.render emits frameworks only on macOS"):
    assert(
      Contribution
        .merge(NativeConfig.empty, Usage.render(Usage.frameworks("Security"), darwin))
        .linkingOptions
        .containsSlice(Seq("-framework", "Security")))
    assert(!Contribution.merge(NativeConfig.empty, Usage.render(Usage.frameworks("Security"), glibc)).linkingOptions.contains("-framework"))

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
