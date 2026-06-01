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

/** The C library of a Linux toolchain. See [[LinuxLibc$ LinuxLibc]] to parse a target-triple environment component. */
enum LinuxLibc:
  case Glibc, Musl

/** Parser and equality instance for [[LinuxLibc]]. */
object LinuxLibc:
  given CanEqual[LinuxLibc, LinuxLibc] = CanEqual.derived

  /** Map a target-triple environment component to a Linux libc (`musl*` -> [[Musl]]; otherwise [[Glibc]], the `gnu`
    * default).
    */
  def parse(env: String): LinuxLibc = if env.startsWith("musl") then Musl else Glibc

/** The C runtime ABI of a Windows toolchain. See [[WindowsAbi$ WindowsAbi]] to parse a target-triple environment
  * component.
  */
enum WindowsAbi:
  case Msvc, Mingw

/** Parser and equality instance for [[WindowsAbi]]. */
object WindowsAbi:
  given CanEqual[WindowsAbi, WindowsAbi] = CanEqual.derived

  /** Map a target-triple environment component to a Windows ABI (`gnu*` -> [[Mingw]]; otherwise [[Msvc]]). */
  def parse(env: String): WindowsAbi = if env.startsWith("gnu") then Mingw else Msvc

/** A fully-resolved native platform: a [[TargetPlatform]] plus the toolchain libc/ABI on operating systems where it
  * varies. The match key for per-platform linking, so a libc match offers only the values valid for that operating
  * system. See [[NativePlatform$ NativePlatform]] to resolve one.
  */
enum NativePlatform:
  case Linux(arch: Arch, libc: LinuxLibc)
  case Osx(arch: Arch)
  case Windows(arch: Arch, abi: WindowsAbi)

/** Resolver and equality instance for [[NativePlatform]]. */
object NativePlatform:
  given CanEqual[NativePlatform, NativePlatform] = CanEqual.derived

  /** Resolve a [[TargetPlatform]] and a target-triple environment component into a [[NativePlatform]]. */
  def parse(target: TargetPlatform, env: String): NativePlatform = target.os match
    case Os.Linux   => Linux(target.arch, LinuxLibc.parse(env))
    case Os.Osx     => Osx(target.arch)
    case Os.Windows => Windows(target.arch, WindowsAbi.parse(env))
