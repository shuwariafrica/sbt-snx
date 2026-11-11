// A deleted source's products must leave the link with it. The app's own `shared.Greeting` shadows the library's;
// once the app's copy is deleted, the link must take the library's - from NIR the compiler wrote, never from what a
// deleted source left behind. The app's copy also carries a lambda and a reflectively instantiable class, whose NIR
// the Native compiler writes with no JVM class beside it.
val lib = project
  .enablePlugins(SNXPlugin)
  .settings(scalaVersion := sys.props("scala.version"))

val app = project
  .enablePlugins(SNXPlugin)
  .dependsOn(lib)
  .settings(scalaVersion := sys.props("scala.version"), SNX.deliverable := Executable)

def appNir(classes: File): Seq[String] =
  Option(new File(classes, "shared").listFiles).toSeq.flatten.map(_.getName).filter(_.endsWith(".nir")).sorted

// Non-vacuity: the generated forms exist while the app's source does.
val checkGenerated = taskKey[Unit]("assert the app compiled a lambda and a reflective-instantiation NIR file")
checkGenerated := {
  val nir = appNir((app / Compile / classDirectory).value)
  if (!nir.exists(_.contains("$$Lambda$")) || !nir.exists(_.endsWith("$scalanative$ReflectivelyInstantiate$.nir")))
    sys.error(s"expected generated NIR files, found: ${nir.mkString(", ")}")
}

val checkNoAppNir = taskKey[Unit]("assert no NIR file of the deleted source remains in the app's classes")
checkNoAppNir := {
  val nir = appNir((app / Compile / classDirectory).value)
  if (nir.nonEmpty) sys.error(s"NIR of a deleted source remains: ${nir.mkString(", ")}")
}
