enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

libraryDependencies += "org.scalameta" %% "munit" % sys.props("munit.version") % Test
testFrameworks += new TestFramework("munit.Framework")

// Isolate the suite-replay store to this fixture copy: the disk action cache defaults to the
// user-level directory, which would leak warm records between runs, hosts, and CI cells.
Global / localCacheDirectory := (LocalRootProject / target).value / "snx-cache"
