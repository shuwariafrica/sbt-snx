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

import sjsonnew.Builder
import sjsonnew.JsonFormat
import sjsonnew.Unbuilder
import sjsonnew.deserializationError
import sjsonnew.support.scalajson.unsafe.Converter
import sjsonnew.support.scalajson.unsafe.Parser
import sjsonnew.support.scalajson.unsafe.PrettyPrinter

/** A package in the SPDX graph: a third-party library or the artifact/binary that links it. `id` is the SPDX
  * identifier (derived from the package identity, so the same package has one id across documents and merges by union).
  */
final private[sbt] case class SpdxPackage(
  id: String,
  name: String,
  version: Option[String],
  downloadLocation: String,
  license: String,
  copyright: Option[String],
  originator: Option[String],
  comment: Option[String],
  purl: Option[String],
  notices: Vector[String])

/** A relationship between two SPDX packages (`from`/`to` are SPDX identifiers, `kind` an SPDX relationship type). */
final private[sbt] case class SpdxRelationship(from: String, to: String, kind: String)

/** A non-listed licence's text, carried in the document so it travels with the binary (`licenseId` is a
  * `LicenseRef-` token).
  */
final private[sbt] case class SpdxExtracted(licenseId: String, extractedText: String)

/** The SPDX graph an artifact contributes or a binary aggregates: its packages, the relationships between them, and the
  * extracted texts of any non-listed licences. Documents merge by union and identifier deduplication. See
  * [[SpdxGraph$ SpdxGraph]].
  */
final private[sbt] case class SpdxGraph(
  packages: Vector[SpdxPackage],
  relationships: Vector[SpdxRelationship],
  extracted: Vector[SpdxExtracted])

/** The empty graph and the union of several graphs. */
private[sbt] object SpdxGraph:
  val empty: SpdxGraph = SpdxGraph(Vector.empty, Vector.empty, Vector.empty)

  /** Union several graphs: packages deduplicate by identifier (merging present fields and the union of notices),
    * relationships and extracted texts by value, so the same library reaching the binary by several paths becomes one
    * package keeping every relationship edge.
    */
  def merge(graphs: Seq[SpdxGraph]): SpdxGraph =
    val packages = scala.collection.mutable.LinkedHashMap.empty[String, SpdxPackage]
    graphs.foreach(_.packages.foreach(pkg => packages.updateWith(pkg.id)(existing => Some(existing.fold(pkg)(mergePackage(_, pkg))))))
    SpdxGraph(
      packages.values.toVector,
      graphs.flatMap(_.relationships).distinct.toVector,
      graphs.flatMap(_.extracted).distinctBy(_.licenseId).toVector
    )

  private def mergePackage(a: SpdxPackage, b: SpdxPackage): SpdxPackage =
    SpdxPackage(
      a.id,
      a.name,
      a.version.orElse(b.version),
      if a.downloadLocation == Spdx.noassertion then b.downloadLocation else a.downloadLocation,
      if a.license == Spdx.noassertion then b.license else a.license,
      a.copyright.orElse(b.copyright),
      a.originator.orElse(b.originator),
      a.comment.orElse(b.comment),
      a.purl.orElse(b.purl),
      (a.notices ++ b.notices).distinct
    )
end SpdxGraph

/** The document-level facts of an SPDX report: the binary it describes and the document namespace. */
final private[sbt] case class SpdxInfo(name: String, namespace: String)

/** Renders and parses SPDX 2.3 JSON documents from an [[SpdxGraph]]. The document is deterministic - a fixed creation
  * date and a caller-supplied namespace - so a regenerated-but-identical report is byte-identical and does not
  * invalidate packaging. Parsing reads back the subset this emitter writes.
  */
