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
    intercept[SNXError.NotLinkable](SNXPlugin.resolveTarget(Deliverable.NIR, Some("Main")))

  test("an Executable requires a main class"):
    intercept[SNXError.MissingMainClass](SNXPlugin.resolveTarget(Deliverable.Executable, None))

  test("an Executable links as an application carrying its main class"):
    val (target, main) = SNXPlugin.resolveTarget(Deliverable.Executable, Some("Main"))
    assert(target == BuildTarget.application)
    assertEquals(main, Some("Main"))

  test("a Library resolves to its emit form and carries no main class"):
    val (staticLib, staticMain) = SNXPlugin.resolveTarget(Deliverable.Library.Static, None)
    assert(staticLib == BuildTarget.libraryStatic)
    assertEquals(staticMain, None)
    val (sharedLib, _) = SNXPlugin.resolveTarget(Deliverable.Library.Shared, None)
    assert(sharedLib == BuildTarget.libraryDynamic)

  test("SNX.staticRuntime renders the C-runtime static flag per platform - musl link-only, MSVC compile and link"):
    val base = NativeConfig.empty
    val muslStatic = SNXImports.SNX.staticRuntime(musl)(Native(base)).config
    assertEquals(muslStatic.linkingOptions, base.linkingOptions :+ "-static")
    assertEquals(muslStatic.compileOptions, base.compileOptions)
    val msvcStatic = SNXImports.SNX.staticRuntime(msvc)(Native(base)).config
    assertEquals(msvcStatic.linkingOptions, base.linkingOptions :+ "-fms-runtime-lib=static")
    assertEquals(msvcStatic.compileOptions, base.compileOptions :+ "-fms-runtime-lib=static")

  test("SNX.staticRuntime fails fast on a platform that cannot link a static C runtime"):
    val _ = intercept[SNXError.StaticLinkingUnsupported](SNXImports.SNX.staticRuntime(glibc))
    intercept[SNXError.StaticLinkingUnsupported](SNXImports.SNX.staticRuntime(darwin))

  test("SNX.staticRuntime renders exactly for the runtimes that support static linking, and fails otherwise"):
    TargetPlatform.all
      .flatMap(NativeRuntime.variants)
      .foreach: runtime =>
        if runtime.supportsStaticLinking then
          val rendered = SNXImports.SNX.staticRuntime(runtime)(Native(NativeConfig.empty)).config
          assert(rendered.linkingOptions.nonEmpty, s"SNX.staticRuntime should render for $runtime")
        else intercept[SNXError.StaticLinkingUnsupported](SNXImports.SNX.staticRuntime(runtime))
end LinkSuite
