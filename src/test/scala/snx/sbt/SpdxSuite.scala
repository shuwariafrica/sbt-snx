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

class SpdxSuite extends munit.FunSuite:

  test("identifier sanitises a Package URL into a syntactically valid SPDX id"):
    assertEquals(Spdx.identifier("pkg:maven/org.example/name@1.0"), "SPDXRef-Package-pkg-maven-org.example-name-1.0")

  test("render then parse round-trips a populated and an empty package"):
    val full = SpdxPackage(
      "SPDXRef-Package-a",
      "alpha",
      Some("1.0"),
      "https://example.org",
      "MIT",
      Some("Copyright the authors"),
      Some("Person: someone"),
      Some("Written offer for source: contact"),
      Some("pkg:generic/alpha@1.0"),
      Vector("Notice text")
    )
    val sparse =
      SpdxPackage("SPDXRef-Package-b", "beta", None, Spdx.noassertion, Spdx.noassertion, None, None, None, None, Vector.empty)
    val graph = SpdxGraph(
      Vector(full, sparse),
      Vector(SpdxRelationship("SPDXRef-Package-a", "SPDXRef-Package-b", "STATIC_LINK")),
      Vector(SpdxExtracted("LicenseRef-a-token", "extracted text"))
    )
    val (parsed, describes) =
      Spdx.parse(Spdx.render(SpdxInfo("alpha", "https://spdx.org/spdxdocs/alpha"), Vector("SPDXRef-Package-a"), graph))
    assertEquals(describes, Vector("SPDXRef-Package-a"))
    assertEquals(parsed.packages.toSet, graph.packages.toSet)
    assertEquals(parsed.relationships, graph.relationships)
    assertEquals(parsed.extracted, graph.extracted)

  test("merge deduplicates packages by id, preferring present fields and unioning notices and edges"):
    val present =
      SpdxPackage(
        "SPDXRef-Package-z",
        "zlib",
        Some("1.3"),
        "https://zlib.net",
        "Zlib",
        None,
        None,
        None,
        Some("pkg:generic/zlib@1.3"),
        Vector("N1"))
    val sparse =
      SpdxPackage("SPDXRef-Package-z", "zlib", None, Spdx.noassertion, Spdx.noassertion, None, None, None, None, Vector("N2"))
    val g1 = SpdxGraph(Vector(present), Vector(SpdxRelationship("SPDXRef-bin", "SPDXRef-Package-z", "STATIC_LINK")), Vector.empty)
    val g2 = SpdxGraph(Vector(sparse), Vector(SpdxRelationship("SPDXRef-lib", "SPDXRef-Package-z", "CONTAINS")), Vector.empty)
    val merged = SpdxGraph.merge(Seq(g1, g2))
    assertEquals(merged.packages.size, 1)
    val z = merged.packages.head
    assertEquals(z.downloadLocation, "https://zlib.net")
    assertEquals(z.license, "Zlib")
    assertEquals(z.notices.toSet, Set("N1", "N2"))
    assertEquals(merged.relationships.size, 2)
end SpdxSuite
