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

import sbt.librarymanagement.ModuleID

import snx.Arch
import snx.LinuxLibc
import snx.NativePlatform

class SNXPluginSuite extends munit.FunSuite:

  private def module(configurations: String): ModuleID =
    val base = ModuleID("org", "lib", "1")
    if configurations.isEmpty then base else base.withConfigurations(Some(configurations))

  test("declared parses the configuration string, defaulting an unscoped dependency to compile"):
    assertEquals(SNXPlugin.declared(module("")), Set("compile"))
    assertEquals(SNXPlugin.declared(module("test")), Set("test"))
    assertEquals(SNXPlugin.declared(module("compile,test")), Set("compile", "test"))
    assertEquals(SNXPlugin.declared(module("test->compile")), Set("test"))

  test("visible applies a compile dependency once in Compile and a test-only one only in Test"):
    // A compile-visible dependency contributes to Compile (and reaches Test by sbt's config delegation, so is not
    // re-applied there); a test/runtime-exclusive one contributes only to Test; one declared in both is applied once.
    assert(SNXPlugin.visible("compile", module("")))
    assert(!SNXPlugin.visible("test", module("")))
    assert(!SNXPlugin.visible("compile", module("test")))
    assert(SNXPlugin.visible("test", module("test")))
    assert(SNXPlugin.visible("compile", module("compile,test")))
    assert(!SNXPlugin.visible("test", module("compile,test")))

  test("resolveRelationship resolves Auto by kind and lets an explicit relationship win"):
    assertEquals(SNXPlugin.resolveRelationship(Relationship.Auto, static = true), Relationship.StaticLink)
    assertEquals(SNXPlugin.resolveRelationship(Relationship.Auto, static = false), Relationship.DynamicLink)
    assertEquals(SNXPlugin.resolveRelationship(Relationship.DynamicLink, static = true), Relationship.DynamicLink)

  test("nativeSuffix derives the cross suffix and coordinate strips it to a platform-independent base identity"):
    assertEquals(SNXPlugin.nativeSuffix("3.8.3", "3"), "_native0.5_3")
    val suffixed = SNXPlugin.coordinate("africa.shuwari", "snxlib", "snxlib_native0.5_3", "0.1.0", "3.8.3", "3")
    assertEquals(suffixed.identity, "pkg:maven/africa.shuwari/snxlib@0.1.0")
    val plain = SNXPlugin.coordinate("africa.shuwari", "snxlib", "snxlib", "0.1.0", "3.8.3", "3")
    assertEquals(plain.identity, "pkg:maven/africa.shuwari/snxlib@0.1.0")

  test("optionsFor folds the per-platform bundle, contributing none on an unmatched platform"):
    val dependency = NativeDependency(ModuleID("org", "lib", "1"))
      .options { case NativePlatform.Linux(_, _) => NativeOptions().withLinking("-lfoo") }
    assertEquals(dependency.optionsFor(NativePlatform.Linux(Arch.X86_64, LinuxLibc.Glibc)).linking, Seq("-lfoo"))
    assertEquals(dependency.optionsFor(NativePlatform.Osx(Arch.Aarch64)), NativeOptions.empty)
end SNXPluginSuite
