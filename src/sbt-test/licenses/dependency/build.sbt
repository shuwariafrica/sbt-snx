enablePlugins(SNXPlugin)

scalaVersion := "3.8.3"

SNX.target := TargetPlatform(OS.Linux, Arch.X86_64)

// A managed native dependency declaring a project-bundled BSD licence. Checked via managedResources (the generator
// reads the SNX.dependencies setting, so no resolution is triggered and the coordinate need not exist).
SNX.dependencies += ("com.example" %% "blas" % "1.2").native
  .licensed("BSD-3-Clause", file("LICENSE"))
  .originator("Organization: the blas authors")

val checkDep = taskKey[Unit]("verify the dependency's licence reaches the generated SPDX document")
checkDep := {
  val _ = (Compile / managedResources).value
  val dir = (Compile / resourceManaged).value / "META-INF" / "native-licenses"
  val document = IO.read(dir / "native-licenses.spdx.json")
  val flat = document.replaceAll("\\s+", "")
  assert(flat.contains("\"name\":\"blas\""), s"dep name missing: $flat")
  assert(flat.contains("\"versionInfo\":\"1.2\""), s"dep version missing: $flat")
  // A managed dependency auto-derives a maven Package URL identity (a SPDX externalRef), platform-independent.
  assert(flat.contains("\"referenceLocator\":\"pkg:maven/com.example/blas@1.2\""), s"dep purl missing: $flat")
  assert(flat.contains("\"licenseDeclared\":\"BSD-3-Clause\""), s"dep licence expression missing: $flat")
  assert(flat.contains("\"relationshipType\":\"STATIC_LINK\""), s"dep relationship missing: $flat")
  assert(flat.contains("\"originator\":\"Organization:theblasauthors\""), s"dep originator missing: $flat")
  // The licence text is bundled alongside the document under a name keyed by the dependency's identity.
  val bundled = Option(dir.listFiles).getOrElse(Array.empty[File]).filter(_.getName != "native-licenses.spdx.json")
  assert(bundled.exists(f => IO.read(f).contains("BSD 3-Clause")), s"dep licence text not bundled: ${bundled.map(_.getName).toList}")
  streams.value.log.info("snx licenses/dependency: dependency licence + identity + text in the generated SPDX document")
}
