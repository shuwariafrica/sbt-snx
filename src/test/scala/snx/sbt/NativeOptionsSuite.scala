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

class NativeOptionsSuite extends munit.FunSuite:

  test("empty and the no-argument constructor are the identity for ++"):
    val a = NativeOptions().withLinking("-lm").withCompile("-O2").withC("-DA").withCpp("-DB")
    assertEquals(NativeOptions(), NativeOptions.empty)
    assertEquals(a ++ NativeOptions.empty, a)
    assertEquals(NativeOptions.empty ++ a, a)

  test("channel builders append to their own channel only"):
    val o = NativeOptions().withLinking("-lm").withC("-DC").withCpp("-DX").withCompile("-DAll")
    assertEquals(o.linking, Seq("-lm"))
    assertEquals(o.compile, Seq("-DAll"))
    assertEquals(o.c, Seq("-DC"))
    assertEquals(o.cpp, Seq("-DX"))

  test("++ concatenates channel by channel"):
    val a = NativeOptions().withLinking("-la").withC("-Da")
    val b = NativeOptions().withLinking("-lb").withCpp("-Db")
    val merged = a ++ b
    assertEquals(merged.linking, Seq("-la", "-lb"))
    assertEquals(merged.c, Seq("-Da"))
    assertEquals(merged.cpp, Seq("-Db"))
    assertEquals(merged.compile, Seq.empty[String])
