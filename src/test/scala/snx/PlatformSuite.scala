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
      Some(NativeRuntime.Linux(Arch.X86_64, ABI.Glibc)))
    assertEquals(
      NativeRuntime.parse(TargetPlatform(OS.Linux, Arch.X86_64), "x86_64-unknown-linux-musl"),
      Some(NativeRuntime.Linux(Arch.X86_64, ABI.Musl)))
    assertEquals(
      NativeRuntime.parse(TargetPlatform(OS.Windows, Arch.X86_64), "x86_64-pc-windows-msvc"),
      Some(NativeRuntime.Windows(Arch.X86_64, ABI.Msvc)))
    assertEquals(
      NativeRuntime.parse(TargetPlatform(OS.Windows, Arch.X86_64), "x86_64-w64-windows-gnu"),
      Some(NativeRuntime.Windows(Arch.X86_64, ABI.MinGw)))

  test("NativeRuntime.parse falls back to the third triple component"):
    assertEquals(
      NativeRuntime.parse(TargetPlatform(OS.Linux, Arch.Aarch64), "aarch64-linux-musl"),
      Some(NativeRuntime.Linux(Arch.Aarch64, ABI.Musl)))

  test("NativeRuntime.parse ignores the triple environment on macOS"):
    assertEquals(
      NativeRuntime.parse(TargetPlatform(OS.Darwin, Arch.Aarch64), "arm64-apple-darwin"),
      Some(NativeRuntime.Darwin(Arch.Aarch64)))

  test("NativeRuntime.parse yields no runtime for a triple with no recognised ABI"):
    assertEquals(NativeRuntime.parse(TargetPlatform(OS.Linux, Arch.X86_64), "x86_64-unknown-linux-android"), None)

  // Real `clang -dumpmachine` output per distribution, openSUSE included - it alone names no environment component.
  test("NativeRuntime.parse resolves the distribution triples that name an environment, and only those"):
    val resolved = Map(
      "x86_64-redhat-linux-gnu" -> Some(NativeRuntime.Linux(Arch.X86_64, ABI.Glibc)), // Fedora, RHEL
      "x86_64-pc-linux-gnu" -> Some(NativeRuntime.Linux(Arch.X86_64, ABI.Glibc)), // Debian, Ubuntu, Arch, Gentoo
      "x86_64-alpine-linux-musl" -> Some(NativeRuntime.Linux(Arch.X86_64, ABI.Musl)), // Alpine
      "arm-unknown-linux-gnueabihf" -> Some(NativeRuntime.Linux(Arch.X86_64, ABI.Glibc)), // hard-float ARM suffix
      "x86_64-unknown-linux-musleabi" -> Some(NativeRuntime.Linux(Arch.X86_64, ABI.Musl)), // musl with a suffix
      "x86_64-suse-linux" -> None, // openSUSE: no environment
      "aarch64-linux-android" -> None // bionic: named, but not a C library we support
    )
    resolved.foreach: (triple, expected) =>
      assertEquals(NativeRuntime.parse(TargetPlatform(OS.Linux, Arch.X86_64), triple), expected, triple)

  // The Windows ABI is named from the environment slot by the modern triples and from the operating-system slot by the
  // classic MinGW one, so recognition cannot key on position. The MSYS2 clang64 toolchain CI uses reports the modern
  // form, which is why only the classic form was ever unresolved.
  test("NativeRuntime.parse resolves the Windows ABI from whichever slot names it"):
    val resolved = Map(
      "x86_64-pc-windows-msvc" -> Some(ABI.Msvc),
      "x86_64-w64-windows-gnu" -> Some(ABI.MinGw), // MSYS2 clang64
      "aarch64-pc-windows-gnullvm" -> Some(ABI.MinGw), // LLVM-native MinGW
      "x86_64-w64-mingw32" -> Some(ABI.MinGw), // classic MinGW-w64
      "i686-w64-mingw32" -> Some(ABI.MinGw), // classic MinGW-w64, 32-bit
      "x86_64-pc-windows-cygnus" -> None // Cygwin is not a supported ABI
    )
    resolved.foreach: (triple, expected) =>
      assertEquals(
        NativeRuntime.parse(TargetPlatform(OS.Windows, Arch.X86_64), triple),
        expected.map(NativeRuntime.Windows(Arch.X86_64, _)),
        triple)

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
