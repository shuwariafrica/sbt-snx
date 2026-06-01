import scala.scalanative.sbtplugin.ScalaNativeCrossVersion

enablePlugins(ScalaNativePlugin, SnxPlugin)

scalaVersion := "3.8.3"
organization := "africa.shuwari"
name := "snxlib"
version := "0.1.0"

snxTarget := TargetPlatform(Os.Linux, Arch.X86_64)
snxClassified := true

// sbt/sbt#9117 workaround: drop once targeting an RC that includes the #9251 fix.
moduleName := {
  val base = moduleName.value
  CrossVersion(ScalaNativeCrossVersion.binary, scalaVersion.value, scalaBinaryVersion.value).fold(base)(_.apply(base))
}
projectID / crossVersion := Disabled()

// Host gpg signs (sbt-pgp default); the environment must provide a signing key (CI: disposable key, isolated GNUPGHOME).
publishTo := Some("test" at (baseDirectory.value / "target" / "repo").toURI.toString)
