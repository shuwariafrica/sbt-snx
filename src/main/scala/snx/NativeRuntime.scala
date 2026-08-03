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

/** The toolchain ABI of an operating system `A`: the C library on Linux ([[ABI.Glibc]], [[ABI.Musl]]) and the runtime
  * ABI on Windows ([[ABI.Msvc]], [[ABI.MinGw]]), each carrying the environment token it renders to. Indexed by [[OS]]
  * so a [[NativeRuntime]] case admits only the values valid for its operating system. See [[ABI$ ABI]] for the cases.
  */
enum ABI[A <: OS](val token: String) derives CanEqual:
  case Glibc extends ABI[OS.Linux]("gnu")
  case Musl extends ABI[OS.Linux]("musl")
  case Msvc extends ABI[OS.Windows]("msvc")
  case MinGw extends ABI[OS.Windows]("mingw")

/** A fully-resolved native runtime: a [[TargetPlatform]] refined with the toolchain [[ABI]] on operating systems where
  * it varies - the key for conditioning the native build per platform, narrowed so a match offers only the ABI values
  * valid for the operating system. See [[NativeRuntime$ NativeRuntime]] to resolve one.
  */
enum NativeRuntime derives CanEqual:
  case Linux(arch: Arch, abi: ABI[OS.Linux])
  case Darwin(arch: Arch)
  case Windows(arch: Arch, abi: ABI[OS.Windows])

/** Resolver and capability predicate for [[NativeRuntime]]. */
object NativeRuntime:

  /** Resolve a [[TargetPlatform]] and a Scala Native target triple into a [[NativeRuntime]], taking the toolchain ABI
    * from the triple's environment component.
    *
    * Yields `None` where the triple names no environment - `x86_64-suse-linux` gives an architecture, a vendor and an
    * operating system only - and so identifies neither the C library nor the Windows ABI. macOS has no ABI axis and
    * always resolves.
    */
  def parse(target: TargetPlatform, triple: String): Option[NativeRuntime] = target.os match
    case OS.Linux   => resolve(triple, linuxLibc).map(Linux(target.arch, _))
    case OS.Darwin  => Some(Darwin(target.arch))
    case OS.Windows => resolve(triple, windowsABI).map(Windows(target.arch, _))

  /** The native runtimes a [[TargetPlatform]] can resolve to - each supported [[ABI]] for its operating system (both
    * Linux C libraries, both Windows ABIs; macOS has none).
    */
  def variants(target: TargetPlatform): Seq[NativeRuntime] = target.os match
    case OS.Linux   => Seq(Linux(target.arch, ABI.Glibc), Linux(target.arch, ABI.Musl))
    case OS.Darwin  => Seq(Darwin(target.arch))
    case OS.Windows => Seq(Windows(target.arch, ABI.Msvc), Windows(target.arch, ABI.MinGw))

  // Recognised from the token rather than by position, for the reason `parse` gives: `x86_64-suse-linux` carries the
  // operating system third and `aarch64-linux-gnu` second. `mingw32` and `cygwin` name Windows from that slot.
  private[snx] def os(triple: String): Option[OS] =
    val parts = triple.split("-", 4).nn.toList
    List(parts.lift(2), parts.lift(1)).flatten.map(_.nn).flatMap(operatingSystem).headOption

  private def operatingSystem(token: String): Option[OS] =
    if token.startsWith("linux") then Some(OS.Linux)
    else if token.startsWith("windows") || token.startsWith("mingw") || token.startsWith("cygwin") then Some(OS.Windows)
    else if token.startsWith("darwin") || token.startsWith("macos") then Some(OS.Darwin)
    else None

  private def linuxLibc(env: String): Option[ABI[OS.Linux]] =
    if env.startsWith("musl") then Some(ABI.Musl)
    else if env.startsWith("gnu") then Some(ABI.Glibc)
    else None

  // `mingw32`/`mingw64` name the ABI from the OPERATING-SYSTEM slot of the classic `x86_64-w64-mingw32` triple, where
  // the other forms carry it in the environment slot (`x86_64-w64-windows-gnu`, `aarch64-pc-windows-gnullvm`). Both
  // reach here because candidates are recognised rather than taken by position, so the token is what decides.
  private def windowsABI(env: String): Option[ABI[OS.Windows]] =
    if env.startsWith("gnu") || env.startsWith("mingw") then Some(ABI.MinGw)
    else if env.startsWith("msvc") then Some(ABI.Msvc)
    else None

  // Offer the fourth component of an `arch-vendor-os-env` triple and the third of a shorter `arch-os-env` one, most
  // specific first. Position alone cannot tell those apart from `arch-vendor-os` (`x86_64-suse-linux`), so these are
  // CANDIDATES: the recognisers above decide, and a component naming an operating system rather than an environment
  // simply matches nothing and falls through to the caller's next authority.
  private def environments(triple: String): List[String] =
    val parts = triple.split("-", 4).nn.toList
    List(parts.lift(3), parts.lift(2)).flatten.map(_.nn).filter(_.nonEmpty)

  private def resolve[A](triple: String, recognise: String => Option[A]): Option[A] =
    environments(triple).flatMap(recognise).headOption

  extension (runtime: NativeRuntime)
    /** Whether the toolchain can link a fully static executable - musl on Linux, MSVC on Windows. */
    def supportsStaticLinking: Boolean = runtime match
      case Linux(_, ABI.Musl)    => true
      case Linux(_, ABI.Glibc)   => false
      case Darwin(_)             => false
      case Windows(_, ABI.Msvc)  => true
      case Windows(_, ABI.MinGw) => false

    /** The descriptor pattern key: `<os>-<arch>` refined with the [[ABI]] environment where it applies - for example
      * `linux-x86_64-gnu`; macOS has no environment, so `osx-aarch_64`.
      */
    def pattern: String = runtime match
      case Linux(arch, abi)   => s"${OS.Linux.token}-${arch.token}-${abi.token}"
      case Darwin(arch)       => s"${OS.Darwin.token}-${arch.token}"
      case Windows(arch, abi) => s"${OS.Windows.token}-${arch.token}-${abi.token}"
end NativeRuntime
