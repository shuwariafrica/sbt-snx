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
import sbt.util.Level
import sbt.util.Logger

import java.io.File
import java.nio.charset.StandardCharsets

/** A logger that records every message, so a test can assert that a warning was emitted. */
final class CapturingLogger extends Logger:
  val messages: scala.collection.mutable.ListBuffer[String] = scala.collection.mutable.ListBuffer.empty
  def trace(t: => Throwable): Unit = ()
  def success(message: => String): Unit = ()
  def log(level: Level.Value, message: => String): Unit = messages.append(message)

class LicenseSuite extends munit.FunSuite:

  private val artifact: ArtifactInfo =
    ArtifactInfo("bin", "pkg:maven/org/bin@1.0", Some("1.0"), "https://spdx.org/spdxdocs/bin")

  private def spec(name: String, identity: String, root: File, license: String, textPath: String): LibrarySpec =
    LibrarySpec(
      name,
      Some(identity),
      Some("1.0"),
      Relationship.StaticLink,
      license,
      Seq(LicenseText(license, new File(textPath))),
      Nil,
      None,
      None,
      None,
      None,
      Nil,
      root)

  test("a LicenseRef token reused by two libraries is namespaced so both texts survive"):
    IO.withTemporaryDirectory: root =>
      IO.write(new File(root, "a/LICENSE"), "Alpha licence text", StandardCharsets.UTF_8)
      IO.write(new File(root, "b/LICENSE"), "Beta licence text", StandardCharsets.UTF_8)
      val out = new File(root, "out")
      val specs = Seq(
        spec("shared", "pkg:generic/alpha@1.0", root, "LicenseRef-shared", "a/LICENSE"),
        spec("shared", "pkg:generic/beta@1.0", root, "LicenseRef-shared", "b/LICENSE")
      )
      val _ = LicenseGenerator.generate(artifact, specs, out, new CapturingLogger)
      val doc = IO.read(new File(out, LicenseGenerator.document)).replaceAll("\\s+", "")
      assert(doc.contains("LicenseRef-pkg-generic-alpha-1.0-shared"), doc)
      assert(doc.contains("LicenseRef-pkg-generic-beta-1.0-shared"), doc)
      assert(doc.contains("Alphalicencetext"), doc)
      assert(doc.contains("Betalicencetext"), doc)

  test("two packages sharing a display name bundle their texts under distinct identity-keyed filenames"):
    IO.withTemporaryDirectory: root =>
      IO.write(new File(root, "a/LICENSE"), "Alpha text", StandardCharsets.UTF_8)
      IO.write(new File(root, "b/LICENSE"), "Beta text", StandardCharsets.UTF_8)
      val out = new File(root, "out")
      val specs = Seq(
        spec("zlib", "pkg:generic/zlib@1.2", root, "Zlib", "a/LICENSE"),
        spec("zlib", "pkg:generic/zlib@1.3", root, "Zlib", "b/LICENSE"))
      val _ = LicenseGenerator.generate(artifact, specs, out, new CapturingLogger)
      val bundled = Option(out.listFiles).getOrElse(Array.empty[File]).filter(_.getName.nn != LicenseGenerator.document)
      val contents = bundled.toSeq.map(file => IO.read(file, StandardCharsets.UTF_8))
      assertEquals(bundled.length, 2)
      assert(contents.contains("Alpha text"), contents.toString)
      assert(contents.contains("Beta text"), contents.toString)

  private val marker = "META-INF/native-licenses/native-licenses.spdx.json"

  test("aggregation warns and skips an unreadable document in a dependency jar instead of dropping it silently"):
    IO.withTemporaryDirectory: root =>
      val brokenMarker = new File(root, "marker.json")
      IO.write(brokenMarker, "{ not valid json", StandardCharsets.UTF_8)
      val jar = new File(root, "dep.jar")
      val manifest = new java.util.jar.Manifest()
      manifest.getMainAttributes.nn.putValue("Manifest-Version", "1.0")
      IO.jar(Seq(brokenMarker -> marker), jar, manifest, None)
      assert(!jar.isDirectory, "the classpath entry must be a file so it routes through the jar reader")
      val out = new File(root, "out")
      val log = new CapturingLogger
      val report = LicenseAggregator.aggregate(Seq(jar), artifact, out, log)
      assert(report.isFile, "an aggregate report is still written")
      assert(log.messages.exists(_.contains("unreadable native-licence document")), log.messages.toString)

  test("aggregation fails on an unreadable document in a local products directory rather than skipping it"):
    IO.withTemporaryDirectory: root =>
      val entry = new File(root, "broken")
      IO.write(new File(entry, marker), "{ not valid json", StandardCharsets.UTF_8)
      val out = new File(root, "out")
      val error = intercept[RuntimeException](LicenseAggregator.aggregate(Seq(entry), artifact, out, new CapturingLogger))
      assert(error.getMessage.nn.contains("a local native-licence document is unreadable"), error.getMessage)

  test("generation warns when an expression references a LicenseRef without supplying its text"):
    IO.withTemporaryDirectory: root =>
      val out = new File(root, "out")
      val log = new CapturingLogger
      val unbacked =
        LibrarySpec(
          "acme",
          Some("pkg:generic/acme@1"),
          Some("1"),
          Relationship.StaticLink,
          "LicenseRef-acme",
          Nil,
          Nil,
          None,
          None,
          None,
          None,
          Nil,
          root)
      val _ = LicenseGenerator.generate(artifact, Seq(unbacked), out, log)
      assert(
        log.messages.exists(message => message.contains("LicenseRef-acme") && message.contains("no licence text")),
        log.messages.toString)

  test("hasMarkers detects a native-licence document in a directory or a jar, gating automatic aggregation"):
    IO.withTemporaryDirectory: root =>
      val manifest = new java.util.jar.Manifest()
      manifest.getMainAttributes.nn.putValue("Manifest-Version", "1.0")
      val emptyDir = new File(root, "empty")
      IO.createDirectory(emptyDir)
      val markedDir = new File(root, "marked")
      IO.write(new File(markedDir, marker), "{}", StandardCharsets.UTF_8)
      val plainJar = new File(root, "plain.jar")
      IO.jar(Seq.empty[(File, String)], plainJar, manifest, None)
      val markedJar = new File(root, "marked.jar")
      val source = new File(root, "doc.json")
      IO.write(source, "{}", StandardCharsets.UTF_8)
      IO.jar(Seq(source -> marker), markedJar, manifest, None)
      assert(!LicenseAggregator.hasMarkers(Seq(emptyDir, plainJar)), "no markers expected on an empty classpath")
      assert(LicenseAggregator.hasMarkers(Seq(emptyDir, markedDir)), "a directory marker should be detected")
      assert(LicenseAggregator.hasMarkers(Seq(plainJar, markedJar)), "a jar marker should be detected")
end LicenseSuite
