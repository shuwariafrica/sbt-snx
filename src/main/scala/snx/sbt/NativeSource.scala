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

import snx.NativePlatform

/** A native dependency built from source - the source-built counterpart to [[NativeDependency]] (which is resolved
  * from a `ModuleID`). Carries the library `name` and the per-platform additive [[NativeOptions]] every variant
  * contributes. See [[NativeSource$ NativeSource]] for its variants and factories.
  */
sealed trait NativeSource:
  def name: String
  def nativeOptions: PartialFunction[NativePlatform, NativeOptions]

  /** Attach the per-platform additive options this source contributes; unmatched platforms contribute none. */
  infix def options(bundle: PartialFunction[NativePlatform, NativeOptions]): NativeSource = this match
    case s: NativeSource.Git    => s.copy(nativeOptions = bundle)
    case s: NativeSource.Local  => s.copy(nativeOptions = bundle)
    case s: NativeSource.System => s.copy(nativeOptions = bundle)

  private[sbt] def optionsFor(platform: NativePlatform): NativeOptions =
    nativeOptions.applyOrElse(platform, (_: NativePlatform) => NativeOptions.empty)

/** Variants and factories for [[NativeSource]]. */
object NativeSource:
  given CanEqual[NativeSource, NativeSource] = CanEqual.derived

  /** Built from a git repository fetched at a pinned `ref` - a tag or commit SHA. */
  final case class Git(
    name: String,
    uri: String,
    ref: String,
    backend: NativeBackend,
    nativeOptions: PartialFunction[NativePlatform, NativeOptions])
      extends NativeSource

  /** Built from a local directory, defaulting to `vendor/<name>` when `directory` is empty. */
  final case class Local(
    name: String,
    directory: Option[File],
    backend: NativeBackend,
    nativeOptions: PartialFunction[NativePlatform, NativeOptions])
      extends NativeSource

  /** Linked from a library already installed on the system; nothing is built. */
  final case class System(name: String, nativeOptions: PartialFunction[NativePlatform, NativeOptions]) extends NativeSource

  object Git:
    def apply(name: String, uri: String, ref: String, backend: NativeBackend): Git =
      Git(name, uri, ref, backend, PartialFunction.empty)

  object Local:
    def apply(name: String, backend: NativeBackend): Local =
      Local(name, None, backend, PartialFunction.empty)

    def apply(name: String, directory: File, backend: NativeBackend): Local =
      Local(name, Some(directory), backend, PartialFunction.empty)

  object System:
    def apply(name: String): System =
      System(name, PartialFunction.empty)
end NativeSource
