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

import snx.ABI
import snx.Arch
import snx.NativeRuntime

class LinkSuite extends munit.FunSuite:

  private val glibc = NativeRuntime.Linux(Arch.X86_64, ABI.Glibc)
  private val musl = NativeRuntime.Linux(Arch.X86_64, ABI.Musl)
  private val darwin = NativeRuntime.Darwin(Arch.Aarch64)

  test("the NIR deliverable is not linked"):
    intercept[RuntimeException](SNXPlugin.resolveTarget(Deliverable.NIR, Linkage.Dynamic, glibc, Some("Main")))

  test("an Executable requires a main class"):
    intercept[RuntimeException](SNXPlugin.resolveTarget(Deliverable.Executable, Linkage.Dynamic, glibc, None))

  test("a dynamic Executable links as an application carrying its main class"):
    val (target, static, main) = SNXPlugin.resolveTarget(Deliverable.Executable, Linkage.Dynamic, glibc, Some("Main"))
    assert(target == BuildTarget.application)
    assertEquals(static, false)
    assertEquals(main, Some("Main"))

  test("a static Executable is rejected where the platform cannot link statically"):
    intercept[RuntimeException](SNXPlugin.resolveTarget(Deliverable.Executable, Linkage.Static, glibc, Some("Main")))

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

  test("a Library deliverable's test links as a dynamic application regardless of test linkage"):
    val (target, static) = SNXPlugin.resolveTestTarget(Deliverable.Library, Linkage.Static, musl)
    assert(target == BuildTarget.application)
    assertEquals(static, false)

  test("a NIR deliverable's test links as a dynamic application"):
    val (target, static) = SNXPlugin.resolveTestTarget(Deliverable.NIR, Linkage.Static, musl)
    assert(target == BuildTarget.application)
    assertEquals(static, false)

  test("an Executable deliverable's test honours a static test linkage where supported"):
    val (target, static) = SNXPlugin.resolveTestTarget(Deliverable.Executable, Linkage.Static, musl)
    assert(target == BuildTarget.application)
    assertEquals(static, true)

  test("an Executable deliverable's test links dynamically under dynamic test linkage"):
    val (target, static) = SNXPlugin.resolveTestTarget(Deliverable.Executable, Linkage.Dynamic, glibc)
    assert(target == BuildTarget.application)
    assertEquals(static, false)
end LinkSuite
