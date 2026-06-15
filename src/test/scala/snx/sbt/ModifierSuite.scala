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

import snx.ABI
import snx.Arch
import snx.NativeRuntime

class ModifierSuite extends munit.FunSuite:

  private val glibc = NativeRuntime.Linux(Arch.X86_64, ABI.Glibc)
  private val musl = NativeRuntime.Linux(Arch.X86_64, ABI.Musl)
  private val darwin = NativeRuntime.Darwin(Arch.Aarch64)
  private val msvc = NativeRuntime.Windows(Arch.X86_64, ABI.Msvc)
  private val mingw = NativeRuntime.Windows(Arch.X86_64, ABI.MinGw)

  test("wholeArchivePath renders the whole-archive syntax of each platform's linker"):
    assertEquals(Modifier.wholeArchivePath(darwin, "/x.a"), Seq("-Wl,-force_load,/x.a"))
    assertEquals(Modifier.wholeArchivePath(msvc, "/x.a"), Seq("/x.a", "-Wl,/WHOLEARCHIVE:/x.a"))
    assertEquals(Modifier.wholeArchivePath(glibc, "/x.a"), Seq("-Wl,--whole-archive", "/x.a", "-Wl,--no-whole-archive"))
    assertEquals(Modifier.wholeArchivePath(mingw, "/x.a"), Seq("-Wl,--whole-archive", "/x.a", "-Wl,--no-whole-archive"))

  test("wholeArchiveName renders the name-based whole-archive syntax where the linker provides one"):
    assertEquals(Modifier.wholeArchiveName(msvc, "foo"), Seq("-lfoo", "-Wl,/WHOLEARCHIVE:foo"))
    assertEquals(Modifier.wholeArchiveName(glibc, "foo"), Seq("-Wl,--whole-archive", "-lfoo", "-Wl,--no-whole-archive"))
    assertEquals(Modifier.wholeArchiveName(darwin, "foo"), Seq.empty[String])

  test("the File whole-archive applies on every platform; the name form skips macOS"):
    val byFile = Modifier.wholeArchive(new java.io.File("x.a"))
    assert(byFile.isDefinedAt(darwin) && byFile.isDefinedAt(glibc) && byFile.isDefinedAt(msvc))
    val byName = Modifier.wholeArchive("foo")
    assert(!byName.isDefinedAt(darwin), "the name form has no macOS whole-archive syntax")
    assert(byName.isDefinedAt(glibc) && byName.isDefinedAt(musl) && byName.isDefinedAt(msvc) && byName.isDefinedAt(mingw))
end ModifierSuite
