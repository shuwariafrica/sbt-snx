import sbt.Def

scalaVersion := "3.8.3"
name := "sbt-snx"
organization := "africa.shuwari"
startYear := Some(2026)
homepage := Some(url("https://github.com/shuwariafrica/sbt-snx"))
apacheLicensed
Shuwari.organisationSettings
packageSettings
scriptedSettings
enablePlugins(SbtPlugin)
semanticdbEnabled := true

addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.12")

def packageSettings: Seq[Def.Setting[?]] = Seq(
  packageOptions += Package.ManifestAttributes(
    "Specification-Title" -> name.value,
    "Specification-Version" -> Keys.version.value,
    "Specification-Vendor" -> organizationName.value
  ),
  publishTo := {
    if (Keys.version.value.toLowerCase.contains("snapshot"))
      Some("central-snapshots".at("https://central.sonatype.com/repository/maven-snapshots/"))
    else localStaging.value
  },
  pomIncludeRepository := (_ => false),
  publishMavenStyle := true
)

def scriptedSettings: Seq[Def.Setting[?]] = Seq(
  scriptedLaunchOpts ++= Seq("classifier", "os", "env").flatMap { axis =>
    sys.env.get(s"SNX_EXPECT_${axis.toUpperCase}").map(value => s"-Dsnx.expect.$axis=$value")
  } ++ Seq("-Xmx1024M", s"-Dplugin.version=${version.value}"),
  scriptedBufferLog := false,
  scripted / excludeFilter := {
    if (sys.env.contains("SNX_EXPECT_OS")) NothingFilter
    else new SimpleFileFilter(_.getName == "detect")
  }
)

addCommandAlias("format", "scalafixAll; scalafmtAll; scalafmtSbt; headerCreateAll")
addCommandAlias("check", "scalafixAll --check; scalafmtCheckAll; scalafmtSbtCheck; headerCheckAll")
