import scala.scalanative.sbtplugin.ScalaNativeCrossVersion

import java.util.jar.JarFile
import scala.jdk.CollectionConverters.*

enablePlugins(SNXPlugin)

scalaVersion := "3.8.3"
organization := "africa.shuwari"
name := "snxlib"
version := "0.1.0"

SNX.target := TargetPlatform(OS.Linux, Arch.X86_64)
SNX.classified := true

// sbt/sbt#9117 workaround (fix tracked in sbt/sbt#9293). `publishSigned` (sbt-pgp) always uses the Ivy publish
// backend - `useIvy := false` does NOT route it to the correct ivyless backend - and the Ivy backend drops the
// platform suffix from published filenames, which Maven rejects. Bake the cross+platform suffix into the
// module name (the single source every published filename derives from) and disable further suffixing, so the
// filenames match the coordinate. (This is the #9117 workaround for the Ivy/signed backend.)
moduleName := {
  val base = moduleName.value
  CrossVersion(ScalaNativeCrossVersion.binary, scalaVersion.value, scalaBinaryVersion.value).fold(base)(_(base))
}
projectID / crossVersion := Disabled()

// Host gpg signs (sbt-pgp default); the environment must provide a signing key (CI: disposable key, isolated GNUPGHOME).
publishTo := Some("test" at (baseDirectory.value / "target" / "repo").toURI.toString)

val check = taskKey[Unit]("the main artifact is a placeholder; the built native content is published under the classifier")
check := {
  val dir = baseDirectory.value / "target" / "repo" / "africa" / "shuwari" / "snxlib_native0.5_3" / "0.1.0"
  def entries(jar: File): Set[String] = {
    val file = new JarFile(jar)
    try file.entries.asScala.map(_.getName).toSet
    finally file.close()
  }
  val main = entries(dir / "snxlib_native0.5_3-0.1.0.jar")
  val classified = entries(dir / "snxlib_native0.5_3-0.1.0-linux-x86_64.jar")
  assert(classified.exists(_.startsWith("snxlib/")), s"classified jar is missing the built native content: $classified")
  assert(!main.exists(_.startsWith("snxlib/")), s"main jar must be a placeholder, but carries content: $main")
  streams.value.log.info("snx publish/signed: main artifact is a placeholder; classified carries the built content")
}
