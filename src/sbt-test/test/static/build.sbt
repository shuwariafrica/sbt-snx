enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

libraryDependencies += "org.scalameta" %% "munit" % "1.3.2" % Test
testFrameworks += new TestFramework("munit.Framework")

// A NIR deliverable (default): the main artefact is a jar and is never linked. Its test binary is always an
// application, and this opts it into a fully-static link - independent of the deliverable's (dynamic) linkage, which
// resolveTestTarget now honours for every deliverable. Gated to static-capable platforms (musl/MSVC).
Test / SNX.linkage := Static

val checkStaticTest = taskKey[Unit]("assert the test binary linked statically")
checkStaticTest := {
  val binary = (Test / SNX.link).value
  assert(binary.isFile, s"no linked test binary at $binary")
  val ldd = scala.sys.process.Process(Seq("sh", "-c", s"ldd '${binary.getAbsolutePath}' 2>&1 || true")).!!.toLowerCase
  assert(ldd.contains("not a") || ldd.contains("statically"), s"the test binary is not statically linked:\n$ldd")
  streams.value.log.info("snx test/static: the Test / SNX.linkage := Static test binary is statically linked")
}
