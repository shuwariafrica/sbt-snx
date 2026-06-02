// Producer phase: publish a classified native library to a local file repository. The build.sbt is swapped to
// changes/consumer.sbt (see test) for the resolve phase, so the producer is no longer a project in the build
// and the consumer resolves the published artifact rather than short-circuiting to inter-project resolution.
enablePlugins(SNXPlugin)

scalaVersion := "3.8.3"
organization := "africa.shuwari"
name := "snxlib"
version := "0.1.0"

SNX.target := TargetPlatform(OS.Linux, Arch.X86_64)
SNX.Native / crossPaths := true

// sbt/sbt#9117 workaround so the published filenames carry the platform suffix (tracked in sbt/sbt#9293).
SNX.platformPublishSettings

publishTo := Some("repo" at (baseDirectory.value / "target" / "repo").toURI.toString)
