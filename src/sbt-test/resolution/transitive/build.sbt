// Transitivity + no double-count: each library exports only its OWN requirements (an intermediate never re-exports a
// dependency's), and a downstream link aggregates the whole transitive graph by scanning the classpath. base declares
// a requirement; middle (depending on base) declares its own and must NOT carry base's; app (depending on middle)
// links, folding base's requirement transitively through the classpath - the unresolvable library fails the link.
scalaVersion := "3.8.4"

val base = project
  .enablePlugins(SNXPlugin)
  .settings(SNX.usage := { case _ => Usage.libraries("snx_base_absent") })

val middle = project
  .enablePlugins(SNXPlugin)
  .dependsOn(base)
  .settings(SNX.usage := { case _ => Usage.libraries("snx_middle_absent") })

val app = project
  .enablePlugins(SNXPlugin)
  .dependsOn(middle)
  .settings(SNX.deliverable := Executable)

val checkOwnOnly = taskKey[Unit]("middle exports its own requirement only, never re-exporting base's")
checkOwnOnly := Def.uncached {
  val _ = (middle / Compile / resources).value
  val descriptor = (middle / Compile / resourceManaged).value / "META-INF" / "scala-native" / "native.json"
  assert(descriptor.exists, s"middle wrote no descriptor at $descriptor")
  val content = IO.read(descriptor)
  assert(content.contains("snx_middle_absent"), s"middle omits its own requirement:\n$content")
  assert(!content.contains("snx_base_absent"), s"middle must not re-export base's requirement (no double-count):\n$content")
  streams.value.log.info("snx resolution/transitive: middle exports its own requirement only")
}