private[sbt] object Spdx:

  val noassertion: String = "NOASSERTION"
  private val spdxVersion: String = "SPDX-2.3"
  // A fixed creation date keeps the emitted document reproducible; the report is not a record of when it was run.
  private val created: String = "1980-01-01T00:00:00Z"

  /** A unique, syntactically valid SPDX identifier derived from a package identity (a Package URL) or, failing that, a
    * name and version - so the same package has one identifier across documents and merges by union.
    */
  def identifier(key: String): String = s"SPDXRef-Package-${sanitise(key)}"

  private def sanitise(value: String): String = value.replaceAll("[^A-Za-z0-9.-]", "-").nn

  /** Render the SPDX document describing `describes` (the root package identifiers) over `graph`. */
  def render(info: SpdxInfo, describes: Seq[String], graph: SpdxGraph): String =
    val document = Document(
      spdxVersion,
      "CC0-1.0",
      "SPDXRef-DOCUMENT",
      info.name,
      info.namespace,
      CreationInfo(created, Vector("Tool: sbt-native-extras")),
      describes.toVector,
      graph.packages,
      graph.relationships,
      graph.extracted
    )
    PrettyPrinter(Converter.toJsonUnsafe(document))

  /** Parse an SPDX document produced by [[render]] back into its graph and the identifiers it describes (its roots). */
  def parse(text: String): (SpdxGraph, Vector[String]) =
    val document = Converter.fromJsonUnsafe[Document](Parser.parseUnsafe(text))
    (SpdxGraph(document.packages, document.relationships, document.extracted), document.documentDescribes)

  final private case class CreationInfo(created: String, creators: Vector[String])

  final private case class Document(
    spdxVersion: String,
    dataLicense: String,
    spdxId: String,
    name: String,
    documentNamespace: String,
    creationInfo: CreationInfo,
    documentDescribes: Vector[String],
    packages: Vector[SpdxPackage],
    relationships: Vector[SpdxRelationship],
    extracted: Vector[SpdxExtracted])

  import sjsonnew.BasicJsonProtocol.given

  private def optional(value: Option[String]): Option[String] = value.filterNot(_ == noassertion)

  private given JsonFormat[SpdxPackage] = new JsonFormat[SpdxPackage]:
    def read[J](js: Option[J], unbuilder: Unbuilder[J]): SpdxPackage = js match
      case Some(value) =>
        val _ = unbuilder.beginObject(value)
        val id = unbuilder.readField[String]("SPDXID")
        val name = unbuilder.readField[String]("name")
        val version = unbuilder.readField[Option[String]]("versionInfo")
        val downloadLocation = unbuilder.readField[Option[String]]("downloadLocation").getOrElse(noassertion)
        val license = unbuilder.readField[Option[String]]("licenseDeclared").getOrElse(noassertion)
        val copyright = optional(unbuilder.readField[Option[String]]("copyrightText"))
        val originator = unbuilder.readField[Option[String]]("originator")
        val comment = unbuilder.readField[Option[String]]("comment")
        val purl = unbuilder.readField[Vector[Reference]]("externalRefs").find(_.referenceType == "purl").map(_.referenceLocator)
        val notices = unbuilder.readField[Vector[String]]("attributionTexts")
        unbuilder.endObject()
        SpdxPackage(id, name, version, downloadLocation, license, copyright, originator, comment, purl, notices)
      case None => deserializationError("Expected package object but found None")
    def write[J](obj: SpdxPackage, builder: Builder[J]): Unit =
      builder.beginObject()
      builder.addField("SPDXID", obj.id)
      builder.addField("name", obj.name)
      builder.addField("versionInfo", obj.version)
      builder.addField("downloadLocation", obj.downloadLocation)
      builder.addField("filesAnalyzed", false)
      builder.addField("licenseConcluded", noassertion)
      builder.addField("licenseDeclared", obj.license)
      builder.addField("copyrightText", obj.copyright.getOrElse(noassertion))
      builder.addField("originator", obj.originator)
      builder.addField("comment", obj.comment)
      obj.purl.foreach(locator => builder.addField("externalRefs", Vector(Reference("PACKAGE-MANAGER", "purl", locator))))
      if obj.notices.nonEmpty then builder.addField("attributionTexts", obj.notices)
      builder.endObject()

  private given JsonFormat[SpdxRelationship] = new JsonFormat[SpdxRelationship]:
    def read[J](js: Option[J], unbuilder: Unbuilder[J]): SpdxRelationship = js match
      case Some(value) =>
        val _ = unbuilder.beginObject(value)
        val from = unbuilder.readField[String]("spdxElementId")
        val to = unbuilder.readField[String]("relatedSpdxElement")
        val kind = unbuilder.readField[String]("relationshipType")
        unbuilder.endObject()
        SpdxRelationship(from, to, kind)
      case None => deserializationError("Expected relationship object but found None")
    def write[J](obj: SpdxRelationship, builder: Builder[J]): Unit =
      builder.beginObject()
      builder.addField("spdxElementId", obj.from)
      builder.addField("relatedSpdxElement", obj.to)
      builder.addField("relationshipType", obj.kind)
      builder.endObject()

  private given JsonFormat[SpdxExtracted] = new JsonFormat[SpdxExtracted]:
    def read[J](js: Option[J], unbuilder: Unbuilder[J]): SpdxExtracted = js match
      case Some(value) =>
        val _ = unbuilder.beginObject(value)
        val licenseId = unbuilder.readField[String]("licenseId")
        val extractedText = unbuilder.readField[String]("extractedText")
        unbuilder.endObject()
        SpdxExtracted(licenseId, extractedText)
      case None => deserializationError("Expected extracted-licensing object but found None")
    def write[J](obj: SpdxExtracted, builder: Builder[J]): Unit =
      builder.beginObject()
      builder.addField("licenseId", obj.licenseId)
      builder.addField("extractedText", obj.extractedText)
      builder.endObject()

  private given JsonFormat[Reference] = new JsonFormat[Reference]:
    def read[J](js: Option[J], unbuilder: Unbuilder[J]): Reference = js match
      case Some(value) =>
        val _ = unbuilder.beginObject(value)
        val category = unbuilder.readField[String]("referenceCategory")
        val refType = unbuilder.readField[String]("referenceType")
        val locator = unbuilder.readField[String]("referenceLocator")
        unbuilder.endObject()
        Reference(category, refType, locator)
      case None => deserializationError("Expected external-reference object but found None")
    def write[J](obj: Reference, builder: Builder[J]): Unit =
      builder.beginObject()
      builder.addField("referenceCategory", obj.category)
      builder.addField("referenceType", obj.referenceType)
      builder.addField("referenceLocator", obj.referenceLocator)
      builder.endObject()

  private given JsonFormat[CreationInfo] = new JsonFormat[CreationInfo]:
    def read[J](js: Option[J], unbuilder: Unbuilder[J]): CreationInfo = js match
      case Some(value) =>
        val _ = unbuilder.beginObject(value)
        val created = unbuilder.readField[String]("created")
        val creators = unbuilder.readField[Vector[String]]("creators")
        unbuilder.endObject()
        CreationInfo(created, creators)
      case None => deserializationError("Expected creationInfo object but found None")
    def write[J](obj: CreationInfo, builder: Builder[J]): Unit =
      builder.beginObject()
      builder.addField("created", obj.created)
      builder.addField("creators", obj.creators)
      builder.endObject()

  private given JsonFormat[Document] = new JsonFormat[Document]:
    def read[J](js: Option[J], unbuilder: Unbuilder[J]): Document = js match
      case Some(value) =>
        val _ = unbuilder.beginObject(value)
        val spdxVersion = unbuilder.readField[Option[String]]("spdxVersion").getOrElse("")
        val dataLicense = unbuilder.readField[Option[String]]("dataLicense").getOrElse("")
        val spdxId = unbuilder.readField[Option[String]]("SPDXID").getOrElse("")
        val name = unbuilder.readField[Option[String]]("name").getOrElse("")
        val namespace = unbuilder.readField[Option[String]]("documentNamespace").getOrElse("")
        val creationInfo = unbuilder.readField[Option[CreationInfo]]("creationInfo").getOrElse(CreationInfo("", Vector.empty))
        val describes = unbuilder.readField[Vector[String]]("documentDescribes")
        val packages = unbuilder.readField[Vector[SpdxPackage]]("packages")
        val relationships = unbuilder.readField[Vector[SpdxRelationship]]("relationships")
        val extracted = unbuilder.readField[Vector[SpdxExtracted]]("hasExtractedLicensingInfos")
        unbuilder.endObject()
        Document(spdxVersion, dataLicense, spdxId, name, namespace, creationInfo, describes, packages, relationships, extracted)
      case None => deserializationError("Expected SPDX document but found None")
    def write[J](obj: Document, builder: Builder[J]): Unit =
      builder.beginObject()
      builder.addField("spdxVersion", obj.spdxVersion)
      builder.addField("dataLicense", obj.dataLicense)
      builder.addField("SPDXID", obj.spdxId)
      builder.addField("name", obj.name)
      builder.addField("documentNamespace", obj.documentNamespace)
      builder.addField("creationInfo", obj.creationInfo)
      builder.addField("documentDescribes", obj.documentDescribes)
      builder.addField("packages", obj.packages)
      builder.addField("relationships", obj.relationships)
      if obj.extracted.nonEmpty then builder.addField("hasExtractedLicensingInfos", obj.extracted)
      builder.endObject()
end Spdx

/** A package-manager external reference (a Package URL); the bridge between our identity and SPDX `externalRefs`. */
final private[sbt] case class Reference(category: String, referenceType: String, referenceLocator: String)
