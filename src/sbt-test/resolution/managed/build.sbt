enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

// Plain `% Test` must resolve through sbt's own path (the libraryDependencies element type is ModuleID), untouched
// by the NativeDependency conversions.
libraryDependencies += "org.scalameta" %% "munit" % "1.3.3" % Test

// The managed-dependency DSL - the `% NativeClassifier` marker, the forwarded config builder in either order, and
// `options` - all reach NativeDependency through the plugin autoImport (the marker argument selects the conversion).
SNX.dependencies ++= Seq(
  "org.example" %% "lib" % "1.0" % NativeClassifier,
  "org.example" %% "lib" % "1.0" % NativeClassifier % Test,
  "org.example" %% "lib" % "1.0" % Test % NativeClassifier,
  ("org.example" %% "lib" % "1.0" % NativeClassifier).options { case _ => Usage.libraries("z") }
)

// The bare lift: a plain ModuleID where a NativeDependency is expected.
SNX.dependencies += "org.example" %% "lib" % "1.0"

val check = taskKey[Unit]("assert the managed-dependency DSL resolves through the plugin autoImport")
check := Def.uncached {
  val deps = SNX.dependencies.value
  assert(deps.sizeIs == 5, s"dependency count: ${deps.size}")
  assert(deps.count(_.classified) == 4, s"classified count: ${deps.count(_.classified)}")
  val testScoped = deps.filter(_.module.configurations.contains("test"))
  assert(testScoped.sizeIs == 2, s"test-scoped count: ${testScoped.size}")
  assert(testScoped.forall(_.classified), "a test-scoped marker must keep the classifier")
  streams.value.log.info("snx resolution/managed: marker, forwarded builder, options, and both conversions resolved")
}
