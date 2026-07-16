enablePlugins(SNXPlugin)

scalaVersion := "3.8.4"

libraryDependencies += "org.scalameta" %% "munit" % sys.props("munit.version") % Test
testFrameworks += new TestFramework("munit.Framework")

// A NIR library publishes its C as source, so a vendored library for it belongs only in the Test link - here, building
// a real libanswer.a to link the binding tests against. `% Test` scopes it to the test link in the library definition
// itself (mirroring a managed dependency's `% Test`): it folds into the test link, never the (absent) main link, and
// does not export. The closure define is keyed on every runtime so the fixture is host-agnostic.
SNX.libraries +=
  NativeLibrary("answer", Vendored.local("vendor/answer").cmake("answer").options { case _ => Flags.defines("SNX_VENDORED") }) % Test
