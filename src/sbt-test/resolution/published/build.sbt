// Descriptor propagation through a real publish->resolve, for both a classified and an unclassified NIR library. A
// classified library publishes its descriptor under the OS/arch classifier (placeholder main); an UNCLASSIFIED library
// publishes the SAME descriptor inside its main - and only - jar, so the scala-native pipeline propagates whether a NIR
// project is published classified or not. A consumer then resolves both from the file repo (no inter-project
// short-circuit, via the build-file swap) and finds each descriptor in the resolved artefact.
scalaVersion := "3.8.4"
organization := "snx.test"
version := "0.1.0"

publishTo := Some(MavenCache("snx-pub", (LocalRootProject / baseDirectory).value / "repo"))

val cls = project
  .enablePlugins(SNXPlugin)
  .settings(name := "cls", SNX.classified := true, SNX.libraries := { case _ => Seq(NativeLibrary("snx_cls_marker")) })

val uni = project
  .enablePlugins(SNXPlugin)
  .settings(name := "uni", SNX.libraries := { case _ => Seq(NativeLibrary("snx_uni_marker")) })

def entries(jar: File): Set[String] =
  val zip = new java.util.zip.ZipFile(jar)
  try
    import scala.jdk.CollectionConverters.*
    zip.entries.asScala.map(_.getName).toSet
  finally zip.close()

val checkMain = taskKey[Unit]("assert the unclassified library carries the descriptor in its main (only) jar")
checkMain := Def.uncached {
  val classifier = SNX.host.classifier
  val base = (LocalRootProject / baseDirectory).value / "repo" / "snx" / "test"
  val uniMain = base / "uni_native0.5_3" / "0.1.0" / "uni_native0.5_3-0.1.0.jar"
  val clsMain = base / "cls_native0.5_3" / "0.1.0" / "cls_native0.5_3-0.1.0.jar"
  val clsContent = base / "cls_native0.5_3" / "0.1.0" / s"cls_native0.5_3-0.1.0-$classifier.jar"
  assert(uniMain.isFile, s"no unclassified main jar at $uniMain")
  assert(entries(uniMain).contains("META-INF/scala-native/native.json"), s"the unclassified main jar must carry the descriptor:\n${entries(uniMain)}")
  assert(!entries(clsMain).contains("META-INF/scala-native/native.json"), "the classified library's main jar must be the placeholder (no descriptor)")
  assert(entries(clsContent).contains("META-INF/scala-native/native.json"), "the classified library's content jar must carry the descriptor")
  streams.value.log.info("snx resolution/published: unclassified descriptor-in-main; classified descriptor under the classifier")
}
