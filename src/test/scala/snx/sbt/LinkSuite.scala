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

import scala.scalanative.build.BuildTarget
import scala.scalanative.build.NativeConfig

import snx.ABI
import snx.Arch
import snx.NativeRuntime
import snx.SNXError
import snx.TargetPlatform

class LinkSuite extends munit.FunSuite:

  private val glibc = NativeRuntime.Linux(Arch.X86_64, ABI.Glibc)
  private val musl = NativeRuntime.Linux(Arch.X86_64, ABI.Musl)
  private val darwin = NativeRuntime.Darwin(Arch.Aarch64)
  private val msvc = NativeRuntime.Windows(Arch.X86_64, ABI.Msvc)

  test("the NIR deliverable is not linked"):
    intercept[SNXError.NotLinkable](SNXPlugin.resolveTarget(Deliverable.NIR, Linkage.Dynamic, glibc, Some("Main")))

  test("an Executable requires a main class"):
    intercept[SNXError.MissingMainClass](SNXPlugin.resolveTarget(Deliverable.Executable, Linkage.Dynamic, glibc, None))

  test("a dynamic Executable links as an application carrying its main class"):
    val (target, static, main) = SNXPlugin.resolveTarget(Deliverable.Executable, Linkage.Dynamic, glibc, Some("Main"))
    assert(target == BuildTarget.application)
    assertEquals(static, false)
    assertEquals(main, Some("Main"))

  test("a static Executable is rejected where the platform cannot link statically"):
    intercept[SNXError.StaticLinkingUnsupported](SNXPlugin.resolveTarget(Deliverable.Executable, Linkage.Static, glibc, Some("Main")))

  test("a static Executable links on a static-capable platform"):
    val (target, static, _) = SNXPlugin.resolveTarget(Deliverable.Executable, Linkage.Static, musl, Some("Main"))
    assert(target == BuildTarget.application)
    assertEquals(static, true)

  test("a Library links static or dynamic and carries no main class"):
    val (staticLib, _, staticMain) = SNXPlugin.resolveTarget(Deliverable.Library, Linkage.Static, darwin, None)
    assert(staticLib == BuildTarget.libraryStatic)
    assertEquals(staticMain, None)
    val (dynamicLib, _, _) = SNXPlugin.resolveTarget(Deliverable.Library, Linkage.Dynamic, darwin, None)
    assert(dynamicLib == BuildTarget.libraryDynamic)

  test("the test binary links as an application, honouring a static test linkage where the platform supports it"):
    val (target, static) = SNXPlugin.resolveTestTarget(Linkage.Static, musl)
    assert(target == BuildTarget.application)
    assertEquals(static, true)

  test("the test binary links dynamically under dynamic test linkage"):
    val (target, static) = SNXPlugin.resolveTestTarget(Linkage.Dynamic, glibc)
    assert(target == BuildTarget.application)
    assertEquals(static, false)

  test("a static test binary is rejected where the platform cannot link statically"):
    intercept[SNXError.StaticLinkingUnsupported](SNXPlugin.resolveTestTarget(Linkage.Static, glibc))

  test("cRuntimeStatic renders the C-runtime static flag per platform - musl link-only, MSVC compile and link"):
    val base = NativeConfig.empty
    val muslStatic = Contribution.merge(base, SNXPlugin.cRuntimeStatic(musl))
    assertEquals(muslStatic.linkingOptions, base.linkingOptions :+ "-static")
    assertEquals(muslStatic.compileOptions, base.compileOptions)
    val msvcStatic = Contribution.merge(base, SNXPlugin.cRuntimeStatic(msvc))
    assertEquals(msvcStatic.linkingOptions, base.linkingOptions :+ "-fms-runtime-lib=static")
    assertEquals(msvcStatic.compileOptions, base.compileOptions :+ "-fms-runtime-lib=static")

  test("cRuntimeStatic contributes exactly for the runtimes that support static linking"):
    val base = NativeConfig.empty
    TargetPlatform.all
      .flatMap(NativeRuntime.variants)
      .foreach: runtime =>
        val merged = Contribution.merge(base, SNXPlugin.cRuntimeStatic(runtime))
        val contributes = merged.linkingOptions != base.linkingOptions || merged.compileOptions != base.compileOptions
        assertEquals(contributes, runtime.supportsStaticLinking, s"cRuntimeStatic disagrees with supportsStaticLinking for $runtime")
end LinkSuite
