// Classified publish (Pass 5 part C). A per-platform NIR library publishes its native content (NIR + the native.json
// descriptor) under the build's OS/arch classifier, with a manifest-only placeholder on the unclassified main
// coordinate. Proven on BOTH publish backends - ivy (sbt's default `useIvy := true`) and ivyless/coursier
// (`useIvy := false`) - so a sbt#9117-class regression (a dropped platform suffix or classifier on one backend) is
// caught: the assertions read the published file at its suffixed, classified path. Also: the publish SPLIT (placeholder
// main vs classified content, guarding content leaking onto the main coordinate) and build-twice NO-CASCADE (the
// classified content and the native.json are byte-stable across an unchanged rebuild). Both backends publish maven-style
// to a file repo.
scalaVersion := "3.8.4"
organization := "snx.test"
version := "0.1.0"

val ivyRepo = settingKey[File]("the maven-layout file repo the ivy backend publishes to")
ivyRepo := baseDirectory.value / "repo-ivy"

val coursierRepo = settingKey[File]("the maven-layout file repo the ivyless (coursier) backend publishes to")
coursierRepo := baseDirectory.value / "repo-coursier"

val descriptorFile = settingKey[File]("the generated native.json descriptor")
descriptorFile := (Compile / resourceManaged).value / "META-INF" / "scala-native" / "native.json"

name := "snxlib"
publishTo := Some(MavenCache("snx-ivy", ivyRepo.value))
enablePlugins(SNXPlugin)
SNX.classified := true
SNX.libraries := { case _ => Seq(NativeLibrary("snx_classified_marker")) }

def entries(jar: File): Set[String] =
  val zip = new java.util.zip.ZipFile(jar)
  try
    import scala.jdk.CollectionConverters.*
    zip.entries.asScala.map(_.getName).toSet
  finally zip.close()

def sha256(file: File): String =
  val digest = java.security.MessageDigest.getInstance("SHA-256")
  digest.update(IO.readBytes(file))
  digest.digest.map(b => "%02x".format(b & 0xff)).mkString

// The placeholder/classified split at the suffixed, classified maven path - reading the file there is itself the
// platform-suffix-plus-classifier (sbt#9117) guard.
def assertSplit(repo: File, backend: String, classifier: String, log: sbt.util.Logger): Unit =
  val dir = repo / "snx" / "test" / "snxlib_native0.5_3" / "0.1.0"
  val main = dir / "snxlib_native0.5_3-0.1.0.jar"
  val classified = dir / s"snxlib_native0.5_3-0.1.0-$classifier.jar"
  assert(main.isFile, s"[$backend] no suffixed main jar at $main")
  assert(classified.isFile, s"[$backend] no suffixed classified jar at $classified")
  val mainEntries = entries(main)
  val classifiedEntries = entries(classified)
  assert(!mainEntries.contains("META-INF/scala-native/native.json"), s"[$backend] the placeholder main jar must not carry the descriptor:\n$mainEntries")
  assert(!mainEntries.exists(_.endsWith(".nir")), s"[$backend] the placeholder main jar must carry no NIR:\n$mainEntries")
  assert(classifiedEntries.contains("META-INF/scala-native/native.json"), s"[$backend] the classified jar must carry the descriptor:\n$classifiedEntries")
  assert(classifiedEntries.exists(_.endsWith(".nir")), s"[$backend] the classified jar must carry the NIR:\n$classifiedEntries")
  log.info(s"snx resolution/classified [$backend]: placeholder main (manifest-only) and classified $classifier content (native.json + NIR) split correctly")

val checkIvy = taskKey[Unit]("assert the ivy-backend publish split")
checkIvy := Def.uncached(assertSplit(ivyRepo.value, "ivy", SNX.host.classifier, streams.value.log))

val checkCoursier = taskKey[Unit]("assert the ivyless (coursier) publish split")
checkCoursier := Def.uncached(assertSplit(coursierRepo.value, "ivyless", SNX.host.classifier, streams.value.log))

val recordStable = taskKey[Unit]("record the packaged content hash and the descriptor's modification time")
recordStable := Def.uncached {
  val content = fileConverter.value.toPath((Compile / packageBin).value).toFile
  IO.write(target.value / "stable-hash", sha256(content))
  IO.write(target.value / "stable-mtime", descriptorFile.value.lastModified.toString)
}

val checkStable = taskKey[Unit]("assert no publish cascade: the packaged content + descriptor are byte-stable across a rebuild")
checkStable := Def.uncached {
  val content = fileConverter.value.toPath((Compile / packageBin).value).toFile
  val hash = IO.read(target.value / "stable-hash")
  val mtime = IO.read(target.value / "stable-mtime").trim.toLong
  assert(sha256(content) == hash, "the packaged content jar changed across an unchanged rebuild (publish cascade)")
  assert(descriptorFile.value.lastModified == mtime, "the native.json descriptor was rewritten on an unchanged rebuild (write-if-changed failed)")
  streams.value.log.info("snx resolution/classified: packaged content + native.json are byte-stable across a rebuild (no cascade)")
}
