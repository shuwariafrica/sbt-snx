enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

// Plain `% Test` must resolve through sbt's own path (the libraryDependencies element type is ModuleID), untouched
// by the NativeDependency conversions.
libraryDependencies += "org.scalameta" %% "munit" % sys.props("munit.version") % Test

// The managed-dependency DSL - the `% NativeClassifier` marker and the forwarded config builder in either order - reach
// NativeDependency through the plugin autoImport (the marker argument selects the conversion). NativeDependency is a
// pure coordinate; a library a project must declare itself is an SNX.libraries NativeLibrary, proven elsewhere.
SNX.dependencies ++= Seq(
  "org.example" %% "lib" % "1.0" % NativeClassifier,
  "org.example" %% "lib" % "1.0" % NativeClassifier % Test,
  "org.example" %% "lib" % "1.0" % Test % NativeClassifier
)

// The bare lift: a plain ModuleID where a NativeDependency is expected.
SNX.dependencies += "org.example" %% "lib" % "1.0"

val check = taskKey[Unit]("assert the managed-dependency DSL resolves through the plugin autoImport")
check := Def.uncached {
  val deps = SNX.dependencies.value
  assert(deps.sizeIs == 4, s"dependency count: ${deps.size}")
  assert(deps.count(_.classified) == 3, s"classified count: ${deps.count(_.classified)}")
  val testScoped = deps.filter(_.module.configurations.contains("test"))
  assert(testScoped.sizeIs == 2, s"test-scoped count: ${testScoped.size}")
  assert(testScoped.forall(_.classified), "a test-scoped marker must keep the classifier")
  streams.value.log.info("snx resolution/managed: marker, forwarded builder, and both conversions resolved")
}
