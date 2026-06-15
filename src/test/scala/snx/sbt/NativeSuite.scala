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

import scala.scalanative.build.JVMMemoryModelCompliance
import scala.scalanative.build.NativeConfig

class NativeSuite extends munit.FunSuite:

  test("finalFields sets the JVM memory-model compliance through the semantics config"):
    val configured = Native(NativeConfig.empty).finalFields(JVMMemoryModelCompliance.Strict)
    assertEquals(configured.config.semanticsConfig.finalFields, JVMMemoryModelCompliance.Strict)

  test("debugSymbols toggles source-level debugging in the config"):
    assert(Native(NativeConfig.empty).debugSymbols(true).config.sourceLevelDebuggingConfig.enabled)
    assert(!Native(NativeConfig.empty).debugSymbols(false).config.sourceLevelDebuggingConfig.enabled)

  test("linktimeProperty records each typed value under its name"):
    val configured = Native(NativeConfig.empty)
      .linktimeProperty("flag", true)
      .linktimeProperty("count", 3)
      .linktimeProperty("name", "value")
    assertEquals(configured.config.linktimeProperties, Map[String, Any]("flag" -> true, "count" -> 3, "name" -> "value"))
