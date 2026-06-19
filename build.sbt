scalaVersion := "3.8.4"
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

libraryDependencies += Dependencies.`scala-native-tools`
libraryDependencies += Dependencies.`scala-native-test-runner`
libraryDependencies += Dependencies.munit % Test
testFrameworks += new TestFramework("munit.Framework")

Compile / sourceGenerators += Def.task {
  val file = (Compile / sourceManaged).value / "snx" / "sbt" / "BuildInfo.scala"
  IO.write(
    file,
    s"""package snx.sbt
       |
       |private[sbt] object BuildInfo:
       |  inline val nativeVersion = "${Dependencies.`scala-native-tools`.revision}"
       |  inline val version = "${version.value}"
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
  } ++ Seq("-Xmx3G", "-Xss2M", s"-Dplugin.version=${version.value}", s"-Duser.home=${sys.props.getOrElse("user.home", "")}"),
  scriptedBufferLog := false,
  scripted / excludeFilter := {
    val os = sys.env.get("SNX_EXPECT_OS")
    val env = sys.env.get("SNX_EXPECT_ENV")
    // detect needs the injected platform ground truth (SNX_EXPECT_OS) the CI matrix sets per cell; static executables
    // need musl or MSVC; the library C-driver harness, the shell-script clang wrapper, the whole-archive
    // de-duplication link, and the zlib-backed integration capstone are Linux-only (the name-form whole-archive
    // renders nothing on macOS; the capstone's C + zlib path is Linux). Everything else (including wholearchive) runs
    // wherever clang is.
    new SimpleFileFilter(file =>
      file.getName match {
        case "detect"  => os.isEmpty
        case "static"  => !env.exists(Set("musl", "msvc"))
        case "library" => os.exists(_ != "linux")
        case "clang"   => os.exists(_ != "linux")
        case "dedup"   => os.exists(_ != "linux")
        case "hello"   => os.exists(_ != "linux")
        case _         => false
      })
  }
)

addCommandAlias("format", "scalafixAll; scalafmtAll; scalafmtSbt; headerCreateAll")
addCommandAlias("check", "scalafixAll --check; scalafmtCheckAll; scalafmtSbtCheck; headerCheckAll")
