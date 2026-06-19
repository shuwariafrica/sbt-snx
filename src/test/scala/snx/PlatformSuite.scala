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
package snx

class PlatformSuite extends munit.FunSuite:

  test("OS.parse recognises supported operating systems"):
    assertEquals(OS.parse("Linux"), OS.Linux)
    assertEquals(OS.parse("Mac OS X"), OS.Darwin)
    assertEquals(OS.parse("darwin"), OS.Darwin)
    assertEquals(OS.parse("Windows 11"), OS.Windows)

  test("OS.parse rejects an unsupported operating system"):
    val error = intercept[SNXError.UnsupportedTarget](OS.parse("SunOS"))
    assert(clue(error.toString).contains("SunOS"))
    assert(!error.toString.contains("UnsupportedTarget"))

  test("Arch.parse normalises architecture aliases"):
    assertEquals(Arch.parse("amd64"), Arch.X86_64)
    assertEquals(Arch.parse("x86_64"), Arch.X86_64)
    assertEquals(Arch.parse("aarch64"), Arch.Aarch64)
    assertEquals(Arch.parse("arm64"), Arch.Aarch64)

  test("Arch.parse rejects an unsupported architecture"):
    intercept[SNXError.UnsupportedTarget](Arch.parse("riscv64"))

  test("TargetPlatform.classifier renders os-arch with the os-maven tokens"):
    assertEquals(TargetPlatform(OS.Darwin, Arch.Aarch64).classifier, "osx-aarch_64")
    assertEquals(TargetPlatform(OS.Linux, Arch.X86_64).classifier, "linux-x86_64")

  test("NativeRuntime.parse takes the ABI from a four-component triple"):
    assertEquals(
      NativeRuntime.parse(TargetPlatform(OS.Linux, Arch.X86_64), "x86_64-unknown-linux-gnu"),
      NativeRuntime.Linux(Arch.X86_64, ABI.Glibc))
    assertEquals(
      NativeRuntime.parse(TargetPlatform(OS.Linux, Arch.X86_64), "x86_64-unknown-linux-musl"),
      NativeRuntime.Linux(Arch.X86_64, ABI.Musl))
    assertEquals(
      NativeRuntime.parse(TargetPlatform(OS.Windows, Arch.X86_64), "x86_64-pc-windows-msvc"),
      NativeRuntime.Windows(Arch.X86_64, ABI.Msvc))
    assertEquals(
      NativeRuntime.parse(TargetPlatform(OS.Windows, Arch.X86_64), "x86_64-w64-windows-gnu"),
      NativeRuntime.Windows(Arch.X86_64, ABI.MinGw))

  test("NativeRuntime.parse falls back to the third triple component"):
    assertEquals(
      NativeRuntime.parse(TargetPlatform(OS.Linux, Arch.Aarch64), "aarch64-linux-musl"),
      NativeRuntime.Linux(Arch.Aarch64, ABI.Musl))

  test("NativeRuntime.parse ignores the triple environment on macOS"):
    assertEquals(NativeRuntime.parse(TargetPlatform(OS.Darwin, Arch.Aarch64), "arm64-apple-darwin"), NativeRuntime.Darwin(Arch.Aarch64))

  test("NativeRuntime.parse rejects a triple with no recognised ABI"):
    intercept[SNXError.UnsupportedTarget](NativeRuntime.parse(TargetPlatform(OS.Linux, Arch.X86_64), "x86_64-unknown-linux-android"))

  test("ABI carries its environment token"):
    assertEquals(ABI.Glibc.token, "gnu")
    assertEquals(ABI.Musl.token, "musl")
    assertEquals(ABI.Msvc.token, "msvc")
    assertEquals(ABI.MinGw.token, "mingw")

  test("NativeRuntime.pattern renders the descriptor key, with the environment where it applies"):
    assertEquals(NativeRuntime.Linux(Arch.X86_64, ABI.Glibc).pattern, "linux-x86_64-gnu")
    assertEquals(NativeRuntime.Windows(Arch.X86_64, ABI.Msvc).pattern, "windows-x86_64-msvc")
    assertEquals(NativeRuntime.Darwin(Arch.Aarch64).pattern, "osx-aarch_64")

  test("NativeRuntime.variants enumerates each ABI of a target's operating system"):
    assertEquals(
      NativeRuntime.variants(TargetPlatform(OS.Linux, Arch.X86_64)),
      Seq(NativeRuntime.Linux(Arch.X86_64, ABI.Glibc), NativeRuntime.Linux(Arch.X86_64, ABI.Musl))
    )
    assertEquals(NativeRuntime.variants(TargetPlatform(OS.Darwin, Arch.Aarch64)), Seq(NativeRuntime.Darwin(Arch.Aarch64)))
end PlatformSuite
