// A producer NIR library that declares a vendored zlib licence, exported as a jar so the consumer resolves it as a
// dependency carrying a META-INF/native-licenses SPDX document (the transitive case). The consumer adds its own
// declarations and aggregates everything into an SPDX document.

lazy val producer = project
  .enablePlugins(SNXPlugin)
  .settings(
    scalaVersion := "3.8.3",
    exportJars := true,
    SNX.target := TargetPlatform(OS.Linux, Arch.X86_64),
    SNX.vendored += NativeSource
      .Local("zlib", NativeBackend.CMake(Seq("z")))
      .licensed("Zlib", file("LICENSE"))
      .identity("pkg:generic/zlib@1.3")
      .source(java.net.URI.create("https://zlib.net"))
  )

lazy val consumer = project
  .enablePlugins(SNXPlugin)
  .dependsOn(producer)
  .settings(
    scalaVersion := "3.8.3",
    SNX.target := TargetPlatform(OS.Linux, Arch.X86_64),
    SNX.vendored ++= Seq(
      // A non-listed licence: its text must travel as an SPDX extracted-licensing entry, its notice as an
      // attribution text, and its written offer as a package comment.
      NativeSource
        .Local("acme", NativeBackend.CMake(Seq("acme")))
        .licensed("LicenseRef-acme", file("LICENSE"))
        .notice(file("NOTICE"))
        .writtenOffer("source@example.com"),
      // A second library reusing the SAME LicenseRef token for a DIFFERENT text: both texts must survive the merge
      // (the LicenseRef is namespaced by package identity), never silently collapse to one.
      NativeSource.Local("beta", NativeBackend.CMake(Seq("beta"))).licensed("LicenseRef-acme", file("LICENSE")),
      // The same zlib (same identity) the producer ships: must deduplicate to a single SPDX package.
      NativeSource.Local("zlib", NativeBackend.CMake(Seq("z"))).licensed("Zlib", file("LICENSE")).identity("pkg:generic/zlib@1.3"),
      // A build-only dependency: its obligations do not transfer to the binary, so it must be excluded.
      NativeSource.Local("buildtool", NativeBackend.CMake(Seq("bt"))).licensed("MIT", file("LICENSE")).relationship(Relationship.DependsOn),
      // A library that vendors its own third-party components (the libgit2 case): each is its own SPDX package,
      // contained by the wrapper (a CONTAINS relationship), with its own identity and licence.
      NativeSource
        .Local("wrapper", NativeBackend.CMake(Seq("wrapper")))
        .licensed("Apache-2.0", file("LICENSE"))
        .bundles(
          Component("bundled-llhttp", "MIT", file("LICENSE")).identity("pkg:github/nodejs/llhttp@9.2.1"),
          Component("bundled-pcre2", "BSD-3-Clause", file("LICENSE"))
        )
    )
  )

val checkReport = taskKey[Unit]("aggregate the SPDX report and verify its contents")
checkReport := {
  val document = (consumer / Compile / SNX.licenseReport).value
  val flat = IO.read(document).replaceAll("\\s+", "")
  assert(flat.contains("\"spdxVersion\":\"SPDX-2.3\""), s"spdx version: $flat")
  // The producer's zlib (from its jar) is present with its Package URL external reference.
  assert(flat.contains("\"name\":\"zlib\""), s"zlib missing: $flat")
  assert(flat.contains("\"referenceType\":\"purl\",\"referenceLocator\":\"pkg:generic/zlib@1.3\""), s"zlib purl: $flat")
  assert(flat.contains("\"downloadLocation\":\"https://zlib.net\""), s"zlib source: $flat")
  // The consumer's own acme (a non-listed licence) is present with its text as an extracted-licensing entry, its
  // LicenseRef namespaced by identity. beta reuses the same token for a different text - both must survive.
  assert(flat.contains("\"name\":\"acme\""), s"acme missing: $flat")
  assert(flat.contains("\"licenseDeclared\":\"LicenseRef-acme-acme\""), s"acme namespaced licence: $flat")
  assert(flat.contains("\"licenseDeclared\":\"LicenseRef-beta-acme\""), s"beta namespaced licence: $flat")
  assert(flat.contains("\"extractedText\":\"AcmeLicense"), s"acme extracted text: $flat")
  assert(flat.contains("\"extractedText\":\"BetaLicense"), s"beta extracted text (collision survived): $flat")
  // acme's NOTICE travels as an attribution text, and its written offer as a package comment.
  assert(flat.contains("\"attributionTexts\":[\"AcmeNOTICE"), s"acme notice as attribution: $flat")
  assert(flat.contains("\"comment\":\"Writtenofferforsource:source@example.com\""), s"acme written offer as comment: $flat")
  // The build-only dependency is excluded from the binary's notices.
  assert(!flat.contains("\"name\":\"buildtool\""), s"buildtool should be excluded: $flat")
  // The binary statically links the included libraries.
  assert(flat.contains("\"relationshipType\":\"STATIC_LINK\""), s"static link relationship: $flat")
  // zlib reaches the binary via both the producer jar and the consumer declaration: it must appear once.
  val zlibPackages = flat.split("\"name\":\"zlib\"", -1).length - 1
  assert(zlibPackages == 1, s"zlib not deduplicated ($zlibPackages packages): $flat")
  // The wrapper's bundled components are their own packages, contained by the wrapper.
  assert(flat.contains("\"name\":\"bundled-llhttp\""), s"bundled component missing: $flat")
  assert(flat.contains("\"name\":\"bundled-pcre2\""), s"bundled component missing: $flat")
  assert(flat.contains("\"referenceLocator\":\"pkg:github/nodejs/llhttp@9.2.1\""), s"bundled component identity: $flat")
  assert(flat.contains("\"relationshipType\":\"CONTAINS\""), s"contained relationship missing: $flat")
  streams.value.log.info("snx licenses/aggregate: SPDX aggregated across jar + local, deduplicated, contained, build-only excluded")
}
