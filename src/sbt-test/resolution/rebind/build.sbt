// Transitive rebind: a library required by a transitive NIR dependency is rebound by a downstream's own provisioning.
// `base` (NIR) exports a requirement; `middle` (NIR, depends on base) adds its own and never re-exports base's; `app`
// and `bare` (Executable, depend on middle) fold base's requirement transitively through the classpath. `app`
// provisions both names Unmanaged, so the rebind suppresses their default `-l` and the link succeeds; `bare` provisions
// neither, so the transitive default `-l<name>` is pushed and the link fails on an unresolvable library - proving the
// name-match rebind acts on a name folded from a transitive descriptor, two hops away. The own-only propagation it
// builds on is proven by resolution/transitive.
scalaVersion := "3.8.4"

val base = project
  .enablePlugins(SNXPlugin)
  .settings(SNX.libraries := { case _ => Seq(NativeLibrary("snx_rebind_foo")) })

val middle = project
  .enablePlugins(SNXPlugin)
  .dependsOn(base)
  .settings(SNX.libraries := { case _ => Seq(NativeLibrary("snx_rebind_mid")) })

val app = project
  .enablePlugins(SNXPlugin)
  .dependsOn(middle)
  .settings(
    SNX.deliverable := Executable,
    SNX.libraries ++= Seq(NativeLibrary("snx_rebind_foo", Provisioning.Unmanaged), NativeLibrary("snx_rebind_mid", Provisioning.Unmanaged))
  )

val bare = project
  .enablePlugins(SNXPlugin)
  .dependsOn(middle)
  .settings(SNX.deliverable := Executable)
