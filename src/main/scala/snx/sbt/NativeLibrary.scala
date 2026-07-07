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

import sbt.Append
import sbt.librarymanagement.Configuration

import scala.annotation.targetName

import snx.NativeRuntime

/** The link form of a [[NativeLibrary]]: a plain `-l<name>` library, a macOS `-framework`, or a whole-archive
  * force-load of every member.
  */
enum LinkMode derives CanEqual:
  case Plain, Framework, WholeArchive

/** How a project supplies a [[NativeLibrary]]'s symbols: from the system (`System`), built from source (`Vendored`), or
  * compiled in from unmanaged `scala-native/` sources (`Unmanaged`).
  */
enum Provisioning derives CanEqual:
  case System
  case Vendored(spec: snx.sbt.Vendored)
  case Unmanaged

/** A native library a link requires: its linker `name`, its link [[LinkMode mode]], how the project
  * [[Provisioning provisions]] it, whether it has a system default, the configurations it applies to, and its
  * per-platform [[Linkage]] (empty defers to the provisioning default). See [[NativeLibrary$ NativeLibrary]] to
  * construct one.
  */
final case class NativeLibrary private[sbt] (
  name: String,
  mode: LinkMode,
  provisioning: Provisioning,
  systemDefault: Boolean,
  configurations: Option[String],
  linkage: PartialFunction[NativeRuntime, Linkage]
) derives CanEqual:

  /** Force-load every member of this library, including unreferenced ones. */
  def wholeArchive: NativeLibrary = copy(mode = LinkMode.WholeArchive)

  /** Declare that this library has no system default, so an unprovisioned build fails at configuration time. */
  def noSystemDefault: NativeLibrary = copy(systemDefault = false)

  /** Set this library's per-platform linkage; a platform the selector does not match falls to the provisioning
    * default (`System` dynamic, `Vendored` static). A bare `Static`/`Dynamic` lifts to a constant selector through
    * [[Linkage$ Linkage]]'s conversion, as on `SNX.linkage`.
    */
  def linkage(selector: PartialFunction[NativeRuntime, Linkage]): NativeLibrary = copy(linkage = selector)

  /** Restrict this library to a configuration (for example `Test`); without one it applies to every link. */
  @targetName("configuration")
  def %(configuration: Configuration): NativeLibrary = copy(configurations = Some(configuration.name))

  /** Restrict to the named configurations - plain comma-separated names, not sbt's `a->b` mapping syntax. */
  @targetName("configurations")
  def %(configurations: String): NativeLibrary = copy(configurations = Some(configurations))
end NativeLibrary

/** Constructors and the `+=`/`++=` append instances for [[NativeLibrary]]. */
object NativeLibrary:

  /** A system-provisioned library (`-l<name>`). */
  def apply(name: String): NativeLibrary =
    NativeLibrary(name, LinkMode.Plain, Provisioning.System, true, None, PartialFunction.empty)

  /** A library built from source by `vendored`. */
  def apply(name: String, vendored: Vendored): NativeLibrary =
    NativeLibrary(name, LinkMode.Plain, Provisioning.Vendored(vendored), true, None, PartialFunction.empty)

  /** A library under an explicit [[Provisioning]] (for example [[Provisioning.Unmanaged]]). */
  def apply(name: String, provisioning: Provisioning): NativeLibrary =
    NativeLibrary(name, LinkMode.Plain, provisioning, true, None, PartialFunction.empty)

  /** A macOS framework (`-framework <name>`), contributing nothing elsewhere. */
  def framework(name: String): NativeLibrary =
    NativeLibrary(name, LinkMode.Framework, Provisioning.System, true, None, PartialFunction.empty)

  // In the companion so the instances are in implicit scope at the `SNX.libraries +=` site; the lift adds an
  // unconditional library to every platform's result.
  private type Libraries = PartialFunction[NativeRuntime, Seq[NativeLibrary]]

  given Append.Value[Libraries, NativeLibrary] with
    def appendValue(libraries: Libraries, library: NativeLibrary): Libraries = { case runtime =>
      libraries.applyOrElse(runtime, (_: NativeRuntime) => Seq.empty[NativeLibrary]) :+ library
    }

  given Append.Values[Libraries, Seq[NativeLibrary]] with
    def appendValues(libraries: Libraries, added: Seq[NativeLibrary]): Libraries = { case runtime =>
      libraries.applyOrElse(runtime, (_: NativeRuntime) => Seq.empty[NativeLibrary]) ++ added
    }
end NativeLibrary
