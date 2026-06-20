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

import snx.Arch
import snx.OS
import snx.TargetPlatform

class DependencySuite extends munit.FunSuite:

  private val module = "org.example" % "widget" % "1.0"
  private val target = TargetPlatform(OS.Linux, Arch.X86_64)

  test("derive resolves a classified dependency under the target's OS/arch classifier"):
    val derived = SNXPlugin.derive(target, NativeDependency(module, classified = true))
    assertEquals(derived.explicitArtifacts.flatMap(_.classifier).toSet, Set("linux-x86_64"))

  test("derive leaves a plain dependency unclassified"):
    val derived = SNXPlugin.derive(target, NativeDependency(module, classified = false))
    assert(derived.explicitArtifacts.flatMap(_.classifier).isEmpty)
