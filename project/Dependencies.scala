import sbt.*

object Dependencies:
  val munit = "org.scalameta" %% "munit" % "1.3.6"
  val `scala-native-tools` = "org.scala-native" %% "tools" % "0.5.12"
  val `scala-native-test-runner` = `scala-native-tools`.withName("test-runner")
  // The Scala the scripted fixtures' projects compile with, standing in for a consumer's.
  val `scala3-library` = "org.scala-lang" %% "scala3-library" % "3.10.1"
