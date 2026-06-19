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
import sbt.internal.util.MessageOnlyException
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
      SNX.linkage := { case _ => Linkage.Dynamic },
      SNX.mode := Mode.default,
      SNX.gc := GC.default,
      SNX.lto := LTO.default,
      SNX.optimize := NativeConfig.empty.optimize,
      SNX.sanitizer := None,
      SNX.multithreading := None,
      SNX.modifiers := Seq.empty,
      SNX.dependencies := Seq.empty,
      SNX.vendored := Seq.empty,
      SNX.usage := PartialFunction.empty,
      SNX.classified := false,
      libraryDependencies ++= SNX.dependencies.value.map(dependency => derive(SNX.target.value, dependency)),
      Compile / resourceGenerators += descriptorResource.taskValue
    ) ++ classifiedPublish ++ inConfig(Compile)(relativization ++ pathSettings ++ nativeSettings(testConfig = false)) ++
      inConfig(Test)(relativization ++ pathSettings ++ nativeSettings(testConfig = true) ++ testSettings)

  /** Adds the Scala Native compiler plugin and runtime libraries the project compiles against. The `nscplugin` is
    * pinned to the JVM platform - it is a JVM compiler plugin, not a native artefact; the `_2.13`-cross standard
    * libraries are excluded because Scala Native cross-publishes them for both Scala 2.13 and 3.
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

  /** Inject the per-platform source and resource directories for the enclosing configuration -
    * `scalanative-<os>` / `scalanative-<os>-<arch>` (sources) and `resources-<os>` / `resources-<os>-<arch>`
    * (resources), siblings of `scala` / `resources`. sbt merges them with the defaults; only the resolved target's
    * directories contribute. A `scala-native/` subdirectory for unmanaged C is the user's own concern within any of them.
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
    * requirements - the project's own ([[combineRequirements]]) combined with the descriptors carried by the link
    * classpath ([[Descriptor.fold]]), de-duplicated then rendered - and finally the platform-matched modifiers
    * (applied last, so a modifier overrides a scalar). The main link resolves the Runtime classpath - a native binary
    * links every dependency at build time, so a `% Runtime` dependency must be included, exactly as sbt's own `run`
    * uses the Runtime classpath; a test link resolves the Test classpath.
    */
  private def configSettings(testConfig: Boolean): Seq[Setting[?]] = Seq(
    SNX.config := Def.uncached {
      val runtime = SNX.runtime.value
      val configuration = if testConfig then Test.name else Runtime.name
      val cross = SNX.target.value != SNX.host
      val base =
        discovered(resolveClang(SNX.clang.value), resolveClangPP(SNX.clangPP.value), SNX.includeDirs.value, SNX.libDirs.value, cross)
          .withMode(SNX.mode.value)
          .withGC(SNX.gc.value)
          .withLTO(SNX.lto.value)
          .withOptimize(SNX.optimize.value)
          .withSanitizer(SNX.sanitizer.value)
          .withMultithreading(SNX.multithreading.value)
      val own = combineRequirements(SNX.usage.value, SNX.dependencies.value, configuration, runtime)
      val classpath = if testConfig then fullClasspath.value else (Runtime / fullClasspath).value
      val files = classpath.map(entry => fileConverter.value.toPath(entry.data).toFile.nn)
      val requirements = (own ++ Descriptor.fold(classpathDescriptors(files), runtime)).distinct
      val rendered = Contribution.merge(base, Usage.render(requirements, runtime))
      val staging = new File(target.value, s"snx/vendored/${if testConfig then "test" else "main"}")
      val withVendored = foldVendored(
        rendered,
        SNX.vendored.value,
        runtime,
        baseDirectory.value,
        (LocalRootProject / baseDirectory).value,
        staging,
        Def.cacheConfiguration.value,
        fileConverter.value,
        streams.value.log
      )
      val propagated = enforceMultithreading(withVendored, requirements.requiresMultithreading, SNX.multithreading.value)
      SNX.modifiers.value.foldLeft(Native(propagated))((native, modifier) => modifier.lift(runtime).fold(native)(_(native)))
    }
  )

  /** Bind [[SNXImports.SNX.link]] to the tagged link task for the enclosing configuration. The result is a `File`,
    * which is not a cacheable task output, so the binding is uncached; the tagged inner task still serialises links.
    */
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
        val runtime = SNX.runtime.value
        val linkage = SNX.linkage.value.applyOrElse(runtime, (_: NativeRuntime) => Linkage.Dynamic)
        val (buildTarget, static, mainClass) =
          if testConfig then
            val (target, statically) = resolveTestTarget(SNX.deliverable.value, linkage, runtime)
            (target, statically, Some(testMain))
          else resolveTarget(SNX.deliverable.value, linkage, runtime, selectMainClass.value)
        val converter = fileConverter.value
        val entries = if testConfig then fullClasspath.value else (Runtime / fullClasspath).value
        val classpath = entries.map(entry => converter.toPath(entry.data))
        val base = SNX.config.value.config.withBuildTarget(buildTarget)
        val compilerConfig = if static then base.withLinkingOptions(base.linkingOptions :+ "-static") else base
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
      if exitCode != 0 then fail(s"Nonzero exit code from ${binary.getName}: $exitCode")
    }.evaluated,
    runMain := Def.inputTask {
      val _ = Def.spaceDelimited("<arg>").parsed
      fail("runMain is unsupported for Scala Native - a native binary has one entry point; use run.")
    }.evaluated
  )

  /** Route the Test configuration's test frameworks through the linked native test binary. Overriding
    * `loadedTestFrameworks` (rather than `test`) keeps sbt's own selection and reporting; `definedTestNames` is
    * re-triggered off it so a plain `Test / compile` does not link the test binary.
    */
  private def testSettings: Seq[Setting[?]] = Seq(
    loadedTestFrameworks := Def.uncached {
      if fork.value then fail("Test / fork must be false for a Scala Native project.")
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

  /** Abort the enclosing task with a clean, stack-trace-free message. */
  private def fail(message: String): Nothing =
    throw MessageOnlyException(message) // scalafix:ok DisableSyntax.throw

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

  /** Resolve a deliverable, its per-platform linkage, and the selected main class into the Scala Native build
    * target, whether to link statically, and the main class to embed. Fails fast for an unlinked `NIR` deliverable,
    * a missing `Executable` main class, or static-executable linking where the platform cannot support it.
    */
  private[sbt] def resolveTarget(
    deliverable: Deliverable,
    linkage: Linkage,
    runtime: NativeRuntime,
    main: Option[String]): (BuildTarget, Boolean, Option[String]) =
    deliverable match
      case Deliverable.NIR =>
        sys.error("the NIR deliverable is published as a jar, not linked")
      case Deliverable.Library =>
        val buildTarget = if linkage == Linkage.Static then BuildTarget.libraryStatic else BuildTarget.libraryDynamic
        (buildTarget, false, None)
      case Deliverable.Executable =>
        if main.isEmpty then sys.error("an Executable deliverable requires a main class")
        else if linkage == Linkage.Static && !runtime.supportsStaticLinking then
          sys.error(s"static executable linking is not supported on $runtime (requires musl or MSVC)")
        else (BuildTarget.application, linkage == Linkage.Static, main)

  /** Resolve the build target for a test binary: it always links `TestMain` as an application, and is static only
    * when the deliverable is an `Executable` whose test linkage resolves to `Static` - a `Library` or `NIR`
    * deliverable's test always links dynamically, so a static-artefact intent never forces a static test link.
    */
  private[sbt] def resolveTestTarget(deliverable: Deliverable, linkage: Linkage, runtime: NativeRuntime): (BuildTarget, Boolean) =
    val effective = if deliverable == Deliverable.Executable then linkage else Linkage.Dynamic
    val (buildTarget, static, _) = resolveTarget(Deliverable.Executable, effective, runtime, Some(testMain))
    (buildTarget, static)

  /** Derive the resolved `ModuleID` for a managed native dependency: a classified one resolves under the target's
    * OS/arch classifier; the Scala Native platform suffix composes through the dependency's own `%%` cross-version.
    */
  private[sbt] def derive(target: TargetPlatform, dependency: NativeDependency): ModuleID =
    if dependency.classified then dependency.module.classifier(target.classifier) else dependency.module

  /** The project's own exported link requirements for `runtime`: its project `usage` combined with the requirements of
    * its dependencies visible in `configuration` ([[visible]]), de-duplicated. The library's own requirements only,
    * never the transitive classpath fold - a consumer applies that itself from each dependency's own descriptor.
    */
  private[sbt] def combineRequirements(
    usage: PartialFunction[NativeRuntime, Usage],
    dependencies: Seq[NativeDependency],
    configuration: String,
    runtime: NativeRuntime): Usage =
    val requirements = usage +: dependencies.filter(dependency => visible(configuration, dependency.module)).map(_.requirements)
    requirements.flatMap(_.lift(runtime)).foldLeft(Usage.empty)(_ ++ _).distinct

  /** The library's own exported requirements as a partial function over every runtime, for the published descriptor:
    * project usage combined with the runtime-visible dependencies' requirements. A consumer links a native binary
    * against the Runtime classpath and resolves this library's compile and runtime dependencies transitively, so a
    * `% Runtime` dependency reaches that link and its requirements must export; a test-only dependency never reaches a
    * consumer, so its requirements do not. This matches the consumer's own Runtime-scope link fold.
    */
  private def exportedRequirements(
    usage: PartialFunction[NativeRuntime, Usage],
    dependencies: Seq[NativeDependency]): PartialFunction[NativeRuntime, Usage] = { case runtime =>
    combineRequirements(usage, dependencies, Runtime.name, runtime)
  }

  /** Build the published descriptor for the NIR deliverable and write it as a managed resource, byte-stably and only
    * when its content changes, so a rebuild does not re-package. The descriptor carries the library's OWN exported
    * requirements - its project usage combined with its runtime-visible dependencies' requirements - never the
    * transitive classpath fold (a consumer reaches a dependency's requirements through that dependency's own
    * descriptor, so re-exporting them would double-count). A non-NIR deliverable or an empty requirement set writes
    * nothing.
    */
  private def descriptorResource: Def.Initialize[Task[Seq[File]]] = Def.uncachedTask {
    if SNX.deliverable.value != Deliverable.NIR then Seq.empty
    else
      val module = Module(organization.value, moduleName.value, version.value)
      val requirements = exportedRequirements(SNX.usage.value, SNX.dependencies.value)
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

  /** Classified publishing for a per-platform NIR library ([[SNXImports.SNX.classified]]). The native content (NIR,
    * bundled source, the `native.json` descriptor) publishes under the build's OS/arch classifier, while the
    * unclassified main coordinate carries a manifest-only placeholder jar - so building one target per published
    * classifier never overwrites another target's content, and the main artefact stays jar-typed (Maven derives `jar`
    * packaging from it, and a consumer resolves the real content through the OS/arch classifier). Disabled by default:
    * a single, platform-independent jar then publishes normally.
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
        // The classified key must match the `artifacts` entry exactly on (classifier, type, extension): both derive
        // from the same main artefact, else the classified entry is orphaned and silently not published.
        val classified = mainArtifact.withClassifier(Some(SNX.target.value.classifier))
        val content = (Compile / packageBin).value
        val placeholderRef: HashedVirtualFileRef =
          fileConverter.value.toVirtualFile(placeholder(new File(target.value, s"snx/${moduleName.value}.jar"), moduleName.value).toPath.nn)
        base.updated(mainArtifact, placeholderRef).updated(classified, content)
    }
  )

  // 1980-01-01T00:00:00Z, the earliest timestamp a zip entry can store; fixing it keeps the placeholder byte-identical
  // across rebuilds, so a republish does not change the main coordinate's content.
  final private val placeholderEpochMillis: Long = 315532800000L

  /** Write a manifest-only placeholder jar standing in for the unclassified main artefact when the native content
    * publishes under the OS/arch classifier instead.
    */
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
        case Some(false) => fail("a native dependency requires multithreading, which this project has disabled")
        case _           => config.withMultithreading(Some(true))

  /** Build each declared vendored library for `runtime` and fold its result onto `base`: the static archives as raw
    * link inputs, the header directories as `-I`, and the library's own per-platform [[Contribution]] merged onto the
    * channels. Applied after the propagated requirements and before the modifiers, so a modifier keeps the final say.
    */
  private def foldVendored(
    base: NativeConfig,
    libraries: Seq[Vendored],
    runtime: NativeRuntime,
    projectBase: File,
    rootBase: File,
    staging: File,
    cache: BuildWideCacheConfiguration,
    converter: FileConverter,
    log: Logger): NativeConfig =
    libraries.foldLeft(base) { (config, library) =>
      val artefacts = buildVendored(library, runtime, projectBase, rootBase, staging, cache, converter, log)
      val withArtefacts = config
        .withLinkingOptions(config.linkingOptions ++ artefacts.archives.map(_.getAbsolutePath.nn))
        .withCompileOptions(config.compileOptions ++ artefacts.includes.map(dir => s"-I${dir.getAbsolutePath}"))
      Contribution.merge(withArtefacts, library.contributionFor(runtime))
    }

  /** Build one declared vendored library for `runtime`. A `Local` origin resolves its directory (the project base,
    * then the build root) and keys the cache to a content hash of the sources.
    */
  private def buildVendored(
    library: Vendored,
    runtime: NativeRuntime,
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
          Vendored.contentDigest(location),
          () => location,
          staging,
          cache,
          converter,
          log)
      case Origin.Git(uri, ref) =>
        val clones = new File(staging, "clones")
        cachedBuild(
          slug(s"$uri-$ref"),
          library.backend,
          runtime,
          s"git:$uri@$ref",
          () => fetch(uri, ref, clones),
          staging,
          cache,
          converter,
          log)

  /** Build `backend` for `runtime`, cached per library in the local action cache. `locate` resolves the source
    * directory and runs on a cache miss only; `sourceId` keys the cache to the source identity, and
    * `backend.cacheKey` adds the build configuration. A compiled archive is not portable, so the cache is
    * [[CacheLevelTag.Local]] - the [[toolchainId]] in the key invalidates it across a toolchain upgrade.
    */
  private def cachedBuild(
    name: String,
    backend: Backend,
    runtime: NativeRuntime,
    sourceId: String,
    locate: () => File,
    staging: File,
    cache: BuildWideCacheConfiguration,
    converter: FileConverter,
    log: Logger): Artefacts =
    val sourceStaging = new File(staging, name)
    val outputDir = cache.outputDirectory
    val key = List(BuildInfo.version, runtime.toString, toolchainId, sourceId) ++ backend.cacheKey(runtime)
    val (archives, includes) = ActionCache.cache[Seq[String], (Seq[String], Seq[String])](
      key,
      Digest.zero,
      Digest.zero,
      List(CacheLevelTag.Local),
      cache
    ) { _ =>
      val location = locate()
      IO.delete(sourceStaging)
      val built = backend.build(BuildContext(location, sourceStaging, runtime, log))
      val outputs =
        built.archives.map(file => converter.toVirtualFile(file.toPath.nn)) ++
          built.includes.map(dir => ActionCache.packageDirectory(converter.toVirtualFile(dir.toPath.nn), converter, outputDir))
      def relative(files: Seq[File]): Seq[String] = files.map(file => outputDir.relativize(file.toPath).toString)
      ActionCache.InternalActionResult((relative(built.archives), relative(built.includes)), outputs)
    }
    def absolute(paths: Seq[String]): Seq[File] = paths.map(path => outputDir.resolve(path).nn.toFile.nn)
    Artefacts(absolute(archives), absolute(includes))
  end cachedBuild

  /** Resolve a `Local` vendored directory: relative to the project base if it exists there, else to the build root. */
  private def resolveDir(directory: String, projectBase: File, rootBase: File): File =
    val inProject = new File(projectBase, directory)
    if inProject.isDirectory then inProject else new File(rootBase, directory)

  /** Clone `uri` at `ref` (a tag, commit, or branch) into a cached subdirectory of `clones`, reusing an existing
    * clone - so the clone runs only on a cache miss, and a branch is cloned once and then frozen per machine.
    */
  private def fetch(uri: String, ref: String, clones: File): File =
    val keyed = new java.net.URI(s"$uri#$ref")
    val localCopy = Resolvers.uniqueSubdirectoryFor(keyed, clones)
    Resolvers.creates(localCopy) {
      Resolvers.run("git", "clone", uri, localCopy.getAbsolutePath)
      Resolvers.run(Some(localCopy), "git", "checkout", "-q", ref)
    }

  /** A filesystem-safe staging label for a vendored origin. */
  private def slug(value: String): String = value.replaceAll("[^A-Za-z0-9]+", "-").nn

  /** A best-effort local toolchain identity for the cache key: the `clang` and `cmake` versions, so an upgrade of
    * either rebuilds rather than reusing an archive compiled against a different one.
    */
  private def toolchainId: String =
    def version(tool: String): String =
      scala.util
        .Try(scala.sys.process.Process(Seq(tool, "--version")).!!.linesIterator.find(_.nonEmpty).getOrElse(""))
        .toOption
        .getOrElse("")
    s"clang=${version("clang")};cmake=${version("cmake")}"

  // Configurations whose dependencies are visible in each scope, along the Test -> Runtime -> Compile extension chain:
  // Compile sees compile-scoped; Runtime adds runtime-scoped; Test adds test-scoped.
  final private val compileScoped = Set("compile", "default")
  final private val runtimeScoped = compileScoped ++ Set("runtime")
  final private val testScoped = runtimeScoped ++ Set("test")

  /** Whether a dependency declared for `module`'s configurations is visible in `configuration`. Compile sees
    * compile-scoped dependencies; Runtime (the main link) adds runtime-scoped ones; Test (the test link) adds
    * test-scoped ones - so a compile dependency reaches every link, a runtime dependency the main and test links, and
    * a test-only dependency only the test link.
    */
  private[sbt] def visible(configuration: String, module: ModuleID): Boolean =
    val scope =
      if configuration == Test.name then testScoped
      else if configuration == Runtime.name then runtimeScoped
      else compileScoped
    declared(module).exists(scope.contains)

  /** The configurations a `ModuleID` is declared for: the comma-separated entries of its configuration string, each
    * taken before any `->` mapping and lower-cased; an unscoped dependency is `compile`.
    */
  private[sbt] def declared(module: ModuleID): Set[String] =
    module.configurations match
      case None        => Set("compile")
      case Some(value) =>
        value.toLowerCase.nn
          .split(",")
          .nn
          .iterator
          .map { entry =>
            val text = entry.nn
            val arrow = text.indexOf("->")
            (if arrow < 0 then text else text.substring(0, arrow).nn).trim.nn
          }
          .filter(_.nonEmpty)
          .toSet

  /** The toolchain base: the resolved clang/clang++, the discovered compile/link options (with host search paths
    * cross-stripped, [[crossStrip]]), and the user's include/lib directories. Scalars are not read from the
    * `SCALANATIVE_*` environment - they come from the typed settings, folded over this base in [[configSettings]].
    */
  private def discovered(clang: Path, clangPP: Path, includeDirs: Seq[File], libDirs: Seq[File], cross: Boolean): NativeConfig =
    val compileOptions = crossStrip(Discover.compileOptions(), "-I", cross) ++ includeDirs.map(dir => "-I" + dir.getAbsolutePath)
    val linkingOptions = crossStrip(Discover.linkingOptions(), "-L", cross) ++ libDirs.map(dir => "-L" + dir.getAbsolutePath)
    NativeConfig.empty
      .withClang(clang)
      .withClangPP(clangPP)
      .withCompileOptions(compileOptions)
      .withLinkingOptions(linkingOptions)

  /** Drop options beginning with `prefix` (a host-discovered `-I` or `-L` search path) when cross-targeting, so a
    * build for a non-host target is not contaminated by the host toolchain's directories.
    */
  private[sbt] def crossStrip(options: Seq[String], prefix: String, cross: Boolean): Seq[String] =
    if cross then options.filterNot(_.startsWith(prefix)) else options

  /** The C compiler: the `SNX.clang` override, or the toolchain's discovered `clang`. */
  private def resolveClang(setting: Option[File]): Path = setting.map(_.toPath.nn).getOrElse(Discover.clang())

  /** The C++ compiler: the `SNX.clangPP` override, or the toolchain's discovered `clang++`. */
  private def resolveClangPP(setting: Option[File]): Path = setting.map(_.toPath.nn).getOrElse(Discover.clangpp())

end SNXPlugin
