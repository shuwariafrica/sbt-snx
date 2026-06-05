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

/** The licensing of a piece of third-party native software: the SPDX licence expression it is offered under and the
  * texts backing it, the attribution notices, copyright, and originator to reproduce, where its source is available or
  * offered, and a package identity used to deduplicate it. Both a linked library (via [[Compliance]]) and a contained
  * component ([[Component]]) carry one. See [[Licensing$ Licensing]].
  */
final case class Licensing(
  license: String,
  texts: Seq[LicenseText],
  notices: Seq[File],
  source: Option[URI],
  writtenOffer: Option[String],
  identity: Option[String],
  copyright: Option[String],
  originator: Option[String])

/** Equality instance and the empty value for [[Licensing]]. */
object Licensing:
  given CanEqual[Licensing, Licensing] = CanEqual.derived

  /** Alias for [[empty]]: no declared licensing. */
  def apply(): Licensing = empty

  /** No declared licensing. */
  val empty: Licensing = Licensing("", Nil, Nil, None, None, None, None, None)

/** Capability of a declaration to carry and fluently refine its [[Licensing]]. Mixed into [[Component]] and, through
  * [[Licensed]], into the native library declarations, so the per-field setters are defined once.
  */
trait Documented[Self]:
  def licensing: Licensing
  protected def withLicensing(updated: Licensing): Self

  /** Declare a single-identifier licence with its bundled text (relative to the declaration's source root). */
  def licensed(license: String, text: File): Self =
    withLicensing(licensing.copy(license = license, texts = Seq(LicenseText(license, text))))

  /** Declare the SPDX licence expression and the texts backing it; for a listed licence the texts may be omitted. */
  def licensed(license: String, texts: LicenseText*): Self =
    withLicensing(licensing.copy(license = license, texts = texts.toSeq))

  /** Attach attribution notices (for example an Apache `NOTICE` file) to reproduce alongside the licence. */
  def notice(files: File*): Self = withLicensing(licensing.copy(notices = licensing.notices ++ files))

  /** Declare where the corresponding source is available. */
  def source(uri: URI): Self = withLicensing(licensing.copy(source = Some(uri)))

  /** Declare a written offer for source - contact details, kept separate from a direct [[source]] link. */
  def writtenOffer(contact: String): Self = withLicensing(licensing.copy(writtenOffer = Some(contact)))

  /** Declare a package identity (a Package URL) used to deduplicate this library when binaries are aggregated. */
  def identity(purl: String): Self = withLicensing(licensing.copy(identity = Some(purl)))

  def copyright(notice: String): Self = withLicensing(licensing.copy(copyright = Some(notice)))

  /** Attach an originator - the upstream author or organisation. */
  def originator(who: String): Self = withLicensing(licensing.copy(originator = Some(who)))
end Documented

/** Capability of a native library declaration - [[NativeDependency]] or [[NativeSource]] - to carry its full
  * [[Compliance]]: its [[Licensing]] plus how it links and the components it bundles.
  */
trait Licensed[Self] extends Documented[Self]:
  def compliance: Compliance
  protected def withCompliance(updated: Compliance): Self

  final def licensing: Licensing = compliance.licensing
  final protected def withLicensing(updated: Licensing): Self = withCompliance(compliance.copy(licensing = updated))

  /** Override how this library links into the binary; otherwise it is resolved from its kind (a built library links
    * statically, a `System` library dynamically).
    */
  def relationship(relationship: Relationship): Self = withCompliance(compliance.copy(relationship = relationship))

  /** Declare third-party components this library bundles, each contained within it (an SPDX `CONTAINS`). */
  def bundles(components: Component*): Self = withCompliance(compliance.copy(contains = compliance.contains ++ components))

/** A third-party component bundled inside a licensed library (for example a C library that vendors its own copies of
  * other libraries): its name and [[Licensing]]. It is contained by its parent (an SPDX `CONTAINS` relationship), so
  * it carries no link relationship of its own. See [[Component$ Component]].
  */
final case class Component(name: String, licensing: Licensing) extends Documented[Component]:
  export licensing.*
  protected def withLicensing(updated: Licensing): Component = copy(licensing = updated)

/** Factories for [[Component]]. */
object Component:
  given CanEqual[Component, Component] = CanEqual.derived

  /** A component under a single-identifier licence with its bundled text (relative to the parent's source root). */
  def apply(name: String, license: String, text: File): Component =
    Component(name, Licensing.empty.copy(license = license, texts = Seq(LicenseText(license, text))))

  /** A component under an SPDX licence expression with the texts backing it. */
  def apply(name: String, license: String, texts: LicenseText*): Component =
    Component(name, Licensing.empty.copy(license = license, texts = texts.toSeq))

/** The licence-compliance metadata a native library declares: its [[Licensing]], how it links into the binary, and any
  * third-party components it bundles. Its [[Licensing]] members are re-exported, so they read flatly off the compliance
  * value. See [[Compliance$ Compliance]].
  */
final case class Compliance(licensing: Licensing, relationship: Relationship, contains: Seq[Component]):
  export licensing.*

/** Equality instance and the empty value for [[Compliance]]. */
object Compliance:
  given CanEqual[Compliance, Compliance] = CanEqual.derived

  /** Alias for [[empty]]: empty licensing and [[Relationship.Auto]]. */
  def apply(): Compliance = empty

  /** No declared compliance metadata: empty licensing and [[Relationship.Auto]]. */
  val empty: Compliance = Compliance(Licensing.empty, Relationship.Auto, Nil)
