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

/** The C library of a Linux toolchain. See [[LinuxLibc$ LinuxLibc]] to map a target-triple environment component. */
enum LinuxLibc:
  case Glibc, Musl

/** Recogniser and equality instance for [[LinuxLibc]]. */
object LinuxLibc:
  given CanEqual[LinuxLibc, LinuxLibc] = CanEqual.derived

  private[snx] def from(env: String): Option[LinuxLibc] =
    if env.startsWith("musl") then Some(Musl)
    else if env.startsWith("gnu") then Some(Glibc)
    else None

/** The C runtime ABI of a Windows toolchain. See [[WindowsABI$ WindowsABI]] to map a target-triple environment
  * component.
  */
enum WindowsABI:
  case MSVC, MinGW

/** Recogniser and equality instance for [[WindowsABI]]. */
object WindowsABI:
  given CanEqual[WindowsABI, WindowsABI] = CanEqual.derived

  private[snx] def from(env: String): Option[WindowsABI] =
    if env.startsWith("gnu") then Some(MinGW)
    else if env.startsWith("msvc") then Some(MSVC)
    else None

/** A fully-resolved native platform: a [[TargetPlatform]] plus the toolchain libc/ABI on operating systems where it
  * varies. The match key for per-platform linking, so a libc match offers only the values valid for that operating
  * system. See [[NativePlatform$ NativePlatform]] to resolve one.
  */
enum NativePlatform:
  case Linux(arch: Arch, libc: LinuxLibc)
  case Osx(arch: Arch)
  case Windows(arch: Arch, abi: WindowsABI)

/** Resolver and equality instance for [[NativePlatform]]. */
object NativePlatform:
  given CanEqual[NativePlatform, NativePlatform] = CanEqual.derived

  /** Resolve a [[TargetPlatform]] and a Scala Native target triple into a [[NativePlatform]], taking the toolchain
    * libc/ABI from the triple's environment component.
    *
    * @throws UnsupportedTargetException
    *   on Linux or Windows when the triple identifies no supported libc/ABI.
    */
  def parse(target: TargetPlatform, triple: String): NativePlatform = target.os match
    case OS.Linux   => Linux(target.arch, resolve(triple, LinuxLibc.from, "Linux C library"))
    case OS.Osx     => Osx(target.arch)
    case OS.Windows => Windows(target.arch, resolve(triple, WindowsABI.from, "Windows ABI"))

  // Mirrors scala-native's TargetTriple.parse: the environment is the fourth component, falling back to the third for a
  // three-component `arch-os-env` triple.
  private def environments(triple: String): List[String] =
    val parts = triple.split("-", 4).nn.toList
    List(parts.lift(3), parts.lift(2)).flatten.map(_.nn).filter(_.nonEmpty)

  private def resolve[A](triple: String, from: String => Option[A], component: String): A =
    environments(triple).flatMap(from).headOption.getOrElse(fail(component, triple))

  private def fail(component: String, triple: String): Nothing =
    throw UnsupportedTargetException(s"Unable to determine the $component from target triple: '$triple'") // scalafix:ok DisableSyntax.throw
end NativePlatform
