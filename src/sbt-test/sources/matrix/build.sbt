// In a native project matrix the platform-agnostic scala/resources dirs are left alone; only the native
// scalanative dir is suffixed, and a resources-scalanative dir (which sbt provides for sources, not resources)
// is registered and suffixed.
lazy val foo = (projectMatrix in file("foo"))
  .nativePlatform(Seq("3.8.3"))
  .enablePlugins(SNXPlugin)
  .settings(
    SNX.target := TargetPlatform(OS.Linux, Arch.X86_64),
    SNX.Native / crossPaths := true,
    TaskKey[Unit]("check") := {
      val srcs = (Compile / unmanagedSourceDirectories).value.map(_.getName).toSet
      val res = (Compile / unmanagedResourceDirectories).value.map(_.getName).toSet
      assert(srcs.contains("scalanative-linux"), s"scalanative-linux not registered: $srcs")
      assert(srcs.contains("scalanative-linux-x86_64"), s"scalanative-linux-x86_64 not registered: $srcs")
      assert(res.contains("resources-scalanative"), s"common resources-scalanative not registered: $res")
      assert(res.contains("resources-scalanative-linux"), s"resources-scalanative-linux not registered: $res")
      // The shared, platform-agnostic dirs must NOT be suffixed in a matrix.
      assert(!srcs.contains("scala-linux"), s"shared scala dir suffixed in a matrix: $srcs")
      assert(!res.contains("resources-linux"), s"shared resources dir suffixed in a matrix: $res")
      streams.value.log.info("snx sources/matrix: native-matrix source and resource dirs correct, shared dirs untouched")
    }
  )

// A second matrix row with the switch OFF: the common resources-scalanative is still registered (it is always
// present in a matrix), but no per-platform directories are.
lazy val bar = (projectMatrix in file("bar"))
  .nativePlatform(Seq("3.8.3"))
  .enablePlugins(SNXPlugin)
  .settings(
    SNX.target := TargetPlatform(OS.Linux, Arch.X86_64),
    SNX.Native / crossPaths := false,
    TaskKey[Unit]("check") := {
      val srcs = (Compile / unmanagedSourceDirectories).value.map(_.getName).toSet
      val res = (Compile / unmanagedResourceDirectories).value.map(_.getName).toSet
      assert(res.contains("resources-scalanative"), s"common resources-scalanative not registered with switch off: $res")
      assert(!res.contains("resources-scalanative-linux"), s"platform resource dir registered with switch off: $res")
      assert(!srcs.contains("scalanative-linux"), s"platform source dir registered with switch off: $srcs")
      streams.value.log.info("snx sources/matrix: switch off - common resources-scalanative only, no platform dirs")
    }
  )
