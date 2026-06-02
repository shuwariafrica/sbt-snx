enablePlugins(SNXPlugin)

scalaVersion := "3.8.3"

SNX.target := TargetPlatform(OS.Linux, Arch.X86_64)

// Construction-level cover of the `%%` coordinate and the DSL forms (no `update` runs here, so these
// need not resolve - a real `%%` classified artefact does not exist).
SNX.dependencies += "com.example" %% "blas" % "1.2" // bare lift via given Conversion
SNX.dependencies += ("com.example" %% "uv" % "1.4" linking { case NativePlatform.Linux(_, _) => Seq("-lmainlib") })
SNX.dependencies += ("com.example" %% "headers" % "1.0").plain
SNX.dependencies += (("com.example" %% "tkit" % "1.0" % Test).plain linking { case NativePlatform.Linux(_, _) => Seq("-ltestonly") })

// Full additive bundle + libc-scoped match (resolved from the toolchain), via the nested Options type.
SNX.dependencies += ("com.example" %% "ssl" % "3" options {
  case NativePlatform.Linux(_, LinuxLibc.Glibc) =>
    NativeDependency.Options.empty.withLinking("-lssl").withCompile("-I/opt/ssl/include")
})

// Same module in two scopes with different options - per-config correctness (no leak, no double).
SNX.dependencies += ("com.example" %% "dup" % "1.0" linking { case NativePlatform.Linux(_, _) => Seq("-ldup-compile") })
SNX.dependencies += (("com.example" %% "dup" % "1.0" % Test).plain linking { case NativePlatform.Linux(_, _) => Seq("-ldup-test") })

// Compound config string - declared for both compile and test; applied once, in Compile.
SNX.dependencies += (("com.example" %% "both" % "1.0" % "compile,test").plain linking { case NativePlatform.Linux(_, _) => Seq("-lboth") })

// Project-level transform - `:=` is the primary idiom (no wrapper); mixed `c => ...` / `_.withX(...)` bodies.
SNX.config := Seq({
  case NativePlatform.Linux(_, _) => c => c.withLinkingOptions(c.linkingOptions :+ "-lproject")
  case NativePlatform.Osx(_)      => _.withLTO(LTO.full)
})

// `+=` with the `nativeTransform` carrier (additive).
SNX.config += nativeTransform { case NativePlatform.Linux(_, _) => c => c.withCompileOptions(c.compileOptions :+ "-DPROJ2") }

val check = taskKey[Unit]("verify classifier injection, per-config linking, and the redesigned API (construction-level)")
check := Def.uncached {
  def classifierOf(n: String): Option[String] =
    libraryDependencies.value.find(_.name == n).toSeq.flatMap(_.explicitArtifacts).flatMap(_.classifier).headOption
  assert(classifierOf("blas").contains("linux-x86_64"), s"blas (bare lift): ${classifierOf("blas")}")
  assert(classifierOf("uv").contains("linux-x86_64"), s"uv: ${classifierOf("uv")}")
  assert(classifierOf("headers").isEmpty, s"headers should be plain: ${classifierOf("headers")}")

  // SNX.targets defaults to the active target alone, so the active target is always a member by default.
  assert(SNX.targets.value == Seq(TargetPlatform(OS.Linux, Arch.X86_64)), s"default SNX.targets: ${SNX.targets.value}")
  assert(SNX.targets.value.contains(SNX.target.value), s"active target absent from default SNX.targets: ${SNX.targets.value}")

  // SNX.Native / crossPaths defaults to false here, so no per-platform source/resource dirs are injected.
  assert(
    !(Compile / unmanagedSourceDirectories).value.map(_.getName).contains("scala-linux"),
    s"platform dirs injected with the switch off: ${(Compile / unmanagedSourceDirectories).value.map(_.getName)}")

  // Consumed scope = <config> / nativeLink / nativeConfig (running platform/libc detection via clang).
  val compileFlags = (Compile / nativeLink / nativeConfig).value.linkingOptions
  val testFlags = (Test / nativeLink / nativeConfig).value.linkingOptions
  val compileC = (Compile / nativeLink / nativeConfig).value.compileOptions
  assert(compileFlags.contains("-lmainlib"), s"compile link missing -lmainlib: $compileFlags")
  assert(!compileFlags.contains("-ltestonly"), s"test-only flag leaked into compile link: $compileFlags")
  assert(testFlags.contains("-lmainlib") && testFlags.contains("-ltestonly"), s"test link missing flags: $testFlags")
  assert(testFlags.count(_ == "-lmainlib") == 1, s"compile flag duplicated in test link: $testFlags")
  assert(compileFlags.contains("-lssl"), s"options bundle linking missing: $compileFlags")
  assert(compileC.contains("-I/opt/ssl/include"), s"options bundle compile missing: $compileC")

  // Project-level SNX.config: `:=` linking flag, and `+=` nativeTransform compile flag.
  assert(compileFlags.contains("-lproject"), s"project-level SNX.config (:=) missing: $compileFlags")
  assert(testFlags.count(_ == "-lproject") == 1, s"project-level flag duplicated in test: $testFlags")
  assert(compileC.contains("-DPROJ2"), s"SNX.config += nativeTransform missing: $compileC")

  // Same module, two scopes: compile gets the compile-only flag; test gets both (compile via delegation + test-only).
  assert(compileFlags.contains("-ldup-compile"), s"dup compile flag missing: $compileFlags")
  assert(!compileFlags.contains("-ldup-test"), s"dup test flag leaked into compile: $compileFlags")
  assert(testFlags.contains("-ldup-compile") && testFlags.contains("-ldup-test"), s"dup test link missing flags: $testFlags")
  assert(testFlags.count(_ == "-ldup-compile") == 1, s"dup compile flag duplicated in test: $testFlags")

  // Compound config "compile,test": applied once in Compile, inherited by Test (no double).
  assert(compileFlags.contains("-lboth"), s"compound-config dep missing in compile: $compileFlags")
  assert(testFlags.count(_ == "-lboth") == 1, s"compound-config dep duplicated in test: $testFlags")

  streams.value.log.info("snx scripted check: classifier + per-config + redesigned API (:= / nativeTransform / Options) + dup-scope + compound-config OK")
}
