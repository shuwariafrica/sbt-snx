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
import snx.SNXError

/** A per-platform transform of a native configuration `A`, applied where it matches. See [[Modifier$ Modifier]]. */
type Modifier[A] = PartialFunction[NativeRuntime, A => A]

/** Carrier and constructors for [[Modifier]]. */
object Modifier:

  /** Carry a per-platform partial function as a [[Modifier]] so a bare literal types under `+=`. */
  def platform(modifier: PartialFunction[NativeRuntime, Native => Native]): Modifier[Native] = modifier

  /** Force-load every object file of the static archive at `archive`, in each platform's linker syntax. */
  def wholeArchive(archive: File): Modifier[Native] = { case runtime =>
    native => native.linkOptions(wholeArchivePath(runtime, archive.getAbsolutePath.nn)*)
  }

  private[sbt] def wholeArchivePath(runtime: NativeRuntime, path: String): Seq[String] = runtime match
    case Darwin(_)                           => Seq(s"-Wl,-force_load,$path")
    case Windows(_, ABI.Msvc)                => Seq(path, s"-Wl,/WHOLEARCHIVE:$path")
    case Windows(_, ABI.MinGw) | Linux(_, _) => Seq("-Wl,--whole-archive", path, "-Wl,--no-whole-archive")

  private[sbt] def wholeArchiveName(runtime: NativeRuntime, name: String): Seq[String] = runtime match
    case Windows(_, ABI.Msvc)                => Seq(s"-l$name", s"-Wl,/WHOLEARCHIVE:$name")
    case Windows(_, ABI.MinGw) | Linux(_, _) => Seq("-Wl,--whole-archive", s"-l$name", "-Wl,--no-whole-archive")
    case Darwin(_)                           =>
      // macOS `-force_load` force-loads by archive path, not by `-l<name>`, so a whole-archive System library on macOS
      // cannot be rendered by name - it needs a path. Fail fast rather than silently drop it (which would lose the
      // force-load, and every symbol of a library referenced only for its constructors).
      throw SNXError.UnsupportedLinkage( // scalafix:ok DisableSyntax.throw
        s"whole-archive system library '$name' cannot be force-loaded by name on macOS; supply its archive path via " +
          "Modifier.wholeArchive(file), or provision it Vendored (its built archive is force-loaded by path)")
end Modifier
