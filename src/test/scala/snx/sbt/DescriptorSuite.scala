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
import snx.OS
import snx.TargetPlatform

class DescriptorSuite extends munit.FunSuite:

  private val module = Module("africa.shuwari", "snxlib", "0.2.0")
  private def linux(abi: ABI[OS.Linux]) = NativeRuntime.Linux(Arch.X86_64, abi)

  test("Usage.++ unions channels in order and disjoins the multithreading requirement"):
    assertEquals(
      Usage.libraries("pthread") ++ Usage.multithreaded ++ Usage.frameworks("Security"),
      Usage(Seq("pthread"), Seq("Security"), Nil, Nil, Nil, true))

  test("render is byte-stable: fixed field order, sorted patterns, empty channels omitted"):
    val descriptor = Descriptor(module, Map("osx" -> Usage.frameworks("Security"), "linux" -> Usage.libraries("pthread", "dl")))
    assertEquals(
      Descriptor.render(descriptor),
      """{"schemaVersion":1,"module":{"organization":"africa.shuwari","name":"snxlib","version":"0.2.0"},""" +
        """"usage":{"linux":{"libraries":["pthread","dl"]},"osx":{"frameworks":["Security"]}}}"""
    )

  test("render is independent of usage-map insertion order"):
    val a = Descriptor(module, Map("linux" -> Usage.libraries("a"), "windows-x86_64-msvc" -> Usage.linkFlags("/X")))
    val b = Descriptor(module, Map("windows-x86_64-msvc" -> Usage.linkFlags("/X"), "linux" -> Usage.libraries("a")))
    assertEquals(Descriptor.render(a), Descriptor.render(b))

  // The wire is read by whatever plugin version a consumer resolves, which is routinely older than the publisher's.
  // Every field is read as an optional, so a reader must ignore what it does not know rather than fail the build:
  // that property is what makes a later additive channel safe to introduce, and it is asserted here so a codec change
  // cannot quietly remove it.
  test("parse ignores fields it does not know, at every level of the descriptor"):
    val known = Descriptor.parse("""{"module":{"organization":"o","name":"n","version":"1"},"usage":{"*":{"libraries":["foo"]}}}""")
    val unknownInUsage =
      Descriptor.parse(
        """{"module":{"organization":"o","name":"n","version":"1"},"usage":{"*":{"libraries":["foo"],"whenAdded":["foo"]}}}""")
    val unknownInModule =
      Descriptor.parse("""{"module":{"organization":"o","name":"n","version":"1","extra":true},"usage":{"*":{"libraries":["foo"]}}}""")
    val unknownAtRoot =
      Descriptor.parse("""{"module":{"organization":"o","name":"n","version":"1"},"usage":{"*":{"libraries":["foo"]}},"extra":1}""")
    assertEquals(unknownInUsage, known)
    assertEquals(unknownInModule, known)
    assertEquals(unknownAtRoot, known)

  test("parse round-trips render, preserving every channel and the requirement"):
    val descriptor = Descriptor(
      module,
      Map(
        "linux-x86_64-gnu" -> Usage(Seq("pthread"), Nil, Seq("foo"), Seq("USE=1"), Seq("-z,now"), true),
        "*" -> Usage.frameworks("Security")))
    assertEquals(Descriptor.parse(Descriptor.render(descriptor)), descriptor)

  test("build collapses an env-uniform classified target to its os-arch pattern"):
    val descriptor = Descriptor.build(
      module,
      classified = true,
      TargetPlatform(OS.Linux, Arch.X86_64),
      { case NativeRuntime.Linux(_, _) => Usage.libraries("pthread") })
    assertEquals(descriptor.usage, Map("linux-x86_64" -> Usage.libraries("pthread")))

  test("build keeps per-env patterns when a classified target's ABIs differ"):
    val descriptor = Descriptor.build(
      module,
      classified = true,
      TargetPlatform(OS.Linux, Arch.X86_64),
      {
        case NativeRuntime.Linux(_, ABI.Glibc) => Usage.libraries("pthread"); case NativeRuntime.Linux(_, ABI.Musl) => Usage.libraries("c")
      }
    )
    assertEquals(descriptor.usage, Map("linux-x86_64-gnu" -> Usage.libraries("pthread"), "linux-x86_64-musl" -> Usage.libraries("c")))

  test("build keeps a single declared env when its sibling is undeclared"):
    val descriptor = Descriptor.build(
      module,
      classified = true,
      TargetPlatform(OS.Linux, Arch.X86_64),
      { case NativeRuntime.Linux(_, ABI.Glibc) => Usage.libraries("pthread") })
    assertEquals(descriptor.usage, Map("linux-x86_64-gnu" -> Usage.libraries("pthread")))

  test("build collapses a platform-uniform universal jar to a single wildcard pattern"):
    val descriptor = Descriptor.build(module, classified = false, TargetPlatform(OS.Linux, Arch.X86_64), { case _ => Usage.multithreaded })
    assertEquals(descriptor.usage, Map("*" -> Usage.multithreaded))

  test("build collapses an os-uniform universal jar to its os pattern"):
    val descriptor = Descriptor.build(
      module,
      classified = false,
      TargetPlatform(OS.Linux, Arch.X86_64),
      { case NativeRuntime.Linux(_, _) => Usage.libraries("pthread") })
    assertEquals(descriptor.usage, Map("linux" -> Usage.libraries("pthread")))

  test("build drops an empty declared requirement"):
    val descriptor =
      Descriptor.build(module, classified = true, TargetPlatform(OS.Linux, Arch.X86_64), { case NativeRuntime.Linux(_, _) => Usage.empty })
    assertEquals(descriptor.usage, Map.empty[String, Usage])

  test("fold takes the most-specific pattern per field"):
    val descriptor = Descriptor(module, Map("linux" -> Usage.libraries("base"), "linux-x86_64-musl" -> Usage.libraries("musl")))
    assertEquals(Descriptor.fold(Seq(descriptor), linux(ABI.Musl)), Usage.libraries("musl"))
    assertEquals(Descriptor.fold(Seq(descriptor), linux(ABI.Glibc)), Usage.libraries("base"))

  test("fold combines descriptors in dependency order and disjoins the requirement"):
    val first = Descriptor(module, Map("*" -> (Usage.libraries("a") ++ Usage.multithreaded)))
    val second = Descriptor(module, Map("*" -> Usage.libraries("b")))
    assertEquals(Descriptor.fold(Seq(first, second), linux(ABI.Glibc)), Usage(Seq("a", "b"), Nil, Nil, Nil, Nil, true))

  test("distinct de-duplicates each channel, keeping the first occurrence"):
    assertEquals(
      Usage(Seq("a", "b", "a"), Nil, Seq("x", "x"), Nil, Nil, true).distinct,
      Usage(Seq("a", "b"), Nil, Seq("x"), Nil, Nil, true))

  test("fold de-duplicates a requirement two descriptors share, so a whole-archive is not force-loaded twice"):
    val shared = Usage.libraries("z") ++ Usage.wholeArchive("foo")
    val a = Descriptor(module, Map("*" -> shared))
    val b = Descriptor(module, Map("*" -> shared))
    assertEquals(Descriptor.fold(Seq(a, b), linux(ABI.Glibc)), shared)
end DescriptorSuite
