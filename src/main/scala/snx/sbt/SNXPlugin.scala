/****************************************************************
 * Copyright © 2026 Shuwari Africa Ltd.                         *
 *                                                              *
 * This file is licensed to you under the terms of the Apache   *
 * License Version 2.0 (the "License"); you may not use this    *
 * file except in compliance with the License. You may obtain   *
 * a copy of the License at:                                    *
 *                                                              *
 *     https://www.apache.org/licenses/LICENSE-2.0              *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, *
 * either express or implied. See the License for the specific  *
 * language governing permissions and limitations under the     *
 * License.                                                     *
 ****************************************************************/
package snx.sbt

import sbt.*
import sbt.Keys.artifact
import sbt.Keys.artifacts
import sbt.Keys.baseDirectory
import sbt.Keys.compile
import sbt.Keys.concurrentRestrictions
import sbt.Keys.crossTarget
import sbt.Keys.definedTestNames
import sbt.Keys.definedTests
import sbt.Keys.envVars
import sbt.Keys.excludeDependencies
import sbt.Keys.fileConverter
import sbt.Keys.fork
import sbt.Keys.fullClasspath
import sbt.Keys.libraryDependencies
import sbt.Keys.loadedTestFrameworks
import sbt.Keys.moduleName
import sbt.Keys.onComplete
import sbt.Keys.organization
import sbt.Keys.packageBin
import sbt.Keys.packagedArtifacts
import sbt.Keys.resourceGenerators
import sbt.Keys.resourceManaged
import sbt.Keys.run
import sbt.Keys.runMain
import sbt.Keys.scalaVersion
import sbt.Keys.scalacOptions
import sbt.Keys.selectMainClass
import sbt.Keys.sourceDirectories
import sbt.Keys.sourceDirectory
import sbt.Keys.streams
import sbt.Keys.target
import sbt.Keys.test
import sbt.Keys.testFrameworks
import sbt.Keys.unmanagedResourceDirectories
import sbt.Keys.unmanagedSourceDirectories
import sbt.Keys.version
import sbt.io.Hash
import sbt.librarymanagement.Configurations.Runtime
import sbt.librarymanagement.InclExclRule
import sbt.librarymanagement.ModuleID
import sbt.librarymanagement.Platform
import sbt.plugins.JvmPlugin
import sbt.util.ActionCache
import sbt.util.BuildWideCacheConfiguration
import sbt.util.CacheLevelTag
import sbt.util.Digest
import sbt.util.Logger
import sjsonnew.BasicJsonProtocol.given
import xsbti.FileConverter
import xsbti.HashedVirtualFileRef

import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.jar.Manifest
import java.util.zip.ZipFile

import scala.scalanative.build.Build
import scala.scalanative.build.BuildTarget
import scala.scalanative.build.Config
import scala.scalanative.build.Discover
import scala.scalanative.build.Logger as NativeLogger
import scala.scalanative.build.NativeConfig
import scala.scalanative.testinterface.adapter.TestAdapter
import scala.scalanative.util.Scope
import scala.util.Using

import snx.SNXError
import snx.sbt.SNXImports.*

/** sbt plugin for Scala Native projects: it adds the Scala Native compiler plugin and runtime dependencies, resolves
  * the native target platform, links the native binary, and routes `run`/`test` through it. See
  * [[SNXImports$ SNXImports]] for the settings and tasks it adds to `build.sbt`.
  */
