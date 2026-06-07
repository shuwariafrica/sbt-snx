enablePlugins(SNXPlugin)

scalaVersion := "3.8.3"

SNX.target := TargetPlatform(OS.Linux, Arch.X86_64)

// vendor/noinstall builds a static library but declares NO install() rules, so cmake --install stages nothing
// and the build must fail with a clear diagnostic rather than silently linking nothing.
SNX.vendored += NativeSource.Local("noinstall", NativeBackend.CMake(Seq("noinstall")))
