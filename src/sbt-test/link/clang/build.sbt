import scala.scalanative.build.Discover

enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

SNX.deliverable := Executable

// Override the C compiler with a wrapper that records each invocation and then delegates to the real clang. Linking
// the executable must invoke it, proving SNX.clang is threaded through to the toolchain.
val wrapper = settingKey[File]("the recording clang wrapper")
wrapper := target.value / "clang-wrapper"

val sentinel = settingKey[File]("the file the wrapper appends to on each invocation")
sentinel := target.value / "clang-invoked"

SNX.clang := Some(wrapper.value)

val prepareClang = taskKey[Unit]("write an executable clang wrapper that records its invocation")
prepareClang := {
  val real = Discover.clang().toString
  IO.write(
    wrapper.value,
    s"""|#!/bin/sh
        |echo invoked >> "${sentinel.value.getAbsolutePath}"
        |exec "$real" "$$@"
        |""".stripMargin)
  wrapper.value.setExecutable(true)
}

val checkClang = taskKey[Unit]("assert the clang wrapper recorded an invocation")
checkClang := {
  assert(sentinel.value.exists, s"the SNX.clang override was not invoked: ${sentinel.value}")
  streams.value.log.info("snx link/clang: the SNX.clang override was invoked during the link")
}
