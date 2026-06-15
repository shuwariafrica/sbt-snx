import scala.scalanative.build.Discover
import scala.sys.process.Process

enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

SNX.deliverable := Library

val testC = taskKey[Unit]("compile and run a C driver against the linked native library")
testC := {
  val library = (Compile / SNX.link).value
  val directory = library.getParentFile
  val name = library.getName.stripPrefix("lib").stripSuffix(".so")
  val driver = baseDirectory.value / "src" / "main" / "c" / "driver.c"
  val out = target.value / "driver"
  val clang = Discover.clang().toString
  val compiled = Process(Seq(clang, driver.getAbsolutePath, s"-L${directory.getAbsolutePath}", s"-l$name", "-o", out.getAbsolutePath)).!
  assert(compiled == 0, s"the C driver failed to compile against the native library (exit $compiled)")
  out.setExecutable(true)
  val ran = Process(out.getAbsolutePath, directory, "LD_LIBRARY_PATH" -> directory.getAbsolutePath).!
  assert(ran == 0, s"the C driver linked against the native library returned $ran")
  streams.value.log.info("snx link/library: a C driver linked and ran against the native library's @exported symbol")
}
