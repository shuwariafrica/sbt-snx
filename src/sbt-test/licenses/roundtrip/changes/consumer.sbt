// Consumer phase: resolve the classified native library the producer published, then aggregate its third-party
// native licences - read from the resolved published jar's embedded SPDX document, not a local build. The consumer
// also RE-DECLARES the same library's licence (the redundant case): the producer's shipped identity and the
// consumer's derived identity are the same platform-independent base coordinate, so the library deduplicates to one.
enablePlugins(SNXPlugin)

scalaVersion := "3.8.3"

SNX.target := TargetPlatform(OS.Linux, Arch.X86_64)

resolvers += "repo" at (baseDirectory.value / "target" / "repo").toURI.toString

// Re-declare with a DISTINCT licence (Apache-2.0) - the producer's vendored library is MIT - so the consumer's
// contribution is identifiable: Apache-2.0 can only come from this re-declaration's generated marker.
SNX.dependencies += ("africa.shuwari" %% "snxlib" % "0.1.0").licensed("Apache-2.0")

val check = taskKey[Unit]("the producer's licence is aggregated from the resolved published jar, deduplicated")
check := {
  val report = (Compile / SNX.licenseReport).value
  val flat = IO.read(report).replaceAll("\\s+", "")
  // "answer" (MIT) and the producer's base coordinate originate only inside the resolved published jar's SPDX (the
  // consumer declares nothing named "answer" and never derives the producer's coordinate itself), proving it was read.
  assert(flat.contains("\"name\":\"answer\""), s"producer vendored library missing from the aggregate: $flat")
  assert(flat.contains("\"licenseDeclared\":\"MIT\""), s"producer MIT licence not aggregated from the published jar: $flat")
  assert(flat.contains("pkg:maven/africa.shuwari/snxlib@0.1.0"), s"producer base coordinate (from the resolved jar) missing: $flat")
  // Finding E: BOTH sources contribute snxlib - the resolved published jar (the producer's artefact root) and the
  // consumer's Apache-2.0 re-declaration - yet snxlib is ONE package, because the producer (cross suffix stripped from
  // its baked module name) and the consumer (base ModuleID.name) derive the SAME platform-independent identity.
  assert(flat.contains("\"licenseDeclared\":\"Apache-2.0\""), s"consumer re-declaration did not contribute (Apache-2.0 absent): $flat")
  val snxlibPackages = flat.split("\"name\":\"snxlib\"", -1).length - 1
  assert(snxlibPackages == 1, s"snxlib not deduplicated across the published SPDX and the re-declaration ($snxlibPackages): $flat")
  streams.value.log.info("snx licenses/roundtrip: producer licence aggregated from the resolved published jar, deduplicated")
}
