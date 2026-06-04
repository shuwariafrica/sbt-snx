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
import java.net.URI

import snx.NativePlatform

/** A native dependency built from source - the source-built counterpart to [[NativeDependency]] (which is resolved
  * from a `ModuleID`). Carries the library `name`, the per-platform additive [[NativeOptions]] every variant
  * contributes, and its third-party licence-compliance metadata. See [[NativeSource$ NativeSource]] for its variants
  * and factories.
  */
sealed trait NativeSource extends Licensed:
  def name: String
  def nativeOptions: PartialFunction[NativePlatform, NativeOptions]
  def compliance: Compliance

  /** Attach the per-platform additive options this source contributes; unmatched platforms contribute none. */
  infix def options(bundle: PartialFunction[NativePlatform, NativeOptions]): NativeSource = this match
    case s: NativeSource.Git    => s.copy(nativeOptions = bundle)
    case s: NativeSource.Local  => s.copy(nativeOptions = bundle)
    case s: NativeSource.System => s.copy(nativeOptions = bundle)

  /** Declare a single-identifier licence with its bundled text (relative to the source root). */
  def licensed(license: String, text: File): NativeSource =
    withCompliance(compliance.copy(license = license, texts = Seq(LicenseText(license, text))))

  /** Declare the SPDX licence expression and the texts backing it; for a listed licence the texts may be omitted. */
  def licensed(license: String, texts: LicenseText*): NativeSource =
    withCompliance(compliance.copy(license = license, texts = texts.toSeq))

  /** Override how this source links into the binary; otherwise it is resolved from its kind (a built library links
    * statically, a `System` library dynamically).
    */
  def relationship(relationship: Relationship): NativeSource =
    withCompliance(compliance.copy(relationship = relationship))

  /** Attach attribution notices (for example an Apache `NOTICE` file) to reproduce alongside the licence. */
  def notice(files: File*): NativeSource = withCompliance(compliance.copy(notices = compliance.notices ++ files))

  /** Declare where the corresponding source is available. */
  def source(uri: URI): NativeSource = withCompliance(compliance.copy(source = Some(uri)))

  /** Declare a written offer for source - contact details, kept separate from a direct [[source]] link. */
  def writtenOffer(contact: String): NativeSource = withCompliance(compliance.copy(writtenOffer = Some(contact)))

  /** Declare a package identity (a Package URL) used to deduplicate this library when binaries are aggregated. */
  def identity(purl: String): NativeSource = withCompliance(compliance.copy(identity = Some(purl)))

  def copyright(notice: String): NativeSource = withCompliance(compliance.copy(copyright = Some(notice)))

  /** Attach an originator - the upstream author or organisation. */
  def originator(who: String): NativeSource = withCompliance(compliance.copy(originator = Some(who)))

  /** Declare third-party components this source bundles, each contained within it (an SPDX `CONTAINS`). */
  def bundles(components: Component*): NativeSource =
    withCompliance(compliance.copy(contains = compliance.contains ++ components))

  private def withCompliance(updated: Compliance): NativeSource = this match
    case s: NativeSource.Git    => s.copy(compliance = updated)
    case s: NativeSource.Local  => s.copy(compliance = updated)
    case s: NativeSource.System => s.copy(compliance = updated)

  private[sbt] def optionsFor(platform: NativePlatform): NativeOptions =
    nativeOptions.applyOrElse(platform, (_: NativePlatform) => NativeOptions.empty)
end NativeSource

/** Variants and factories for [[NativeSource]]. */
object NativeSource:
  given CanEqual[NativeSource, NativeSource] = CanEqual.derived

  /** Built from a git repository fetched at `ref` (a tag, commit, or branch). A branch is cloned once then cached, so
    * pin a tag or commit for a reproducible or updatable build.
    */
  final case class Git(
    name: String,
    uri: String,
    ref: String,
    backend: NativeBackend,
    nativeOptions: PartialFunction[NativePlatform, NativeOptions],
    compliance: Compliance)
      extends NativeSource

  /** Built from a local directory, defaulting to `vendor/<name>` when `directory` is empty. */
  final case class Local(
    name: String,
    directory: Option[File],
    backend: NativeBackend,
    nativeOptions: PartialFunction[NativePlatform, NativeOptions],
    compliance: Compliance)
      extends NativeSource

  /** Linked from a library already installed on the system; nothing is built. */
  final case class System(name: String, nativeOptions: PartialFunction[NativePlatform, NativeOptions], compliance: Compliance)
      extends NativeSource

  object Git:
    def apply(name: String, uri: String, ref: String, backend: NativeBackend): Git =
      Git(name, uri, ref, backend, PartialFunction.empty, Compliance.empty)

  object Local:
    def apply(name: String, backend: NativeBackend): Local =
      Local(name, None, backend, PartialFunction.empty, Compliance.empty)

    def apply(name: String, directory: File, backend: NativeBackend): Local =
      Local(name, Some(directory), backend, PartialFunction.empty, Compliance.empty)

  object System:
    def apply(name: String): System =
      System(name, PartialFunction.empty, Compliance.empty)
end NativeSource
