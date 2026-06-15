// The consumer (after the build-file swap): resolves both published libraries from the file repo and confirms each
// descriptor travelled inside the resolved artefact - the classified one through the `% NativeClassifier` derivation,
// the unclassified one through its plain main jar.
scalaVersion := "3.8.4"
organization := "snx.test"
version := "0.2.0"
// The producer published to the build root's `repo` (LocalRootProject / baseDirectory); resolve from the same place.
resolvers += MavenCache("snx-pub", (LocalRootProject / baseDirectory).value / "repo")

val app = project
  .in(file("."))
  .enablePlugins(SNXPlugin)
  .settings(
    name := "app",
    SNX.deliverable := Executable,
    // A mixed classified/plain Seq needs the element-type ascription so the ModuleID lift fires per element (an
    // un-ascribed literal infers a NativeDependency | ModuleID element type that has no single conversion).
    SNX.dependencies ++= Seq[NativeDependency](
      "snx.test" %% "cls" % "0.1.0" % NativeClassifier,
      "snx.test" %% "uni" % "0.1.0"
    )
  )

def entries(jar: File): Set[String] =
  val zip = new java.util.zip.ZipFile(jar)
  try
    import scala.jdk.CollectionConverters.*
    zip.entries.asScala.map(_.getName).toSet
  finally zip.close()

val checkResolved = taskKey[Unit]("assert the consumer resolved both published descriptors")
checkResolved := Def.uncached {
  val classifier = SNX.host.classifier
  val converter = fileConverter.value
  val files = (app / Compile / dependencyClasspath).value.map(entry => converter.toPath(entry.data).toFile)
  val cls = files.find(_.getName == s"cls_native0.5_3-0.1.0-$classifier.jar")
  val uni = files.find(_.getName == "uni_native0.5_3-0.1.0.jar")
  assert(cls.isDefined, s"the classified library did not resolve:\n${files.map(_.getName).mkString("\n")}")
  assert(uni.isDefined, s"the unclassified library did not resolve:\n${files.map(_.getName).mkString("\n")}")
  assert(entries(cls.get).contains("META-INF/scala-native/native.json"), "the resolved classified jar lacks the descriptor")
  assert(entries(uni.get).contains("META-INF/scala-native/native.json"), "the resolved unclassified jar lacks the descriptor")
  streams.value.log.info("snx resolution/published: consumer resolved both the classified and unclassified descriptors end to end")
}
