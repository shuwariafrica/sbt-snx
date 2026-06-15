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

import sbt.*

import snx.ABI
import snx.Arch
import snx.NativeRuntime
import snx.OS
import snx.TargetPlatform

class DependencySuite extends munit.FunSuite:

  private val module = "org.example" % "widget" % "1.0"
  private val target = TargetPlatform(OS.Linux, Arch.X86_64)
  private val runtime = NativeRuntime.Linux(Arch.X86_64, ABI.Glibc)

  test("declared reads the compile scope for an unscoped dependency"):
    assertEquals(SNXPlugin.declared(module), Set("compile"))

  test("declared reads multiple comma-separated configurations, before any mapping arrow"):
    assertEquals(SNXPlugin.declared(module % "compile,test"), Set("compile", "test"))
    assertEquals(SNXPlugin.declared(module % "test->compile"), Set("test"))

  test("a compile dependency is visible in every link scope"):
    assert(SNXPlugin.visible("compile", module))
    assert(SNXPlugin.visible("runtime", module))
    assert(SNXPlugin.visible("test", module))

  test("a runtime dependency is visible in the main (Runtime) and Test links, not in Compile"):
    assert(!SNXPlugin.visible("compile", module % "runtime"))
    assert(SNXPlugin.visible("runtime", module % "runtime"))
    assert(SNXPlugin.visible("test", module % "runtime"))

  test("a test-only dependency is visible only in Test"):
    assert(!SNXPlugin.visible("compile", module % "test"))
    assert(!SNXPlugin.visible("runtime", module % "test"))
    assert(SNXPlugin.visible("test", module % "test"))

  test("derive resolves a classified dependency under the target's OS/arch classifier"):
    val derived = SNXPlugin.derive(target, NativeDependency(module, classified = true))
    assertEquals(derived.explicitArtifacts.flatMap(_.classifier).toSet, Set("linux-x86_64"))

  test("derive leaves a plain dependency unclassified"):
    val derived = SNXPlugin.derive(target, NativeDependency(module, classified = false))
    assert(derived.explicitArtifacts.flatMap(_.classifier).isEmpty)

  test("combineRequirements folds a compile dependency into every scope and a test-only one into Test alone"):
    val none: PartialFunction[NativeRuntime, Usage] = PartialFunction.empty
    val compileDep = NativeDependency(module, false, { case _ => Usage.libraries("compileLib") })
    val testDep = NativeDependency(module % "test", false, { case _ => Usage.libraries("testLib") })
    val deps = Seq(compileDep, testDep)
    val compileReqs = SNXPlugin.combineRequirements(none, deps, "compile", runtime)
    val testReqs = SNXPlugin.combineRequirements(none, deps, "test", runtime)
    assert(compileReqs.libraries.contains("compileLib"), s"compile: ${compileReqs.libraries}")
    assert(!compileReqs.libraries.contains("testLib"), "the descriptor scope must not see test-only requirements")
    assert(testReqs.libraries.contains("compileLib"), "Test must see compile requirements")
    assert(testReqs.libraries.contains("testLib"), "Test must see test-only requirements")

  test("combineRequirements unions project usage with a dependency's requirements and de-duplicates"):
    val usage: PartialFunction[NativeRuntime, Usage] = { case _ => Usage.libraries("shared") }
    val dep = NativeDependency(module, false, { case _ => Usage.libraries("shared", "dep") })
    assertEquals(SNXPlugin.combineRequirements(usage, Seq(dep), "compile", runtime), Usage.libraries("shared", "dep"))
end DependencySuite
