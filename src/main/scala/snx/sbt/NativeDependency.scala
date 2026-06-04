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

import sbt.librarymanagement.ModuleID

import java.io.File
import java.net.URI

import snx.NativePlatform
import snx.TargetPlatform

/** A managed native dependency: a `ModuleID`, whether to inject an OS/arch classifier, the per-platform additive
  * [[NativeOptions]] it contributes, and its third-party licence-compliance metadata. Its source-built counterpart is
  * [[NativeSource]]. See [[NativeDependency$ NativeDependency]].
  */
final case class NativeDependency(
  module: ModuleID,
  classified: Boolean,
  nativeOptions: PartialFunction[NativePlatform, NativeOptions],
  compliance: Compliance)
    extends Licensed:

  /** Attach the per-platform additive options; unmatched platforms contribute none. */
  infix def options(bundle: PartialFunction[NativePlatform, NativeOptions]): NativeDependency =
    copy(nativeOptions = bundle)

  /** Resolve as ordinary NIR without an OS/arch classifier, keeping any options. */
  def plain: NativeDependency = copy(classified = false)

  /** Declare a single-identifier licence with its bundled text (relative to the project). */
  def licensed(license: String, text: File): NativeDependency =
    copy(compliance = compliance.copy(license = license, texts = Seq(LicenseText(license, text))))

  /** Declare the SPDX licence expression and the texts backing it; for a listed licence the texts may be omitted. */
  def licensed(license: String, texts: LicenseText*): NativeDependency =
    copy(compliance = compliance.copy(license = license, texts = texts.toSeq))

  /** Override how this dependency links into the binary; otherwise it is resolved (a managed dependency links
    * statically by default).
    */
  def relationship(relationship: Relationship): NativeDependency =
    copy(compliance = compliance.copy(relationship = relationship))

  /** Attach attribution notices (for example an Apache `NOTICE` file) to reproduce alongside the licence. */
  def notice(files: File*): NativeDependency = copy(compliance = compliance.copy(notices = compliance.notices ++ files))

  /** Declare where the corresponding source is available. */
  def source(uri: URI): NativeDependency = copy(compliance = compliance.copy(source = Some(uri)))

  /** Declare a written offer for source - contact details, kept separate from a direct [[source]] link. */
  def writtenOffer(contact: String): NativeDependency = copy(compliance = compliance.copy(writtenOffer = Some(contact)))

  /** Declare a package identity (a Package URL) used to deduplicate this library when binaries are aggregated. */
  def identity(purl: String): NativeDependency = copy(compliance = compliance.copy(identity = Some(purl)))

  def copyright(notice: String): NativeDependency = copy(compliance = compliance.copy(copyright = Some(notice)))

  /** Attach an originator - the upstream author or organisation. */
  def originator(who: String): NativeDependency = copy(compliance = compliance.copy(originator = Some(who)))

  /** Declare third-party components this dependency bundles, each contained within it (an SPDX `CONTAINS`). */
  def bundles(components: Component*): NativeDependency =
    copy(compliance = compliance.copy(contains = compliance.contains ++ components))

  private[sbt] def moduleID(target: TargetPlatform): ModuleID =
    if classified then module.classifier(target.classifier) else module

  private[sbt] def optionsFor(platform: NativePlatform): NativeOptions =
    nativeOptions.applyOrElse(platform, (_: NativePlatform) => NativeOptions.empty)
end NativeDependency

/** Factories for [[NativeDependency]]. */
object NativeDependency:

  given CanEqual[NativeDependency, NativeDependency] = CanEqual.derived

  def apply(module: ModuleID): NativeDependency = NativeDependency(module, true, PartialFunction.empty, Compliance.empty)

  def apply(module: ModuleID, classified: Boolean): NativeDependency =
    NativeDependency(module, classified, PartialFunction.empty, Compliance.empty)
