import sbt.*

object Dependencies:
  private val scalaNative = "0.5.12"
  val munit = "org.scalameta" %% "munit" % "1.3.2"
  val `scala-native-tools` = "org.scala-native" %% "tools" % scalaNative
  val `scala-native-test-runner` = "org.scala-native" %% "test-runner" % scalaNative
