import java.util.jar.JarFile
import scala.jdk.CollectionConverters.*

enablePlugins(SNXPlugin)

scalaVersion := "3.8.3"
organization := "africa.shuwari"
name := "snxlib"
version := "0.1.0"

SNX.target := TargetPlatform(OS.Linux, Arch.X86_64)
SNX.Native / crossPaths := true

// sbt/sbt#9117 (fix tracked in sbt/sbt#9293): publishSigned uses the Ivy backend, which drops the platform
// suffix from published filenames. The plugin's SNX.platformPublishSettings applies the workaround.
SNX.platformPublishSettings

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
