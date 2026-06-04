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
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

/** Aggregates the per-artefact SPDX documents on a binary's classpath into one SPDX document describing the binary.
  * Each classpath entry - a dependency jar or the project's own products directory - is scanned for the
  * `META-INF/native-licenses/` SPDX document, so the project's own declarations and its dependencies' are gathered
  * uniformly; the binary is rooted over them (it contains each artefact). Packages deduplicate by SPDX identifier and
  * every relationship edge is preserved, so a library reaching the binary by several paths appears once with all its
  * edges.
  */
private[sbt] object LicenseAggregator:

  private val directory = "META-INF/native-licenses/"
  private val document = directory + LicenseGenerator.document

  /** A parsed per-artefact document: its graph, the roots it describes, and the licence text and notice files it
    * ships.
    */
  final private case class Fragment(graph: SpdxGraph, roots: Vector[String], texts: Map[String, String])

  /** Whether any classpath entry carries a native-licence document. Gates the automatic aggregation at link time, so a
    * binary whose classpath declares no third-party native licences produces no report.
    */
  def hasMarkers(classpath: Seq[File]): Boolean =
    classpath.exists(entry => if entry.isDirectory then new File(entry, document).isFile else jarHasMarker(entry))

  private def jarHasMarker(jar: File): Boolean =
    scala.util
      .Try:
        val zip = new ZipFile(jar)
        try Option(zip.getEntry(document)).isDefined
        finally zip.close()
      .getOrElse(false)

  /** Read each classpath document, merge into one SPDX document rooted at `binary`, write it and the bundled texts
    * under `outputDir`, and return the SPDX document file.
    */
  def aggregate(classpath: Seq[File], binary: ArtifactInfo, outputDir: File, log: Logger): File =
    val fragments = classpath.flatMap(entry => if entry.isDirectory then fromDirectory(entry) else fromJar(entry, log))
    val merged = SpdxGraph.merge(fragments.map(_.graph))
    val texts = fragments.flatMap(_.texts).toMap
    val binaryId = Spdx.identifier(binary.identity)
    val binaryPackage =
      SpdxPackage(
        binaryId,
        binary.name,
        binary.version,
        Spdx.noassertion,
        Spdx.noassertion,
        None,
        None,
        None,
        Some(binary.identity),
        Vector.empty)
    // The project's own document is rooted at its own coordinate, which is the binary - skip that self-edge.
    val rootEdges = fragments.flatMap(_.roots).distinct.filterNot(_ == binaryId).map(root => SpdxRelationship(binaryId, root, "CONTAINS"))
    val graph = SpdxGraph(binaryPackage +: merged.packages, merged.relationships ++ rootEdges, merged.extracted)
    IO.delete(outputDir)
    IO.createDirectory(outputDir)
    texts.foreach((name, content) => IO.write(new File(outputDir, name), content, StandardCharsets.UTF_8))
    val out = new File(outputDir, "native-licenses.spdx.json")
    IO.write(out, Spdx.render(SpdxInfo(binary.name, binary.namespace), Vector(binaryId), graph), StandardCharsets.UTF_8)
    log.info(
      s"snx: aggregated ${merged.packages.size} third-party native ${if merged.packages.size == 1 then "package" else "packages"} into $out")
    out
  end aggregate

  /** The fragment from a dependency jar carrying the SPDX document; none if it carries no document. A dependency is
    * outside the build's control, so a present-but-unreadable document is reported and skipped (loud, never silent - a
    * dropped obligation is a compliance risk) rather than failing the whole aggregation; a local one is fatal, see
    * [[fromDirectory]].
    */
  private def fromJar(jar: File, log: Logger): Option[Fragment] =
    scala.util
      .Try(new ZipFile(jar))
      .toOption
      .flatMap: zip =>
        try
          Option(zip.getEntry(document)).flatMap: _ =>
            scala.util.Try:
              val (graph, roots) = Spdx.parse(read(zip, document))
              val texts = entries(zip).filterNot(_ == document).map(name => name.stripPrefix(directory) -> read(zip, name)).toMap
              Fragment(graph, roots, texts)
            match
              case scala.util.Success(fragment) => Some(fragment)
              case scala.util.Failure(error)    =>
                log.warn(s"snx: ignoring an unreadable native-licence document in ${jar.getName}: ${error.getMessage}")
                None
        finally zip.close()

  /** The fragment from a products directory carrying the SPDX document; none if it carries no document. A directory is
    * a local, build-controlled artefact, so a present-but-unreadable document here is fatal - a generation bug to fix,
    * not skipped - unlike a dependency jar's, see [[fromJar]].
    */
  private def fromDirectory(base: File): Option[Fragment] =
    val file = new File(base, document)
    if !file.isFile then None
    else
      scala.util.Try:
        val (graph, roots) = Spdx.parse(IO.read(file, StandardCharsets.UTF_8))
        val texts = Option(new File(base, directory).listFiles)
          .getOrElse(Array.empty[File])
          .filter(text => text.isFile && text.getName != LicenseGenerator.document)
          .map(text => text.getName.nn -> IO.read(text, StandardCharsets.UTF_8))
          .toMap
        Fragment(graph, roots, texts)
      match
        case scala.util.Success(fragment) => Some(fragment)
        case scala.util.Failure(error)    =>
          sys.error(s"snx: a local native-licence document is unreadable: ${file.getAbsolutePath}: ${error.getMessage}")

  /** The names of every entry under the marker directory. */
  private def entries(zip: ZipFile): Seq[String] =
    import scala.jdk.CollectionConverters.*
    zip.entries.asScala.iterator.map(_.getName.nn).filter(name => name.startsWith(directory) && !name.endsWith("/")).toSeq

  /** Read a UTF-8 zip entry. */
  private def read(zip: ZipFile, name: String): String =
    val in = zip.getInputStream(zip.getEntry(name))
    try new String(in.readAllBytes().nn, StandardCharsets.UTF_8)
    finally in.close()
end LicenseAggregator
