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

/** A licence text bundled for a third-party native library: the SPDX licence identifier (or a `LicenseRef-` token for
  * a non-listed licence) and the file holding its text, relative to the library's source root. See
  * [[LicenseText$ LicenseText]].
  */
final case class LicenseText(id: String, file: File)

/** Equality instance for [[LicenseText]]. */
object LicenseText:
  given CanEqual[LicenseText, LicenseText] = CanEqual.derived

/** How a library links into the final binary, mapped to the SPDX relationship and gating whether its obligations
  * transfer to the binary's notices ([[Relationship.StaticLink]]/[[Relationship.DynamicLink]]/[[Relationship.Contains]]
  * ship; [[Relationship.DependsOn]] does not). [[Relationship.Auto]] is resolved from how the library links. See
  * [[Relationship$ Relationship]].
  */
enum Relationship:
  case StaticLink, DynamicLink, DependsOn, Contains, Auto

/** Equality instance for [[Relationship]]. */
object Relationship:
  given CanEqual[Relationship, Relationship] = CanEqual.derived

/** A third-party component bundled inside a licensed library (for example a C library that vendors its own copies of
  * other libraries): its own SPDX licence expression, texts, notices, and provenance. It is contained by its parent
  * (an SPDX `CONTAINS` relationship), so it carries no link relationship of its own. See [[Component$ Component]].
  */
final case class Component(
  name: String,
  license: String,
  texts: Seq[LicenseText],
  notices: Seq[File],
  source: Option[URI],
  writtenOffer: Option[String],
  identity: Option[String],
  copyright: Option[String],
  originator: Option[String]):

  /** Declare a package identity (a Package URL) used to deduplicate this component when binaries are aggregated. */
  def identity(purl: String): Component = copy(identity = Some(purl))

  /** Declare where the corresponding source is available. */
  def source(uri: URI): Component = copy(source = Some(uri))

  /** Declare a written offer for source - contact details, kept separate from a direct [[source]] link. */
  def writtenOffer(contact: String): Component = copy(writtenOffer = Some(contact))

  /** Attach attribution notices (for example an Apache `NOTICE` file) to reproduce alongside the licence. */
  def notice(files: File*): Component = copy(notices = notices ++ files)

  def copyright(notice: String): Component = copy(copyright = Some(notice))

  /** Attach an originator - the upstream author or organisation. */
  def originator(who: String): Component = copy(originator = Some(who))
end Component

/** Factories for [[Component]]. */
object Component:
  given CanEqual[Component, Component] = CanEqual.derived

  /** A component under a single-identifier licence with its bundled text (relative to the parent's source root). */
  def apply(name: String, license: String, text: File): Component =
    Component(name, license, Seq(LicenseText(license, text)), Nil, None, None, None, None, None)

  /** A component under an SPDX licence expression with the texts backing it. */
  def apply(name: String, license: String, texts: LicenseText*): Component =
    Component(name, license, texts.toSeq, Nil, None, None, None, None, None)

/** The licence-compliance metadata a third-party native library declares: the SPDX licence expression it is offered
  * under and the texts backing it, any attribution notices, how it links, where its source is available or offered,
  * a package identity for deduplication, copyright and originator notices, and any third-party components it bundles.
  * See [[Compliance$ Compliance]].
  */
final case class Compliance(
  license: String,
  texts: Seq[LicenseText],
  notices: Seq[File],
  relationship: Relationship,
  source: Option[URI],
  writtenOffer: Option[String],
  identity: Option[String],
  copyright: Option[String],
  originator: Option[String],
  contains: Seq[Component])

/** Equality instance and the empty value for [[Compliance]]. */
object Compliance:
  given CanEqual[Compliance, Compliance] = CanEqual.derived

  /** No declared compliance metadata: an empty licence and [[Relationship.Auto]]. */
  val empty: Compliance = Compliance("", Nil, Nil, Relationship.Auto, None, None, None, None, None, Nil)

/** Capability of a third-party native library declaration - [[NativeDependency]] or [[NativeSource]] - to carry
  * licence-compliance metadata.
  */
trait Licensed:
  def compliance: Compliance
