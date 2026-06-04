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

import sbt.io.IO
import sbt.util.Logger

import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale

import scala.collection.mutable.LinkedHashMap

/** A third-party native library's resolved compliance facts, ready for SPDX generation. `relationship` is resolved
  * (never [[Relationship.Auto]]); `sourceRoot` is the root that bundled files are resolved against (the project, a
  * local vendor directory, or - for a `Git` source - its clone).
  */
final private[sbt] case class LibrarySpec(
  name: String,
  identity: Option[String],
  version: Option[String],
  relationship: Relationship,
  license: String,
  texts: Seq[LicenseText],
  notices: Seq[File],
  source: Option[URI],
  writtenOffer: Option[String],
  copyright: Option[String],
  originator: Option[String],
  contains: Seq[Component],
  sourceRoot: File)

/** The artefact a generated SPDX document is rooted at: the project publishing it. */
final private[sbt] case class ArtifactInfo(name: String, identity: String, version: Option[String], namespace: String)

/** Generates an artefact's `META-INF/native-licenses/native-licenses.spdx.json` (a deterministic SPDX 2.3 document
  * rooted at the artefact, linking the native libraries it ships and the components they contain) and the accompanying
  * licence texts and notices. Only libraries linked into the artefact contribute - a build-only `DependsOn` does not.
  * File resolution is network-free: a missing file (for example a `Git` source not yet built) is a clear error, never
  * an automatic fetch. Files are written only when their content changes, so a regenerated-but-identical output does
  * not invalidate packaging.
  */
