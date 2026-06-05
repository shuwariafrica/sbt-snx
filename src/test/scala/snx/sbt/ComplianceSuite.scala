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

import java.io.File

import snx.sbt.SNXImports.licensed

class ComplianceSuite extends munit.FunSuite:

  test("Component's shared setters accumulate into its Licensing, read back flatly"):
    val component = Component("zlib", "Zlib", new File("LICENSE"))
      .identity("pkg:generic/zlib@1.3")
      .copyright("Copyright Mark Adler")
      .originator("Person: Mark Adler")
    assertEquals(component.name, "zlib")
    assertEquals(component.license, "Zlib")
    assertEquals(component.identity, Some("pkg:generic/zlib@1.3"))
    assertEquals(component.copyright, Some("Copyright Mark Adler"))
    assertEquals(component.originator, Some("Person: Mark Adler"))

  test("a library declaration shares the licensing setters and adds relationship and bundles"):
    val source = NativeSource
      .System("uv")
      .licensed("MIT")
      .copyright("Copyright libuv")
      .relationship(Relationship.DynamicLink)
      .bundles(Component("inner", "BSD-3-Clause"))
    assertEquals(source.compliance.license, "MIT")
    assertEquals(source.compliance.copyright, Some("Copyright libuv"))
    assertEquals(source.compliance.relationship, Relationship.DynamicLink)
    assertEquals(source.compliance.contains.map(_.name), Seq("inner"))

  test("the ModuleID licensed extension lifts and declares in one step"):
    val module: ModuleID = ModuleID("africa.shuwari", "snxlib", "0.1.0")
    val dependency = module.licensed("MIT")
    assertEquals(dependency.compliance.license, "MIT")
end ComplianceSuite
