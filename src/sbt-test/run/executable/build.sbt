enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

SNX.deliverable := Executable

// The run override must forward the configured environment to the spawned binary.
Compile / run / envVars += ("SNX_RUN_PROBE" -> "ok")

val recordLink = taskKey[Unit]("link the executable and record the binary's modification time")
recordLink := {
  val binary = (Compile / SNX.link).value
  assert(binary.isFile, s"no linked binary at $binary")
  IO.write(target.value / "link-mtime", binary.lastModified.toString)
}

val checkNoRelink = taskKey[Unit]("relink an unchanged project and assert the binary was not rebuilt")
checkNoRelink := {
  val binary = (Compile / SNX.link).value
  val recorded = IO.read(target.value / "link-mtime").trim.toLong
  assert(binary.lastModified == recorded, s"binary was relinked (${binary.lastModified} != $recorded)")
  streams.value.log.info("snx run/executable: unchanged rebuild relinked nothing")
}
