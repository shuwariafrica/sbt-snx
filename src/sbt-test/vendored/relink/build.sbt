enablePlugins(SNXPlugin)

scalaVersion := sys.props("scala.version")

SNX.deliverable := Executable

SNX.libraries += NativeLibrary("answer", Vendored.local("vendor/answer").cmake("answer"))

// Runs the linked binary and compares the answer it prints with the one in `expected`, which the build itself does not
// read: changing it changes neither the classpath nor the configuration the link is keyed by.
val expectAnswer = taskKey[Unit]("assert the linked binary returns the answer in `expected`")
expectAnswer := Def.uncached {
  val binary = (Compile / SNX.link).value
  val wanted = IO.read(baseDirectory.value / "expected").trim
  val printed = scala.sys.process.Process(Seq(binary.getAbsolutePath)).!!.trim
  assert(printed == s"snx-answer=$wanted", s"the linked binary $binary printed '$printed', expected snx-answer=$wanted")
  streams.value.log.info(s"snx vendored/relink: $printed")
}
