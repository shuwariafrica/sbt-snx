enablePlugins(SNXPlugin)

scalaVersion := "3.8.3"

SNX.target := TargetPlatform(OS.Linux, Arch.X86_64)

// A vendored library declaring a bundled MIT licence (vendor/answer/LICENSE), an attribution notice, a copyright, and
// where its source is available.
SNX.vendored += NativeSource
  .Local("answer", NativeBackend.CMake(Seq("answer")))
  .licensed("MIT", file("LICENSE"))
  .notice(file("NOTICE"))
  .source(java.net.URI.create("https://example.com/answer-1.0.tar.gz"))
  .copyright("Copyright (c) the answer authors")

val checkContents = taskKey[Unit]("verify the SPDX document and text are packaged into the jar")
checkContents := {
  val jar = fileConverter.value.toPath((Compile / packageBin).value).toFile
  val zip = new java.util.zip.ZipFile(jar)
  try {
    def entry(name: String): String = {
      val e = zip.getEntry(name)
      assert(e != null, s"missing jar entry: $name")
      val in = zip.getInputStream(e)
      try scala.io.Source.fromInputStream(in, "UTF-8").mkString
      finally in.close()
    }
    val flat = entry("META-INF/native-licenses/native-licenses.spdx.json").replaceAll("\\s+", "")
    val text = entry("META-INF/native-licenses/answer-LICENSE")
    val notice = entry("META-INF/native-licenses/answer-NOTICE")
    assert(flat.contains("\"spdxVersion\":\"SPDX-2.3\""), s"spdx version: $flat")
    assert(flat.contains("\"name\":\"answer\""), s"name: $flat")
    assert(flat.contains("\"relationshipType\":\"STATIC_LINK\""), s"relationship: $flat")
    assert(flat.contains("\"licenseDeclared\":\"MIT\""), s"licence expression: $flat")
    assert(flat.contains("\"downloadLocation\":\"https://example.com/answer-1.0.tar.gz\""), s"source: $flat")
    assert(flat.contains("\"copyrightText\":\"Copyright(c)theanswerauthors\""), s"copyright: $flat")
    assert(flat.contains("\"attributionTexts\":[\"answerNOTICE"), s"notice as attribution: $flat")
    assert(text.contains("MIT License"), s"licence text bundled: $text")
    assert(notice.contains("answer NOTICE"), s"notice text bundled: $notice")
    // The Local source has no version, so the package omits versionInfo (an omitted optional field).
    assert(!flat.contains("\"name\":\"answer\",\"versionInfo\""), s"Local source should have no version: $flat")
    streams.value.log.info("snx licenses/document: SPDX document + text + notice packaged into the jar")
  } finally zip.close()
}

val managedDocument = settingKey[File]("the generated SPDX document under resourceManaged")
managedDocument := (Compile / resourceManaged).value / "META-INF" / "native-licenses" / "native-licenses.spdx.json"

val recordResourceTime = taskKey[Unit]("record the generated SPDX document's timestamp")
recordResourceTime := {
  val _ = (Compile / copyResources).value
  IO.write(target.value / "res-time.txt", managedDocument.value.lastModified.toString)
}

val assertResourceUnchanged = taskKey[Unit]("assert the generated SPDX document was not rewritten (writeIfChanged holds)")
assertResourceUnchanged := {
  val _ = (Compile / copyResources).value
  val recorded = IO.read(target.value / "res-time.txt").trim.toLong
  val current = managedDocument.value.lastModified
  assert(current == recorded, s"SPDX document rewritten on identical rebuild (writeIfChanged regressed): $recorded -> $current")
  streams.value.log.info("snx licenses/document: generated SPDX document untouched on identical rebuild (writeIfChanged holds)")
}

val recordHash = taskKey[Unit]("record the packaged jar's content hash")
recordHash := IO.write(target.value / "jar-hash.txt", (Compile / packageBin).value.contentHashStr)

val assertStable = taskKey[Unit]("assert the published jar is unchanged on rebuild (no cascade)")
assertStable := {
  val recorded = IO.read(target.value / "jar-hash.txt").trim
  val current = (Compile / packageBin).value.contentHashStr
  assert(current == recorded, s"published jar changed on rebuild (cascade): $recorded -> $current")
  streams.value.log.info("snx licenses/document: deterministic output, jar stable on rebuild (no cascade)")
}
