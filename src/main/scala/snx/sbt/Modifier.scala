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

import java.io.File

import snx.ABI
import snx.NativeRuntime
import snx.NativeRuntime.Darwin
import snx.NativeRuntime.Linux
import snx.NativeRuntime.Windows

/** A per-platform transform of a native configuration `A`: applied for the platforms it matches, contributing
  * nothing elsewhere. See [[Modifier$ Modifier]] for the carrier and the whole-archive intent.
  */
type Modifier[A] = PartialFunction[NativeRuntime, A => A]

/** Carrier and constructors for [[Modifier]]. A platform-specific configuration is written directly as a partial
  * function over the resolved [[NativeRuntime]] - `platform` carries one for [[Native]] modifiers under `+=`, and a
  * [[Contribution]] modifier is a bare literal whose target the enclosing setting parameter supplies.
  */
object Modifier:

  /** Carry a per-platform partial function as a [[Modifier]], so a bare literal types under `+=` (where the element
    * type is not propagated) without an explicit function-argument annotation.
    */
  def platform(modifier: PartialFunction[NativeRuntime, Native => Native]): Modifier[Native] = modifier

  /** Force-link every object file of the static archive at `archive`, including ones nothing references. Valid on
    * every platform - macOS `-force_load`, MSVC `/WHOLEARCHIVE:`, GNU ld `--whole-archive`.
    */
  def wholeArchive(archive: File): Modifier[Native] = { case runtime =>
    native => native.linkOptions(wholeArchivePath(runtime, archive.getAbsolutePath.nn)*)
  }

  /** Force-link every object file of the named static library (`-l<name>`), including ones nothing references. macOS
    * has no name-based whole-archive linker form, so this contributes nothing there; use the `File` overload for
    * macOS.
    */
  def wholeArchive(name: String): Modifier[Native] = { case runtime @ (Linux(_, _) | Windows(_, _)) =>
    native => native.linkOptions(wholeArchiveName(runtime, name)*)
  }

  private[sbt] def wholeArchivePath(runtime: NativeRuntime, path: String): Seq[String] = runtime match
    case Darwin(_)                           => Seq(s"-Wl,-force_load,$path")
    case Windows(_, ABI.Msvc)                => Seq(path, s"-Wl,/WHOLEARCHIVE:$path")
    case Windows(_, ABI.MinGw) | Linux(_, _) => Seq("-Wl,--whole-archive", path, "-Wl,--no-whole-archive")

  private[sbt] def wholeArchiveName(runtime: NativeRuntime, name: String): Seq[String] = runtime match
    case Windows(_, ABI.Msvc)                => Seq(s"-l$name", s"-Wl,/WHOLEARCHIVE:$name")
    case Windows(_, ABI.MinGw) | Linux(_, _) => Seq("-Wl,--whole-archive", s"-l$name", "-Wl,--no-whole-archive")
    case Darwin(_)                           => Seq.empty
end Modifier
