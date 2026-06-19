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
    * @throws SNXError.UnsupportedTarget
    *   on Linux or Windows when the triple identifies no supported ABI.
    */
  def parse(target: TargetPlatform, triple: String): NativeRuntime = target.os match
    case OS.Linux   => Linux(target.arch, resolve(triple, linuxLibc, "Linux C library"))
    case OS.Darwin  => Darwin(target.arch)
    case OS.Windows => Windows(target.arch, resolve(triple, windowsABI, "Windows ABI"))

  /** The native runtimes a [[TargetPlatform]] can resolve to - each supported [[ABI]] for its operating system (both
    * Linux C libraries, both Windows ABIs; macOS has none).
    */
  def variants(target: TargetPlatform): Seq[NativeRuntime] = target.os match
    case OS.Linux   => Seq(Linux(target.arch, ABI.Glibc), Linux(target.arch, ABI.Musl))
    case OS.Darwin  => Seq(Darwin(target.arch))
    case OS.Windows => Seq(Windows(target.arch, ABI.Msvc), Windows(target.arch, ABI.MinGw))

  private def linuxLibc(env: String): Option[ABI[OS.Linux]] =
    if env.startsWith("musl") then Some(ABI.Musl)
    else if env.startsWith("gnu") then Some(ABI.Glibc)
    else None

  private def windowsABI(env: String): Option[ABI[OS.Windows]] =
    if env.startsWith("gnu") then Some(ABI.MinGw)
    else if env.startsWith("msvc") then Some(ABI.Msvc)
    else None

  // The environment is the fourth component of an `arch-vendor-os-env` triple, or the third of a shorter
  // `arch-os-env` one; both are offered, most specific first.
  private def environments(triple: String): List[String] =
    val parts = triple.split("-", 4).nn.toList
    List(parts.lift(3), parts.lift(2)).flatten.map(_.nn).filter(_.nonEmpty)

  private def resolve[A](triple: String, recognise: String => Option[A], component: String): A =
    environments(triple).flatMap(recognise).headOption.getOrElse(fail(component, triple))

  private def fail(component: String, triple: String): Nothing =
    throw SNXError.UnsupportedTarget(s"Unable to determine the $component from target triple: '$triple'") // scalafix:ok DisableSyntax.throw

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
