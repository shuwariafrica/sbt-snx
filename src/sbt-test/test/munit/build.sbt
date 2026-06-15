enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

libraryDependencies += "org.scalameta" %% "munit" % "1.3.2" % Test
testFrameworks += new TestFramework("munit.Framework")

val check = taskKey[Unit]("assert the plugin discovers native tests through the test framework")
check := {
  val names = (Test / definedTestNames).value
  assert(names.contains("LibSuite"), s"native test discovery returned no LibSuite: $names")
  streams.value.log.info(s"snx test/munit: discovered ${names.mkString(", ")}")
}
