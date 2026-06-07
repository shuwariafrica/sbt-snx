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
    assertEquals(OS.parse("Mac OS X"), OS.Osx)
    assertEquals(OS.parse("darwin"), OS.Osx)
    assertEquals(OS.parse("Windows 11"), OS.Windows)

  test("OS.parse rejects an unsupported operating system"):
    intercept[UnsupportedTargetException](OS.parse("SunOS"))

  test("Arch.parse normalises architecture aliases"):
    assertEquals(Arch.parse("amd64"), Arch.X86_64)
    assertEquals(Arch.parse("x86_64"), Arch.X86_64)
    assertEquals(Arch.parse("aarch64"), Arch.Aarch64)
    assertEquals(Arch.parse("arm64"), Arch.Aarch64)

  test("Arch.parse rejects an unsupported architecture"):
    intercept[UnsupportedTargetException](Arch.parse("riscv64"))

  test("TargetPlatform.classifier renders os-arch with the os-maven tokens"):
    assertEquals(TargetPlatform(OS.Osx, Arch.Aarch64).classifier, "osx-aarch_64")
    assertEquals(TargetPlatform(OS.Linux, Arch.X86_64).classifier, "linux-x86_64")

  test("NativePlatform.parse takes the libc/ABI from a four-component triple"):
    assertEquals(
      NativePlatform.parse(TargetPlatform(OS.Linux, Arch.X86_64), "x86_64-unknown-linux-gnu"),
      NativePlatform.Linux(Arch.X86_64, LinuxLibc.Glibc))
    assertEquals(
      NativePlatform.parse(TargetPlatform(OS.Linux, Arch.X86_64), "x86_64-unknown-linux-musl"),
      NativePlatform.Linux(Arch.X86_64, LinuxLibc.Musl))
    assertEquals(
      NativePlatform.parse(TargetPlatform(OS.Windows, Arch.X86_64), "x86_64-pc-windows-msvc"),
      NativePlatform.Windows(Arch.X86_64, WindowsABI.MSVC))
    assertEquals(
      NativePlatform.parse(TargetPlatform(OS.Windows, Arch.X86_64), "x86_64-w64-windows-gnu"),
      NativePlatform.Windows(Arch.X86_64, WindowsABI.MinGW))

  test("NativePlatform.parse falls back to the third triple component"):
    assertEquals(
      NativePlatform.parse(TargetPlatform(OS.Linux, Arch.Aarch64), "aarch64-linux-musl"),
      NativePlatform.Linux(Arch.Aarch64, LinuxLibc.Musl))

  test("NativePlatform.parse ignores libc on macOS"):
    assertEquals(NativePlatform.parse(TargetPlatform(OS.Osx, Arch.Aarch64), "arm64-apple-darwin"), NativePlatform.Osx(Arch.Aarch64))

  test("NativePlatform.parse rejects a triple with no recognised libc"):
    intercept[UnsupportedTargetException](NativePlatform.parse(TargetPlatform(OS.Linux, Arch.X86_64), "x86_64-unknown-linux-android"))
end PlatformSuite