object SNXPlugin extends AutoPlugin:

  override def requires: Plugins = JvmPlugin
  override def trigger: PluginTrigger = noTrigger

  val autoImport: SNXImports.type = SNXImports

  // The Scala Native 0.5 platform suffix - a `%%` dependency resolves as `<name>_native0.5_3`.
  final private val nativePlatform = "native0.5"

  // The Scala Native test entry point, linked as the main class of a test binary.
  final private val testMain = "scala.scalanative.testinterface.TestMain"

  // Serialises native links: a link writes the JVM-global target-triple system property, so concurrent links of
  // differing targets would corrupt one another.
  final private val linkTag = Tags.Tag("snx-link")

  // Test adapters opened by `loadedTestFrameworks`, closed when the enclosing sbt task completes.
  private val adapters = new AtomicReference[List[TestAdapter]](Nil)

  override def globalSettings: Seq[Setting[?]] = Seq(
    concurrentRestrictions += Tags.limit(linkTag, 1),
    onComplete := {
      val previous = onComplete.value
      () =>
        previous()
        adapters.getAndSet(Nil).foreach(_.close())
    }
  )

  override def buildSettings: Seq[Setting[?]] = Seq(
    SNX.target := SNX.host
  )

  override def projectSettings: Seq[Setting[?]] =
    nativeDependencies ++ Seq(
      Keys.platform := nativePlatform,
      SNX.runtime := Def.uncached {
        val triple = Discover.targetTriple(resolveClang(SNX.clang.value))
        NativeRuntime.parse(SNX.target.value, triple)
      },
      SNX.clang := None,
      SNX.clangPP := None,
      SNX.includeDirs := Seq.empty,
      SNX.libDirs := Seq.empty,
      SNX.deliverable := Deliverable.NIR,
      SNX.mode := Mode.default,
      SNX.gc := GC.default,
      SNX.lto := LTO.default,
      SNX.optimize := NativeConfig.empty.optimize,
      SNX.sanitizer := None,
      SNX.multithreading := None,
      SNX.modifiers := Seq.empty,
      SNX.dependencies := Seq.empty,
      SNX.flags := PartialFunction.empty,
      SNX.libraries := PartialFunction.empty,
      SNX.classified := false,
      libraryDependencies ++= SNX.dependencies.value.map(dependency => derive(SNX.target.value, dependency)),
      Compile / resourceGenerators += descriptorResource.taskValue
    ) ++ classifiedPublish ++ inConfig(Compile)(relativization ++ pathSettings ++ nativeSettings(testConfig = false)) ++
      inConfig(Test)(relativization ++ pathSettings ++ nativeSettings(testConfig = true) ++ testSettings)

  /** Add the Scala Native compiler plugin (JVM-pinned: it is a JVM plugin, not a native artefact) and the runtime
    * libraries, excluding the `_2.13`-cross standard libraries.
    */
  private def nativeDependencies: Seq[Setting[?]] = Seq(
    libraryDependencies ++= {
      val org = "org.scala-native"
      val ver = BuildInfo.nativeVersion
      val standard = Seq("nativelib", "clib", "posixlib", "windowslib", "javalib", "auxlib")
      val runtime = (org %% "scala3lib" % s"${scalaVersion.value}+$ver") +: standard.map(org %% _ % ver)
      val plugin = compilerPlugin((org % "nscplugin" % ver).cross(CrossVersion.full)).platform(Platform.jvm)
      (plugin +: runtime) :+ (org %% "test-interface" % ver % Test)
    },
    excludeDependencies ++= {
      val suffix = s"_${nativePlatform}_2.13"
      Seq("nativelib", "clib", "posixlib", "windowslib", "javalib", "auxlib", "scalalib")
        .map(lib => InclExclRule("org.scala-native", lib + suffix))
    }
  )

  /** Relativise NIR source positions so the compiled output embeds no absolute paths, per configuration. */
  private def relativization: Seq[Setting[?]] = Seq(
    compile / scalacOptions += {
      val paths = sourceDirectories.value.map(_.getAbsolutePath.nn).mkString(";")
      s"-P:scalanative:positionRelativizationPaths:$paths"
    }
  )

  /** Inject the per-platform source and resource directories (`scalanative-<os>`/`-<os>-<arch>`,
    * `resources-<os>`/`-<os>-<arch>`) for the enclosing configuration; only the resolved target's contribute.
    */
  private def pathSettings: Seq[Setting[?]] = Seq(
    unmanagedSourceDirectories ++= perPlatformDirs(sourceDirectory.value, "scalanative", SNX.target.value),
    unmanagedResourceDirectories ++= perPlatformDirs(sourceDirectory.value, "resources", SNX.target.value)
  )

  /** The `<prefix>-<os>` and `<prefix>-<os>-<arch>` siblings under a configuration's source directory. */
  private def perPlatformDirs(directory: File, prefix: String, target: TargetPlatform): Seq[File] =
    Seq(target.os.token, target.classifier).map(suffix => new File(directory, s"$prefix-$suffix"))

  /** The native configuration, link, and `run` settings shared by the Compile and Test configurations. */
  private def nativeSettings(testConfig: Boolean): Seq[Setting[?]] =
    configSettings(testConfig) ++ linkSettings(testConfig) ++ runSettings

  /** Fold the resolved native configuration: the discovered toolchain base, the scalar settings, the propagated link
    * requirements (the project's own [[ownRequirements]] plus the link classpath's descriptors, de-duplicated), then
    * the platform-matched modifiers, applied last. The main link resolves the Runtime classpath, a test link the Test
    * classpath.
    */
  private def configSettings(testConfig: Boolean): Seq[Setting[?]] = Seq(
    SNX.config := Def.uncached {
      val runtime = SNX.runtime.value
      val cross = SNX.target.value != SNX.host
      val clang = resolveClang(SNX.clang.value)
      val clangPP = resolveClangPP(SNX.clangPP.value)
      val base =
        discovered(clang, clangPP, SNX.includeDirs.value, SNX.libDirs.value, cross)
          .withMode(SNX.mode.value)
          .withGC(SNX.gc.value)
          .withLTO(SNX.lto.value)
          .withOptimize(SNX.optimize.value)
          .withSanitizer(SNX.sanitizer.value)
          .withMultithreading(SNX.multithreading.value)
      val scope = if testConfig then testConfigs else mainConfigs
      val libraries = SNX.libraries.value.applyOrElse(runtime, (_: NativeRuntime) => Seq.empty[NativeLibrary]).filter(visible(_, scope))
      val flags = SNX.flags.value.applyOrElse(runtime, (_: NativeRuntime) => Flags.empty)
      requireProvisioned(libraries)
      val classpath = if testConfig then fullClasspath.value else (Runtime / fullClasspath).value
      val files = classpath.map(entry => fileConverter.value.toPath(entry.data).toFile.nn)
      val requirements = (ownRequirements(libraries, flags) ++ Descriptor.fold(classpathDescriptors(files), runtime)).distinct
      val staging = new File(target.value, s"snx/vendored/${if testConfig then "test" else "main"}")
      val converter = fileConverter.value
      val cache = Def.cacheConfiguration.value
      val log = streams.value.log
      val projectBase = baseDirectory.value
      val rootBase = (LocalRootProject / baseDirectory).value
      val build =
        (spec: Vendored, linkage: Linkage) =>
          buildVendored(spec, linkage, runtime, clang.toFile.nn, clangPP.toFile.nn, projectBase, rootBase, staging, cache, converter, log)
      val (rebound, reconciliation) = rebind(base, requirements, libraries.map(l => l.name -> l).toMap, runtime, build)
      // Routine for an all-system link (every requirement is a default `-l`), so log at info only when the project
      // provisions a library locally.
      val emit: String => Unit = if libraries.exists(_.provisioning != Provisioning.System) then log.info(_) else log.debug(_)
      reconciliation.foreach(emit)
      val propagated = enforceMultithreading(rebound, requirements.requiresMultithreading, SNX.multithreading.value)
      SNX.modifiers.value.foldLeft(Native(propagated))((native, modifier) => modifier.lift(runtime).fold(native)(_(native)))
    }
  )

  /** The project's own requirements as a wire `Usage`: each library's name in its [[LinkMode]] channel, plus the
    * [[Flags]] residual.
    */
  private[sbt] def ownRequirements(libraries: Seq[NativeLibrary], flags: Flags): Usage =
    val plain = libraries.collect { case l if l.mode == LinkMode.Plain => l.name }
    val frameworks = libraries.collect { case l if l.mode == LinkMode.Framework => l.name }
    val wholeArchive = libraries.collect { case l if l.mode == LinkMode.WholeArchive => l.name }
    Usage(plain, frameworks, wholeArchive, flags.defines, flags.linkFlags, flags.multithreaded)

  // The configurations a library folds into the main link (compile/runtime) and the test link (+test), under sbt's
  // config extension (Test extends Runtime extends Compile).
  private val mainConfigs = Set("compile", "runtime")
  private val testConfigs = Set("compile", "runtime", "test")

  /** Whether `library` is visible at a link over `configs`. */
  private[sbt] def visible(library: NativeLibrary, configs: Set[String]): Boolean =
    library.configurations.forall(spec => spec.split(",").nn.iterator.flatMap(Option(_)).exists(name => configs.contains(name.trim.nn)))

  /** Fail when a `noSystemDefault` library is left System-provisioned, catching it at configuration time. */
  private[sbt] def requireProvisioned(libraries: Seq[NativeLibrary]): Unit =
    libraries.foreach: library =>
      if !library.systemDefault && library.provisioning == Provisioning.System then
        fail(
          SNXError.UnprovisionedLibrary(
            s"native library '${library.name}' has no system default; provision it (Vendored or Unmanaged) in SNX.libraries"))

  /** Fold the resolved requirements onto `base`: each renders its default `-l<name>` at its link position, unless a
    * local provisioning claims the name - a `Vendored` realises its archive, includes, and closure; an `Unmanaged`
    * suppresses the default. The [[Flags]] residual follows. Returns the configuration and the reconciliation report.
    */
  private[sbt] def rebind(
    base: NativeConfig,
    requirements: Usage,
    libraries: Map[String, NativeLibrary],
    runtime: NativeRuntime,
    build: (Vendored, Linkage) => Artefacts): (NativeConfig, Seq[String]) =
    val report = Seq.newBuilder[String]
    def realise(config: NativeConfig, name: String, mode: LinkMode): NativeConfig =
      libraries.get(name) match
        case Some(library) =>
          val linkage = library.linkage.applyOrElse(runtime, (_: NativeRuntime) => provisioningDefault(library.provisioning))
          library.provisioning match
            case Provisioning.Vendored(spec) =>
              if linkage == Linkage.Dynamic then requireDynamicVendorable(runtime, mode, name)
              val artefacts = build(spec, linkage)
              report += s"snx library '$name': vendored, linked ${linkageLabel(linkage)} (${artefacts.libraries.size} file(s))"
              val withLibraries = config
                .withLinkingOptions(config.linkingOptions ++ vendoredLink(runtime, mode, linkage, name, artefacts.libraries))
                .withCompileOptions(config.compileOptions ++ artefacts.includes.map(dir => s"-I${dir.getAbsolutePath}"))
              applyFlags(withLibraries, spec.closureFor(runtime))
            case Provisioning.Unmanaged =>
              report += s"snx library '$name': unmanaged source (symbols compiled in)"
              config
            case Provisioning.System =>
              report += s"snx library '$name': system, linked ${linkageLabel(linkage)}"
              config.withLinkingOptions(config.linkingOptions ++ systemLink(runtime, mode, linkage, name))
        case None =>
          report += s"snx library '$name': default -l$name"
          config.withLinkingOptions(config.linkingOptions ++ systemLink(runtime, mode, Linkage.Dynamic, name))
    val withLibraries = Seq(
      requirements.libraries -> LinkMode.Plain,
      requirements.frameworks -> LinkMode.Framework,
      requirements.wholeArchive -> LinkMode.WholeArchive
    ).foldLeft(base) { case (config, (names, mode)) => names.foldLeft(config)((cfg, name) => realise(cfg, name, mode)) }
    (applyFlags(withLibraries, Flags(requirements.defines, requirements.linkFlags, false)), report.result())
  end rebind

  /** The linkage a provisioning defaults to when a library declares none: `System` dynamic, `Vendored` static. */
  private[sbt] def provisioningDefault(provisioning: Provisioning): Linkage = provisioning match
    case Provisioning.System      => Linkage.Dynamic
    case Provisioning.Vendored(_) => Linkage.Static
    case Provisioning.Unmanaged   => Linkage.Dynamic

  /** The link tokens for a system library `name` in its link mode and linkage, rendered per platform. A dynamic
    * library is a plain `-l<name>`, framework flag, or name whole-archive; a static one forces that static.
    */
  private[sbt] def systemLink(runtime: NativeRuntime, mode: LinkMode, linkage: Linkage, name: String): Seq[String] =
    val dynamic = dynamicSystemLink(runtime, mode, name)
    linkage match
      case Linkage.Dynamic => dynamic
      case Linkage.Static  =>
        mode match
          case LinkMode.Framework =>
            fail(SNXError.UnsupportedLinkage(s"native framework '$name' cannot be linked statically"))
          case LinkMode.Plain | LinkMode.WholeArchive => staticSystem(runtime, name, dynamic)

  /** A system library's dynamic link tokens: `-l<name>`, the macOS framework flag, or the name whole-archive form. */
  private def dynamicSystemLink(runtime: NativeRuntime, mode: LinkMode, name: String): Seq[String] =
    mode match
      case LinkMode.Plain        => Seq("-l" + name)
      case LinkMode.WholeArchive => Modifier.wholeArchiveName(runtime, name)
      case LinkMode.Framework    =>
        runtime match
          case NativeRuntime.Darwin(_)                                 => Seq("-framework", name)
          case NativeRuntime.Linux(_, _) | NativeRuntime.Windows(_, _) => Seq.empty

  /** Force a system library's `dynamic` tokens static, per platform: GNU brackets with `-Bstatic`/`-Bdynamic`; MSVC
    * names the static `.lib` (its linker has no `-Bstatic`); macOS cannot force a `-l` static, so it fails fast.
    */
  private def staticSystem(runtime: NativeRuntime, name: String, dynamic: Seq[String]): Seq[String] =
    runtime match
      case NativeRuntime.Linux(_, _) | NativeRuntime.Windows(_, ABI.MinGw) =>
        Seq("-Wl,-Bstatic") ++ dynamic ++ Seq("-Wl,-Bdynamic")
      case NativeRuntime.Windows(_, ABI.Msvc) => dynamic
      case NativeRuntime.Darwin(_)            =>
        fail(
          SNXError.UnsupportedLinkage(
            s"system library '$name' cannot be linked statically on macOS; provision it as Vendored to supply a static archive"))

  /** The link tokens for a vendored library's built files, per linkage. A `Static` build links its archive(s) by path
    * ([[archiveLink]]); a `Dynamic` build links `-l<name>` against the built shared library and adds its directory to
    * the link and runtime search paths (`-L`, `-rpath`) - a system dynamic link plus the build's own search path, the
    * target supplying the shared library at runtime. The infeasible dynamic combinations are rejected before the build
    * by [[requireDynamicVendorable]], so only the feasible ones are rendered here.
    */
  private def vendoredLink(runtime: NativeRuntime, mode: LinkMode, linkage: Linkage, name: String, libraries: Seq[File]): Seq[String] =
    linkage match
      case Linkage.Static  => archiveLink(runtime, mode, libraries)
      case Linkage.Dynamic =>
        val dirs = libraries.map(_.getParentFile.nn.getAbsolutePath.nn).distinct
        dynamicSystemLink(runtime, mode, name) ++ dirs.flatMap(dir => Seq("-L" + dir) ++ runtimeSearch(runtime, dir))

  /** Reject the infeasible dynamic-linkage requests for a vendored library before it is built: whole-archive is a
    * static-archive operation, and a dynamically-linked vendored library on Windows needs DLL redistribution (a
    * follow-on). The feasible cases - `Plain`/`Framework` on Linux or macOS - pass.
    */
  private def requireDynamicVendorable(runtime: NativeRuntime, mode: LinkMode, name: String): Unit =
    mode match
      case LinkMode.WholeArchive =>
        fail(
          SNXError.UnsupportedLinkage(
            s"vendored native library '$name' is whole-archive, a static-archive operation, and cannot be linked dynamically"))
      case LinkMode.Plain | LinkMode.Framework => ()
    runtime match
      case NativeRuntime.Linux(_, _) | NativeRuntime.Darwin(_) => ()
      case NativeRuntime.Windows(_, _)                         =>
        fail(
          SNXError.UnsupportedLinkage(
            s"dynamically linking the vendored native library '$name' on Windows requires DLL redistribution, a follow-on; " +
              "link it statically, or declare it System to link a host-provided library"))

  /** The runtime search-path tokens so a dynamically-linked vendored library is found when the binary runs on the build
    * host (its tests and local `run`): an `-rpath` entry pointing at the build directory. PROVEN on Linux; the macOS
    * `-rpath` is emitted but not yet CI-verified (no local mac host - CMake's default `@rpath` install names, `CMP0042`,
    * are expected to resolve it). Windows has no `-rpath`, so a dynamically-linked vendored DLL is rejected earlier
    * ([[requireDynamicVendorable]]).
    */
  private def runtimeSearch(runtime: NativeRuntime, dir: String): Seq[String] = runtime match
    case NativeRuntime.Linux(_, _) | NativeRuntime.Darwin(_) => Seq("-Wl,-rpath," + dir)
    case NativeRuntime.Windows(_, _)                         => Seq.empty

  /** The link tokens for a vendored library's built static archives, in its link-mode syntax. */
  private def archiveLink(runtime: NativeRuntime, mode: LinkMode, archives: Seq[File]): Seq[String] =
    mode match
      case LinkMode.WholeArchive => archives.flatMap(archive => Modifier.wholeArchivePath(runtime, archive.getAbsolutePath.nn))
      case LinkMode.Plain | LinkMode.Framework => archives.map(_.getAbsolutePath.nn)

  /** A short label for a linkage, for the reconciliation diagnostic. */
  private def linkageLabel(linkage: Linkage): String = linkage match
    case Linkage.Static  => "static"
    case Linkage.Dynamic => "dynamic"

  /** Append `flags`' link flags and `-D` defines onto `config` (multithreading is enforced separately,
    * [[enforceMultithreading]]).
    */
  private def applyFlags(config: NativeConfig, flags: Flags): NativeConfig =
    Contribution.merge(config, Contribution.empty.linkOptions(flags.linkFlags*).define(flags.defines*))

  /** Bind [[SNXImports.SNX.link]] to the tagged link task; uncached because the result is a `File`. */
  private def linkSettings(testConfig: Boolean): Seq[Setting[?]] = Seq(
    SNX.link := Def.uncached(linkTask(testConfig).value)
  )

  /** Link the Scala Native binary: assemble the toolchain `Config` over the resolved [[SNXImports.SNX.config]] and
    * drive the Scala Native build inside a fresh `Scope`. A test binary links `TestMain` as an application; a main
    * binary links the deliverable. Tagged to serialise concurrent links.
    */
  private def linkTask(testConfig: Boolean): Def.Initialize[Task[File]] =
    Def
      .uncachedTask {
        val (buildTarget, mainClass) =
          if testConfig then (BuildTarget.application, Some(testMain))
          else resolveTarget(SNX.deliverable.value, selectMainClass.value)
        val converter = fileConverter.value
        val entries = if testConfig then fullClasspath.value else (Runtime / fullClasspath).value
        val classpath = entries.map(entry => converter.toPath(entry.data))
        val compilerConfig = SNX.config.value.config.withBuildTarget(buildTarget)
        val config = Config.empty
          .withLogger(nativeLogger(streams.value.log))
          .withClassPath(classpath)
          .withBaseDir(crossTarget.value.toPath.nn)
          .withModuleName(moduleName.value)
          .withMainClass(mainClass)
          .withTestConfig(testConfig)
          .withCompilerConfig(compilerConfig)
        Scope(implicit scope => Build.buildCachedAwait(config).toFile.nn)
      }
      .tag(linkTag)

  /** Override `run` to execute the linked binary with the configured environment, and reject `runMain` (a native
    * binary has a single entry point).
    */
  private def runSettings: Seq[Setting[?]] = Seq(
    run := Def.inputTask {
      val arguments = Def.spaceDelimited("<arg>").parsed
      val binary = SNX.link.value
      val command = binary.getAbsolutePath.nn +: arguments
      val log = streams.value.log
      log.info(command.mkString("running ", " ", ""))
      val exitCode = scala.sys.process.Process(command, baseDirectory.value, (run / envVars).value.toSeq*).!
      if exitCode != 0 then fail(SNXError.RunFailed(s"Nonzero exit code from ${binary.getName}: $exitCode"))
    }.evaluated,
    runMain := Def.inputTask {
      val _ = Def.spaceDelimited("<arg>").parsed
      fail(SNXError.RunMainUnsupported("runMain is unsupported for Scala Native - a native binary has one entry point; use run."))
    }.evaluated
  )

  /** Route the Test configuration's test frameworks through the linked native test binary. Overriding
    * `loadedTestFrameworks` (rather than `test`) keeps sbt's own selection and reporting; `definedTestNames` is
    * re-triggered off it so a plain `Test / compile` does not link the test binary.
    */
  private def testSettings: Seq[Setting[?]] = Seq(
    loadedTestFrameworks := Def.uncached {
      if fork.value then fail(SNXError.TestForkUnsupported("Test / fork must be false for a Scala Native project."))
      val frameworks = testFrameworks.value
      val names = frameworks.map(_.implClassNames.toList).toList
      val config = TestAdapter
        .Config()
        .withBinaryFile(SNX.link.value)
        .withEnvVars((test / envVars).value)
        .withLogger(nativeLogger(streams.value.log))
      val adapter = register(new TestAdapter(config))
      frameworks.zip(adapter.loadFrameworks(names)).collect { case (framework, Some(loaded)) => (framework, loaded) }.toMap
    },
    definedTestNames := definedTests
      .map(_.map(_.name).distinct)
      .storeAs(definedTestNames)
      .triggeredBy(loadedTestFrameworks)
      .value
  )

  /** Abort the enclosing task by raising the typed `error`. */
  private def fail(error: SNXError): Nothing =
    throw error // scalafix:ok DisableSyntax.throw

  /** Register a test adapter for closure when the enclosing sbt task completes (see `onComplete`). */
  private def register(adapter: TestAdapter): TestAdapter =
    val _ = adapters.updateAndGet(opened => adapter :: opened)
    adapter

  /** Bridge an sbt logger to a Scala Native build logger. */
  private def nativeLogger(log: sbt.util.Logger): NativeLogger =
    NativeLogger(
      (throwable: Throwable) => log.trace(throwable),
      (message: String) => log.debug(message),
      (message: String) => log.info(message),
      (message: String) => log.warn(message),
      (message: String) => log.error(message)
    )

  /** Resolve a deliverable and main class into the build target and main class. Fails fast for an unlinked `NIR` or a
    * missing `Executable` main class. The build target follows the deliverable alone - a `Library.Static` archives, a
    * `Library.Shared` links a shared object, an `Executable` links an application; the static C runtime is a separate
    * opt-in ([[SNXImports.SNX.staticRuntime]]), and the test binary is always an application.
    */
  private[sbt] def resolveTarget(deliverable: Deliverable, main: Option[String]): (BuildTarget, Option[String]) =
    deliverable match
      case Deliverable.NIR =>
        fail(SNXError.NotLinkable("the NIR deliverable is published as a jar, not linked"))
      case Deliverable.Library.Static => (BuildTarget.libraryStatic, None)
      case Deliverable.Library.Shared => (BuildTarget.libraryDynamic, None)
      case Deliverable.Executable     =>
        if main.isEmpty then fail(SNXError.MissingMainClass("an Executable deliverable requires a main class"))
        else (BuildTarget.application, main)

  /** Derive the resolved `ModuleID`: a classified dependency resolves under the target's OS/arch classifier. */
  private[sbt] def derive(target: TargetPlatform, dependency: NativeDependency): ModuleID =
    if dependency.classified then dependency.module.classifier(target.classifier) else dependency.module

  /** The project's own exported requirements per runtime: its main-visible `SNX.libraries` names and modes plus the
    * `SNX.flags` residual. Test-only and `Unmanaged` libraries are excluded.
    */
  private def exportedRequirements(
    libraries: PartialFunction[NativeRuntime, Seq[NativeLibrary]],
    flags: PartialFunction[NativeRuntime, Flags]): PartialFunction[NativeRuntime, Usage] = { case runtime =>
    val exported = libraries
      .applyOrElse(runtime, (_: NativeRuntime) => Seq.empty[NativeLibrary])
      .filter(library => library.provisioning != Provisioning.Unmanaged && visible(library, mainConfigs))
    ownRequirements(exported, flags.applyOrElse(runtime, (_: NativeRuntime) => Flags.empty))
  }

  /** Write the NIR deliverable's descriptor as a managed resource, byte-stably and only when its content changes. A
    * non-NIR deliverable or an empty requirement set writes nothing.
    */
  private def descriptorResource: Def.Initialize[Task[Seq[File]]] = Def.uncachedTask {
    if SNX.deliverable.value != Deliverable.NIR then Seq.empty
    else
      val module = Module(organization.value, moduleName.value, version.value)
      val requirements = exportedRequirements(SNX.libraries.value, SNX.flags.value)
      val descriptor = Descriptor.build(module, SNX.classified.value, SNX.target.value, requirements)
      if descriptor.usage.isEmpty then Seq.empty
      else
        val resource = new File((Compile / resourceManaged).value, Descriptor.resourcePath)
        writeIfChanged(resource, Descriptor.render(descriptor))
        Seq(resource)
  }

  /** Write `content` to `file` only when it differs from the current content, creating parent directories. */
  private def writeIfChanged(file: File, content: String): Unit =
    val current = if file.isFile then Some(IO.read(file)) else None
    if !current.contains(content) then IO.write(file, content)

  /** Classified publishing for a per-platform NIR library ([[SNXImports.SNX.classified]]): the native content
    * publishes under the build's OS/arch classifier, the unclassified main coordinate carries a manifest-only
    * placeholder jar. Disabled by default.
    */
  private def classifiedPublish: Seq[Setting[?]] = Seq(
    artifacts := {
      val base = artifacts.value
      if SNX.classified.value then base :+ (Compile / packageBin / artifact).value.withClassifier(Some(SNX.target.value.classifier))
      else base
    },
    packagedArtifacts := Def.uncached {
      val base = packagedArtifacts.value
      if !SNX.classified.value then base
      else
        val mainArtifact = (Compile / packageBin / artifact).value
        // Both derive from the same main artefact so the (classifier, type, extension) keys match; otherwise the
        // classified entry is orphaned and silently not published.
        val classified = mainArtifact.withClassifier(Some(SNX.target.value.classifier))
        val content = (Compile / packageBin).value
        val placeholderRef: HashedVirtualFileRef =
          fileConverter.value.toVirtualFile(placeholder(new File(target.value, s"snx/${moduleName.value}.jar"), moduleName.value).toPath.nn)
        base.updated(mainArtifact, placeholderRef).updated(classified, content)
    }
  )

  // 1980-01-01T00:00:00Z (the earliest zip timestamp); fixed so the placeholder is byte-identical across rebuilds.
  final private val placeholderEpochMillis: Long = 315532800000L

  /** Write a manifest-only placeholder jar for the unclassified main artefact. */
  private def placeholder(out: File, name: String): File =
    val manifest = new Manifest()
    val attributes = manifest.getMainAttributes.nn
    attributes.putValue("Manifest-Version", "1.0")
    attributes.putValue("Snx-Placeholder", s"$name: platform artefacts publish under OS/arch classifiers.")
    IO.jar(Seq.empty[(File, String)], out, manifest, Some(placeholderEpochMillis))
    out

  /** The descriptors carried by the entries of a consumer's classpath, in classpath (dependency) order. */
  private[sbt] def classpathDescriptors(files: Seq[File]): Seq[Descriptor] =
    files.flatMap(file => descriptorText(file).map(Descriptor.parse))

  /** The descriptor text a classpath entry carries at [[Descriptor.resourcePath]] - a directory file or a jar entry. */
  private def descriptorText(file: File): Option[String] =
    if file.isDirectory then
      val resource = new File(file, Descriptor.resourcePath)
      if resource.isFile then Some(IO.read(resource)) else None
    else if file.isFile && file.getName.nn.endsWith(".jar") then
      Using.resource(new ZipFile(file)): archive =>
        Option(archive.getEntry(Descriptor.resourcePath)).map: entry =>
          Using.resource(archive.getInputStream(entry).nn)(stream => new String(stream.readAllBytes().nn, "UTF-8"))
    else None

  /** Enforce a propagated multithreading requirement: force multithreading on, or fail if the project disabled it. */
  private[sbt] def enforceMultithreading(config: NativeConfig, required: Boolean, setting: Option[Boolean]): NativeConfig =
    if !required then config
    else
      setting match
        case Some(false) =>
          fail(SNXError.MultithreadingRequired("a native dependency requires multithreading, which this project has disabled"))
        case _ => config.withMultithreading(Some(true))

  /** Build a vendored library for `runtime`: a `Local` origin resolves its directory, a `Git` origin clones at the
    * pinned ref; the cache keys on the source content.
    */
  private def buildVendored(
    library: Vendored,
    linkage: Linkage,
    runtime: NativeRuntime,
    clang: File,
    clangPP: File,
    projectBase: File,
    rootBase: File,
    staging: File,
    cache: BuildWideCacheConfiguration,
    converter: FileConverter,
    log: Logger): Artefacts =
    library.origin match
      case Origin.Local(directory) =>
        val location = resolveDir(directory, projectBase, rootBase)
        cachedBuild(
          slug(directory),
          library.backend,
          runtime,
          linkage,
          clang,
          clangPP,
          Vendored.contentDigest(location),
          () => location,
          staging,
          cache,
          converter,
          log)
      case Origin.Git(uri, ref) =>
        val clones = new File(staging, "clones")
        cachedBuild(
          s"git-${Hash.trimHashString(s"$uri@$ref", 8)}",
          library.backend,
          runtime,
          linkage,
          clang,
          clangPP,
          s"git:$uri@$ref",
          () => fetch(uri, ref, clones),
          staging,
          cache,
          converter,
          log
        )

  /** Build `backend` for `runtime`, cached per library: `locate` resolves the source on a miss only, and the key
    * combines `sourceId`, [[toolchainId]], and `backend.cacheKey`. [[CacheLevelTag.Local]] - a compiled archive is not
    * portable.
    */
  private def cachedBuild(
    name: String,
    backend: Backend,
    runtime: NativeRuntime,
    linkage: Linkage,
    clang: File,
    clangPP: File,
    sourceId: String,
    locate: () => File,
    staging: File,
    cache: BuildWideCacheConfiguration,
    converter: FileConverter,
    log: Logger): Artefacts =
    val sourceStaging = new File(staging, name)
    val outputDir = cache.outputDirectory
    val key =
      List(BuildInfo.version, runtime.toString, linkage.toString, toolchainId(clang, clangPP), sourceId) ++ backend.cacheKey(runtime)
    val (archives, includes) = ActionCache.cache[Seq[String], (Seq[String], Seq[String])](
      key,
      Digest.zero,
      Digest.zero,
      List(CacheLevelTag.Local),
      cache
    ) { _ =>
      val location = locate()
      IO.delete(sourceStaging)
      val built = backend.build(BuildContext(location, sourceStaging, runtime, linkage, clang, clangPP, log))
      requireStaged(built.libraries ++ built.includes, sourceStaging)
      val outputs =
        built.libraries.map(file => converter.toVirtualFile(file.toPath.nn)) ++
          built.includes.map(dir => ActionCache.packageDirectory(converter.toVirtualFile(dir.toPath.nn), converter, outputDir))
      def relative(files: Seq[File]): Seq[String] = files.map(file => outputDir.relativize(file.toPath).toString)
      ActionCache.InternalActionResult((relative(built.libraries), relative(built.includes)), outputs)
    }
    def absolute(paths: Seq[String]): Seq[File] = paths.map(path => outputDir.resolve(path).nn.toFile.nn)
    Artefacts(absolute(archives), absolute(includes))
  end cachedBuild

  /** Verify every vendored output lies under `staging` so the action cache can capture it; fail clearly otherwise. */
  private[sbt] def requireStaged(outputs: Seq[File], staging: File): Unit =
    val root = staging.toPath.nn.toAbsolutePath.nn.normalize.nn
    outputs.foreach: output =>
      if !output.toPath.nn.toAbsolutePath.nn.normalize.nn.startsWith(root) then
        fail(
          SNXError.OutputOutsideStaging(s"snx: vendored output '${output.getAbsolutePath}' lies outside the build staging directory " +
            s"'${staging.getAbsolutePath}'; a backend must write its archives and headers under the BuildContext staging directory"))

  /** Resolve a `Local` vendored directory: relative to the project base if it exists there, else to the build root. */
  private def resolveDir(directory: String, projectBase: File, rootBase: File): File =
    val inProject = new File(projectBase, directory)
    if inProject.isDirectory then inProject else new File(rootBase, directory)

  /** Clone `uri` at `ref` into a cached subdirectory of `clones`, reusing an existing clone. */
  private def fetch(uri: String, ref: String, clones: File): File =
    val keyed = new java.net.URI(s"$uri#$ref")
    val localCopy = Resolvers.uniqueSubdirectoryFor(keyed, clones)
    Resolvers.creates(localCopy) {
      Resolvers.run("git", "clone", uri, localCopy.getAbsolutePath)
      Resolvers.run(Some(localCopy), "git", "checkout", "-q", ref)
    }

  /** A filesystem-safe staging label for a vendored origin. */
  private def slug(value: String): String = value.replaceAll("[^A-Za-z0-9]+", "-").nn

  /** A toolchain identity for the vendored cache key: the resolved C/C++ compilers actually used - their paths and the C
    * compiler's `--version`, so an `SNX.clang`/`SNX.clangPP` override or an in-place upgrade invalidates - plus the
    * `cmake` version. It does NOT capture the CMake backend's ambient build environment (`CC`/`CXX`/`CFLAGS`, the MSVC
    * target arch), which CMake detects itself; a change there that leaves these unchanged reuses a cached archive.
    */
  private def toolchainId(clang: File, clangPP: File): String =
    def version(tool: String): String =
      scala.util
        .Try(scala.sys.process.Process(Seq(tool, "--version")).!!.linesIterator.find(_.nonEmpty).getOrElse(""))
        .toOption
        .getOrElse("")
    s"clang=${clang.getAbsolutePath}@${version(clang.getAbsolutePath.nn)};clang++=${clangPP.getAbsolutePath};cmake=${version("cmake")}"

  /** The toolchain base: the resolved clang/clang++, the discovered compile/link options (host paths cross-stripped),
    * and the user's include/lib directories.
    */
  private def discovered(clang: Path, clangPP: Path, includeDirs: Seq[File], libDirs: Seq[File], cross: Boolean): NativeConfig =
    val compileOptions = crossStrip(Discover.compileOptions(), "-I", cross) ++ includeDirs.map(dir => "-I" + dir.getAbsolutePath)
    val linkingOptions = crossStrip(Discover.linkingOptions(), "-L", cross) ++ libDirs.map(dir => "-L" + dir.getAbsolutePath)
    NativeConfig.empty
      .withClang(clang)
      .withClangPP(clangPP)
      .withCompileOptions(compileOptions)
      .withLinkingOptions(linkingOptions)

  /** Drop options beginning with `prefix` (a host `-I`/`-L` search path) when cross-targeting. */
  private[sbt] def crossStrip(options: Seq[String], prefix: String, cross: Boolean): Seq[String] =
    if cross then options.filterNot(_.startsWith(prefix)) else options

  /** The C compiler: the `SNX.clang` override, or the toolchain's discovered `clang`. */
  private def resolveClang(setting: Option[File]): Path = setting.map(_.toPath.nn).getOrElse(Discover.clang())

  /** The C++ compiler: the `SNX.clangPP` override, or the toolchain's discovered `clang++`. */
  private def resolveClangPP(setting: Option[File]): Path = setting.map(_.toPath.nn).getOrElse(Discover.clangpp())

end SNXPlugin
