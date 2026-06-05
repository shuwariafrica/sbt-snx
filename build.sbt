scalaVersion := "3.8.3"
name := "sbt-snx"
organization := "africa.shuwari"
startYear := Some(2026)
homepage := Some(url("https://github.com/shuwariafrica/sbt-snx"))
scmInfo := ScmInfo(
  url("https://github.com/shuwariafrica/sbt-snx"),
  "scm:git:https://github.com/shuwariafrica/sbt-snx.git",
  Some("scm:git:git@github.com:shuwariafrica/sbt-snx.git")
).some

apacheLicensed
Shuwari.organisationSettings
packageSettings
scriptedSettings
enablePlugins(SbtPlugin)
semanticdbEnabled := true

addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.12")

libraryDependencies += "org.scalameta" %% "munit" % "1.3.2" % Test
testFrameworks += new TestFramework("munit.Framework")

// Bakes the plugin version into a constant so the vendored-build action cache invalidates on a plugin upgrade.
Compile / sourceGenerators += Def.task {
  val file = (Compile / sourceManaged).value / "snx" / "sbt" / "BuildInfo.scala"
  IO.write(
    file,
    s"""package snx.sbt
       |
       |private[sbt] object BuildInfo:
       |  final val version: String = "${version.value}"
       |""".stripMargin
  )
  Seq(file)
}.taskValue

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
  } ++ Seq("-Xmx1024M", s"-Dplugin.version=${version.value}", s"-Duser.home=${sys.props.getOrElse("user.home", "")}"),
  scriptedBufferLog := false,
  scripted / excludeFilter := {
    val os = sys.env.get("SNX_EXPECT_OS")
    val staticCapable = sys.env.get("SNX_EXPECT_ENV").exists(Set("musl", "msvc").contains)
    val nonLinux = os.exists(_ != "linux")
    new SimpleFileFilter(file => {
      val name = file.getName
      (name == "detect" && os.isEmpty) || // needs the injected platform ground truth (SNX_EXPECT_OS)
      (name == "static" && !staticCapable) || // needs a fully-static-capable environment (musl or MSVC; not glibc/MinGW/osx)
      (name != "detect" && name != "static" && nonLinux)
    })
  }
)

addCommandAlias("format", "scalafixAll; scalafmtAll; scalafmtSbt; headerCreateAll")
addCommandAlias("check", "scalafixAll --check; scalafmtCheckAll; scalafmtSbtCheck; headerCheckAll")
