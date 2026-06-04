// Producer phase: publish a classified native library that declares a third-party native licence, so the published
// (classified) jar carries its SPDX document. The build.sbt is swapped to changes/consumer.sbt (see test) for the
// resolve phase, so the producer is no longer a project and the consumer aggregates from the resolved published jar.
enablePlugins(SNXPlugin)

scalaVersion := "3.8.3"
organization := "africa.shuwari"
name := "snxlib"
version := "0.1.0"

SNX.target := TargetPlatform(OS.Linux, Arch.X86_64)
SNX.Native / crossPaths := true

// sbt/sbt#9117 workaround so the published filenames carry the platform suffix (fixed upstream in sbt/sbt#9293, not
// yet in RC14); publishing via the suffixed coordinate is what the consumer's %% then resolves.
SNX.platformPublishSettings

// A declared vendored licence travels inside the published jar as an SPDX document (no native build at publish - the
// generator reads the declaration and bundles the text file).
SNX.vendored += NativeSource
  .Local("answer", NativeBackend.CMake(Seq("answer")))
  .licensed("MIT", file("LICENSE"))

publishTo := Some("repo" at (baseDirectory.value / "target" / "repo").toURI.toString)
