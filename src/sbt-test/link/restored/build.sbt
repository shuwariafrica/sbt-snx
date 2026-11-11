// sbt restores cached compile outputs as links into its read-only store, and the Native compiler writes a class's NIR
// over the existing path. Editing a source after such a restore must recompile, not fail writing the old NIR.
enablePlugins(SNXPlugin)

scalaVersion := sys.props("scala.version")

SNX.deliverable := Executable
