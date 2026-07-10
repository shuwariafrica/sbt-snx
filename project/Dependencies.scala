import sbt.*

object Dependencies:
  val munit = "org.scalameta" %% "munit" % "1.3.4"
  val `scala-native-tools` = "org.scala-native" %% "tools" % "0.5.12"
  val `scala-native-test-runner` = `scala-native-tools`.withName("test-runner")