private[sbt] object LicenseGenerator:

  val document: String = "native-licenses.spdx.json"

  private val shipped = Set[Relationship](Relationship.StaticLink, Relationship.DynamicLink, Relationship.Contains)

  /** Generate the SPDX document, licence texts, and notices for `specs` under `outputDir`, returning every file
    * written.
    */
  def generate(artifact: ArtifactInfo, specs: Seq[LibrarySpec], outputDir: File, log: Logger): Seq[File] =
    val active = specs.filter(spec => shipped.contains(spec.relationship) && declared(spec))
    if active.isEmpty then Nil
    else
      IO.createDirectory(outputDir)
      val files = LinkedHashMap.empty[File, String]
      val extracted = LinkedHashMap.empty[String, String]
      val packages = Vector.newBuilder[SpdxPackage]
      val relationships = Vector.newBuilder[SpdxRelationship]
      val artifactId = Spdx.identifier(artifact.identity)
      packages += SpdxPackage(
        artifactId,
        artifact.name,
        artifact.version,
        Spdx.noassertion,
        Spdx.noassertion,
        None,
        None,
        None,
        Some(artifact.identity),
        Vector.empty)
      active.foreach: spec =>
        val libId = identifier(spec.identity, spec.name, spec.version)
        packages += packageOf(
          spec.name,
          spec.identity,
          spec.version,
          spec.license,
          spec.copyright,
          spec.originator,
          spec.source,
          spec.writtenOffer,
          spec.texts,
          spec.notices,
          spec.sourceRoot,
          libId,
          outputDir,
          files,
          extracted
        )
        relationships += SpdxRelationship(artifactId, libId, token(spec.relationship))
        spec.contains.foreach: component =>
          val componentId = identifier(component.identity, component.name, None)
          packages += packageOf(
            component.name,
            component.identity,
            None,
            component.license,
            component.copyright,
            component.originator,
            component.source,
            component.writtenOffer,
            component.texts,
            component.notices,
            spec.sourceRoot,
            componentId,
            outputDir,
            files,
            extracted
          )
          relationships += SpdxRelationship(libId, componentId, "CONTAINS")
      val graph = SpdxGraph(packages.result(), relationships.result(), extracted.toVector.map((id, text) => SpdxExtracted(id, text)))
      val rendered = Spdx.render(SpdxInfo(artifact.name, artifact.namespace), Vector(artifactId), graph)
      log.info(
        s"snx: wrote the native-licence SPDX document (${active.size} ${if active.size == 1 then "library" else "libraries"}) to $outputDir")
      val written = files.toSeq.map((file, content) => writeIfChanged(file, content))
      writeIfChanged(new File(outputDir, document), rendered) +: written
    end if
  end generate

  private def declared(spec: LibrarySpec): Boolean = spec.license.nonEmpty || spec.texts.nonEmpty || spec.contains.nonEmpty

  /** Build one SPDX package, bundling its texts (a non-listed `LicenseRef-` text also becomes an extracted-licensing
    * entry) and notices (embedded as attribution texts).
    */
  private def packageOf(
    name: String,
    identity: Option[String],
    version: Option[String],
    license: String,
    copyright: Option[String],
    originator: Option[String],
    source: Option[URI],
    writtenOffer: Option[String],
    texts: Seq[LicenseText],
    notices: Seq[File],
    sourceRoot: File,
    id: String,
    outputDir: File,
    files: LinkedHashMap[File, String],
    extracted: LinkedHashMap[String, String]): SpdxPackage =
    // A LicenseRef is document-scoped, so namespace it by this package's identity: two libraries that both author
    // a "LicenseRef-license" for different texts must not collide (and silently drop one) when binaries merge.
    val key = id.stripPrefix("SPDXRef-Package-")
    def namespaced(ref: String): String = s"LicenseRef-$key-${ref.stripPrefix("LicenseRef-")}"
    texts.foreach: text =>
      val content = bundle(name, key, text.file, sourceRoot, outputDir, files)
      val ref = namespaced(text.id)
      if text.id.startsWith("LicenseRef-") && !extracted.contains(ref) then extracted.update(ref, content)
    val expression = texts
      .map(_.id)
      .filter(_.startsWith("LicenseRef-"))
      .distinct
      .foldLeft(license)((current, ref) =>
        current.replaceAll(
          java.util.regex.Pattern.quote(ref).nn + "(?![A-Za-z0-9.-])",
          java.util.regex.Matcher.quoteReplacement(namespaced(ref))))
    val attribution = notices.map(notice => bundle(name, key, notice, sourceRoot, outputDir, files))
    SpdxPackage(
      id,
      name,
      version,
      source.map(_.toString).getOrElse(Spdx.noassertion),
      if expression.isEmpty then Spdx.noassertion else expression,
      copyright,
      originator,
      writtenOffer.map(offer => s"Written offer for source: $offer"),
      identity,
      attribution.toVector
    )
  end packageOf

  /** Resolve a bundled file network-free (relative to the library's source root, or absolute), stage it under
    * `outputDir` named by the package `key` so distinct packages that share a display name never overwrite each other,
    * and return its content. `name` is the human library name, used only for the not-found error.
    */
  private def bundle(name: String, key: String, path: File, sourceRoot: File, outputDir: File, files: LinkedHashMap[File, String]): String =
    val file = if path.isAbsolute then path else new File(sourceRoot, path.getPath)
    if !file.isFile then
      sys.error(
        s"snx: licence file not found for '$name': ${file.getAbsolutePath}. For a Git source, build the vendored library first so its clone exists, or vendor the licence text into your project.")
    val content = IO.read(file, StandardCharsets.UTF_8)
    files.update(new File(outputDir, s"$key-${file.getName}"), content)
    content

  private def identifier(identity: Option[String], name: String, version: Option[String]): String =
    Spdx.identifier(identity.getOrElse(version.fold(normalise(name))(revision => s"${normalise(name)}@$revision")))

  private def normalise(value: String): String =
    value.toLowerCase(Locale.US).nn.replaceAll("[^a-z0-9]+", "-").nn.stripPrefix("-").stripSuffix("-")

  private def token(relationship: Relationship): String = relationship match
    case Relationship.StaticLink  => "STATIC_LINK"
    case Relationship.DynamicLink => "DYNAMIC_LINK"
    case Relationship.DependsOn   => "DEPENDS_ON"
    case Relationship.Contains    => "CONTAINS"
    case Relationship.Auto        => "STATIC_LINK"

  private def writeIfChanged(file: File, content: String): File =
    val current = if file.isFile then Some(IO.read(file, StandardCharsets.UTF_8)) else None
    if !current.contains(content) then IO.write(file, content, StandardCharsets.UTF_8)
    file
end LicenseGenerator
