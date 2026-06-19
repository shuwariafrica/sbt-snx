enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

libraryDependencies += "org.scalameta" %% "munit" % "1.3.2" % Test
testFrameworks += new TestFramework("munit.Framework")

// A NIR library publishes its C as source, so vendored C belongs only in the Test link - here, building a real
// libanswer.a to link the binding tests against. Test-scoped: it folds into the test link, never the (absent) main
// link. The defines are keyed on every runtime so the fixture is host-agnostic.
Test / SNX.vendored += Vendored
  .local("vendor/answer")
  .cmake("answer")
  .options { case _ => _.compileOptions("-DSNX_VENDORED_COMPILE").cOptions("-DSNX_VENDORED_C") }
