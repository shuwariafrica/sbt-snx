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
import sbt.Keys.configuration
import sbt.Keys.crossPaths
import sbt.Keys.fileConverter
import sbt.Keys.fullClasspath
import sbt.Keys.libraryDependencies
import sbt.Keys.libraryDependencySchemes
import sbt.Keys.moduleName
import sbt.Keys.name
import sbt.Keys.organization
import sbt.Keys.packageBin
import sbt.Keys.packagedArtifacts
import sbt.Keys.resourceGenerators
import sbt.Keys.resourceManaged
import sbt.Keys.sLog
import sbt.Keys.scalaBinaryVersion
import sbt.Keys.scalaVersion
import sbt.Keys.sourceDirectory
import sbt.Keys.streams
import sbt.Keys.target
import sbt.Keys.unmanagedResourceDirectories
import sbt.Keys.unmanagedSourceDirectories
import sbt.Keys.version
import sbt.Keys.virtualAxes
import sbt.io.IO
import sbt.util.ActionCache
import sbt.util.BuildWideCacheConfiguration
import sbt.util.CacheImplicits.given
import sbt.util.CacheLevelTag
import sbt.util.Digest
import xsbti.FileConverter
import xsbti.HashedVirtualFileRef

import java.net.URI
import java.util.jar.Manifest

import scala.scalanative.sbtplugin.ScalaNativeCrossVersion
import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport.nativeConfig
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport.nativeLink
import scala.sys.process.Process

import snx.sbt.SNXImports.*

/** sbt plugin expressing a Scala Native project's per-platform native concerns as build settings: OS/arch dependency
  * classification, per-platform linker and compiler options, native libraries built from source, per-platform source
  * and resource directories, classified publishing, and third-party native licence compliance. See
  * [[SNXImports$ SNXImports]] for the settings and syntax it adds to `build.sbt`.
  */
object SNXPlugin extends AutoPlugin:

  override def requires: Plugins = ScalaNativePlugin
  override def trigger: PluginTrigger = noTrigger

  val autoImport: SNXImports.type = SNXImports

  override def buildSettings: Seq[Setting[?]] = Seq(
    SNX.target := SNX.host
  )

  override def projectSettings: Seq[Setting[?]] =
    Seq(
      SNX.targets := Seq(SNX.target.value),
      SNX.dependencies := Seq.empty,
      SNX.config := Seq.empty,
      SNX.vendored := Seq.empty,
      SNX.Native / crossPaths := false,
      SNX.platform := Def.uncached {
        val base = (ThisBuild / nativeConfig).value
        val triple = base.targetTriple.getOrElse(clangTriple(base.clang))
        NativePlatform.parse(SNX.target.value, triple)
      },
      libraryDependencies ++= {
        targetNote.value
        val resolved = SNX.target.value
        SNX.dependencies.value.map(_.moduleID(resolved))
      },
      libraryDependencySchemes += {
        val name = CrossVersion(ScalaNativeCrossVersion.binary, scalaVersion.value, scalaBinaryVersion.value)
          .fold("test-interface")(_("test-interface"))
        "org.scala-native" % name % "always"
      },
      artifacts := {
        val base = artifacts.value
        if (SNX.Native / crossPaths).value then
          base :+ (Compile / packageBin / artifact).value.withClassifier(Some(SNX.target.value.classifier))
        else base
      },
      packagedArtifacts := Def.uncached {
        val base = packagedArtifacts.value
        if !(SNX.Native / crossPaths).value then base
        else
          val converter = fileConverter.value
          val mainArtifact = (Compile / packageBin / artifact).value
          val content = (Compile / packageBin).value
          val placeholderRef: HashedVirtualFileRef =
            converter.toVirtualFile(placeholder(new File(target.value, s"snx/${moduleName.value}.jar"), moduleName.value).toPath.nn)
          base
            .updated(mainArtifact, placeholderRef)
            .updated(mainArtifact.withClassifier(Some(SNX.target.value.classifier)), content)
      },
      SNX.vendoredArtefacts := Def.uncached {
        val platform = SNX.platform.value
        val projectBase = baseDirectory.value
        val rootBase = (LocalRootProject / baseDirectory).value
        val staging = new File(target.value, "snx/vendored")
        val cache = Def.cacheConfiguration.value
        val converter = fileConverter.value
        val log = streams.value.log
        SNX.vendored.value.map(source => buildSource(source, platform, projectBase, rootBase, staging, cache, converter, log))
      },
      nativeConfig := Def.uncached {
        val previous = nativeConfig.value
        val resolved = SNX.platform.value
        val transformed = SNX.config.value.foldLeft(previous)((cfg, transform) => transform.lift(resolved).fold(cfg)(_(cfg)))
        SNX.vendored.value.zip(SNX.vendoredArtefacts.value).foldLeft(transformed) { case (cfg, (source, artefacts)) =>
          val withArtefacts = cfg
            .withLinkingOptions(cfg.linkingOptions ++ artefacts.archives.map(_.getAbsolutePath))
            .withCompileOptions(cfg.compileOptions ++ artefacts.includes.map(dir => s"-I${dir.getAbsolutePath}"))
          applyOptions(withArtefacts, source.optionsFor(resolved))
        }
      },
      Compile / resourceGenerators += Def.task {
        val deps = SNX.dependencies.value.filter(dep => visible(Compile.name, dep.module))
        val sources = SNX.vendored.value
        val projectBase = baseDirectory.value
        val rootBase = (LocalRootProject / baseDirectory).value
        val staging = new File(target.value, "snx/vendored")
        val outputDir = new File((Compile / resourceManaged).value, "META-INF/native-licenses")
        val specs = deps.map(dependencySpec(_, projectBase)) ++ sources.map(sourceSpec(_, projectBase, rootBase, staging))
        LicenseGenerator.generate(
          coordinate(organization.value, name.value, moduleName.value, version.value, scalaVersion.value, scalaBinaryVersion.value),
          specs,
          outputDir,
          streams.value.log
        )
      }.taskValue,
      SNX.licenseReport := Def.uncached((Compile / SNX.licenseReport).value),
      // Producing a linked deliverable also produces its third-party native-licence aggregate, but only when the
      // classpath declares any - so a binary with no native licences (and a NIR library, which never links here) is
      // untouched. The explicit SNX.licenseReport remains for on-demand use.
      Compile / nativeLink := Def.uncached {
        val result = (Compile / nativeLink).value
        val converter = fileConverter.value
        val classpath = (Compile / fullClasspath).value.map(entry => converter.toPath(entry.data).nn.toFile.nn)
        if LicenseAggregator.hasMarkers(classpath) then
          val binary =
            coordinate(organization.value, name.value, moduleName.value, version.value, scalaVersion.value, scalaBinaryVersion.value)
          val _ = LicenseAggregator.aggregate(classpath, binary, new File(target.value, "snx/licenses/compile"), streams.value.log)
        result
      }
    ) ++ inConfig(Compile)(dependencySettings) ++ inConfig(Test)(dependencySettings) ++
      inConfig(Compile)(pathSettings) ++ inConfig(Test)(pathSettings) ++
      inConfig(Compile)(reportSettings) ++ inConfig(Test)(reportSettings)

  /** Per-configuration dependency options. A dependency contributes only where its configuration is visible to the
    * enclosing one (`Compile`-visible in Compile; `Runtime`/`Test`-only in Test, since Compile-visible options arrive
    * via sbt's configuration delegation).
    */
  private def dependencySettings: Seq[Setting[?]] = Seq(
    nativeConfig := Def.uncached {
      val previous = nativeConfig.value
      val resolved = SNX.platform.value
      val config = configuration.value.name
      SNX.dependencies.value
        .filter(dependency => visible(config, dependency.module))
        .map(_.optionsFor(resolved))
        .foldLeft(previous)(applyOptions)
    }
  )

  /** Aggregate the third-party native licences declared across the enclosing configuration's resolved classpath - the
    * binary's dependency jars and the project's own products - into an SPDX document beside the build output.
    */
  private def reportSettings: Seq[Setting[?]] = Seq(
    SNX.licenseReport := Def.uncached {
      val converter = fileConverter.value
      val classpath = fullClasspath.value.map(entry => converter.toPath(entry.data).nn.toFile.nn)
      val binary =
        coordinate(organization.value, name.value, moduleName.value, version.value, scalaVersion.value, scalaBinaryVersion.value)
      val outputDir = new File(target.value, s"snx/licenses/${configuration.value.name}")
      LicenseAggregator.aggregate(classpath, binary, outputDir, streams.value.log)
    }
  )

  /** The publishing artefact's SPDX root facts: a platform-independent maven Package URL identity, a deterministic
    * document namespace, and the display name. The identity is a deliberate dedup KEY, not a resolvable locator: a
    * licence is invariant across the binary axes, so the coordinate strips the Scala Native cross suffix (as it omits
    * the OS/arch classifier) - it identifies the logical library, not a specific binary artifact. Stripping the suffix
    * that publishing (or [[SNXImports.SNX.platformPublishSettings]]) may have baked into `module` makes the artefact
    * carry the same base coordinate a consumer derives from the dependency's `ModuleID.name`, so the same library
    * deduplicates whether read from a published document or declared by the consumer.
    */
  private def coordinate(
    organization: String,
    name: String,
    module: String,
    version: String,
    scalaVersion: String,
    scalaBinaryVersion: String): ArtifactInfo =
    val base = module.stripSuffix(nativeSuffix(scalaVersion, scalaBinaryVersion))
    ArtifactInfo(name, s"pkg:maven/$organization/$base@$version", Some(version), s"https://spdx.org/spdxdocs/$name-$version")

  /** The Scala Native cross suffix for this Scala version (for example `_native0.5_3`), derived rather than hardcoded,
    * so the base artefact identity can be recovered from a suffixed module name.
    */
  private def nativeSuffix(scalaVersion: String, scalaBinaryVersion: String): String =
    CrossVersion(ScalaNativeCrossVersion.binary, scalaVersion, scalaBinaryVersion).fold("")(cross => cross("x").stripPrefix("x"))

  /** Fold one [[NativeOptions]] bundle into `cfg`: each channel appends to its matching `nativeConfig` option list. */
  private def applyOptions(cfg: NativeConfig, options: NativeOptions): NativeConfig =
    cfg
      .withLinkingOptions(cfg.linkingOptions ++ options.linking)
      .withCompileOptions(cfg.compileOptions ++ options.compile)
      .withCOptions(cfg.cOptions ++ options.c)
      .withCppOptions(cfg.cppOptions ++ options.cpp)

  private def pathSettings: Seq[Setting[?]] = Seq(
    unmanagedSourceDirectories ++= {
      val matrix = virtualAxes.?.value.exists(_.exists(_.directorySuffix == "native"))
      sourceDirs(unmanagedSourceDirectories.value, matrix, SNX.target.value, (SNX.Native / crossPaths).value)
    },
    unmanagedResourceDirectories ++= {
      val matrix = virtualAxes.?.value.exists(_.exists(_.directorySuffix == "native"))
      resourceDirs(sourceDirectory.value, unmanagedResourceDirectories.value, matrix, SNX.target.value, (SNX.Native / crossPaths).value)
    }
  )

  private def sourceDirs(base: Seq[File], matrix: Boolean, target: TargetPlatform, enabled: Boolean): Seq[File] =
    if !enabled then Nil
    else (if matrix then base.flatMap(nativeSibling) else base).flatMap(suffixes(_, target))

  private def resourceDirs(sourceDir: File, base: Seq[File], matrix: Boolean, target: TargetPlatform, enabled: Boolean): Seq[File] =
    if matrix then
      val common = new File(sourceDir, "resources-scalanative")
      common +: (if enabled then suffixes(common, target) else Nil)
    else if enabled then base.flatMap(suffixes(_, target))
    else Nil

  /** The native sibling of a shared `scala`/`java` source directory (for example `scala-3` -> `scalanative-3`); other
    * directories have none.
    */
  private def nativeSibling(dir: File): Option[File] =
    val name = dir.getName.nn
    if name.startsWith("scala") then Some(new File(dir.getParentFile.nn, s"scalanative${name.stripPrefix("scala")}"))
    else if name.startsWith("java") then Some(new File(dir.getParentFile.nn, s"javanative${name.stripPrefix("java")}"))
    else None

  private def suffixes(dir: File, target: TargetPlatform): Seq[File] =
    Seq(target.os.token, target.classifier).map(suffix => new File(s"${dir.getPath}-$suffix"))

  private def targetNote: Def.Initialize[Unit] = Def.setting {
    val target = SNX.target.value
    val targets = SNX.targets.value
    if !targets.contains(target) then
      sLog.value.info(
        s"SNX.target ${target.classifier} is not among the declared SNX.targets (${targets.map(_.classifier).mkString(", ")}).")
  }

  private def clangTriple(clang: java.nio.file.Path): String =
    val output = Process(Seq(clang.toString, "--version")).!!
    output.linesIterator.find(_.startsWith("Target: ")).map(_.drop("Target: ".length)).getOrElse("")

  // 1980-01-01T00:00:00Z, the earliest timestamp a zip entry can store; fixing it keeps the placeholder reproducible.
  private val placeholderEpochMillis: Long = 315532800000L

  /** Write a manifest-only placeholder jar standing in for the unclassified main artifact when the built native content
    * is published under the OS/arch classifier instead.
    */
  private def placeholder(out: File, name: String): File =
    val manifest = new Manifest()
    val attributes = manifest.getMainAttributes.nn
    attributes.putValue("Manifest-Version", "1.0")
    attributes.putValue("Snx-Placeholder", s"$name: platform artefacts are published under OS/arch classifiers.")
    IO.jar(Seq.empty[(File, String)], out, manifest, Some(placeholderEpochMillis))
    out

  private val compileScoped = Set("compile", "provided", "optional", "system", "default")
  private val testExclusive = Set("runtime", "test")

  /** Whether `module`'s options apply in `config`: a compile-visible dependency contributes to Compile (and reaches
    * Test via sbt's configuration delegation); a test/runtime-exclusive one contributes only to Test. A dependency
    * declared in both is applied once, in Compile.
    */
  private def visible(config: String, module: ModuleID): Boolean =
    val configs = declared(module)
    val compileVisible = configs.exists(compileScoped.contains)
    if config == Test.name then configs.exists(testExclusive.contains) && !compileVisible
    else compileVisible

  /** The configurations a `ModuleID` is declared for (comma-separated, each taken before any `->` mapping); an
    * unscoped dependency is `compile`.
    */
  private def declared(module: ModuleID): Set[String] =
    module.configurations match
      case None        => Set("compile")
      case Some(value) =>
        value.toLowerCase.nn
          .split(",")
          .nn
          .iterator
          .map { entry =>
            val c = entry.nn
            val arrow = c.indexOf("->")
            (if arrow < 0 then c else c.substring(0, arrow).nn).trim.nn
          }
          .filter(_.nonEmpty)
          .toSet

  /** Build one declared [[NativeSource]] for the resolved platform: `System` builds nothing (link-only), `Local`
    * builds its directory (default `vendor/<name>`), and `Git` clones `ref` then builds it. The build is
    * cached per library (local scope only - a compiled archive is not safe to reuse across toolchains): on a cache
    * hit the source is neither fetched nor built and the archives and include directories are restored from the cache.
    */
  private def buildSource(
    source: NativeSource,
    platform: NativePlatform,
    projectBase: File,
    rootBase: File,
    staging: File,
    cache: BuildWideCacheConfiguration,
    converter: FileConverter,
    log: Logger): NativeArtefacts =
    source match
      case NativeSource.System(_, _, _)                       => NativeArtefacts.empty
      case NativeSource.Local(name, directory, backend, _, _) =>
        val location = directory.getOrElse(vendorDir(name, projectBase, rootBase))
        cachedBuild(name, backend, platform, sourceIdentity(location), () => location, staging, cache, converter, log)
      case NativeSource.Git(name, uri, ref, backend, _, _) =>
        val clones = new File(staging, "clones")
        cachedBuild(name, backend, platform, s"git:$uri@$ref", () => fetch(uri, ref, clones), staging, cache, converter, log)

  /** Build `name` with `backend` for the resolved platform, cached per library in the local action cache. `locate`
    * resolves - and, for a `Git` source, fetches - the source directory; it runs on a cache miss only. `sourceId`
    * keys the cache to the source (a `Local` content hash, a `Git` pinned `uri@ref`); `backend.cacheKey` adds the
    * build configuration.
    */
  private def cachedBuild(
    name: String,
    backend: NativeBackend,
    platform: NativePlatform,
    sourceId: String,
    locate: () => File,
    staging: File,
    cache: BuildWideCacheConfiguration,
    converter: FileConverter,
    log: Logger): NativeArtefacts =
    val sourceStaging = new File(staging, name)
    val outputDir = cache.outputDirectory
    val key = List(BuildInfo.version, platform.toString, toolchainId, sourceId) ++ backend.cacheKey(platform)
    val (archives, includes) = ActionCache.cache[Seq[String], (Seq[String], Seq[String])](
      key,
      Digest.zero,
      Digest.zero,
      List(CacheLevelTag.Local),
      cache
    ) { _ =>
      val location = locate()
      IO.delete(sourceStaging)
      val built = backend.build(BuildContext(location, sourceStaging, platform, log))
      val outputs =
        built.archives.map(file => converter.toVirtualFile(file.toPath.nn)) ++
          built.includes.map(dir => ActionCache.packageDirectory(converter.toVirtualFile(dir.toPath.nn), converter, outputDir))
      def relative(files: Seq[File]): Seq[String] = files.map(file => outputDir.relativize(file.toPath).toString)
      ActionCache.InternalActionResult((relative(built.archives), relative(built.includes)), outputs)
    }
    def absolute(paths: Seq[String]): Seq[File] = paths.map(path => outputDir.resolve(path).nn.toFile.nn)
    NativeArtefacts(absolute(archives), absolute(includes))
  end cachedBuild

  /** Clone `uri` at `ref` (a tag, commit, or branch) into a cached subdirectory of `clones`, reusing an existing
    * clone. Keyed by both `uri` and `ref`, so different refs of the same repository do not collide. The clone is
    * cached and never re-fetched, so a branch resolves once and is then frozen per machine; pin a tag or commit for a
    * reproducible or updatable build.
    */
  private def fetch(uri: String, ref: String, clones: File): File =
    val keyed = new java.net.URI(s"$uri#$ref")
    val localCopy = Resolvers.uniqueSubdirectoryFor(keyed, clones)
    Resolvers.creates(localCopy) {
      Resolvers.run("git", "clone", uri, localCopy.getAbsolutePath)
      Resolvers.run(Some(localCopy), "git", "checkout", "-q", ref)
    }

  /** The compliance spec for a managed dependency: its native content is compiled into the binary, so an unresolved
    * relationship defaults to a static link; identity defaults to a maven Package URL from the coordinate (so the
    * library deduplicates across platforms when binaries are aggregated); bundled files resolve against the project.
    */
  private def dependencySpec(dependency: NativeDependency, projectBase: File): LibrarySpec =
    val module = dependency.module
    val compliance = dependency.compliance
    val identity = compliance.identity.orElse(Some(s"pkg:maven/${module.organization}/${module.name}@${module.revision}"))
    LibrarySpec(
      module.name,
      identity,
      Some(module.revision),
      resolveRelationship(compliance.relationship, static = true),
      compliance.license,
      compliance.texts,
      compliance.notices,
      compliance.source,
      compliance.writtenOffer,
      compliance.copyright,
      compliance.originator,
      compliance.contains,
      projectBase
    )

  /** The compliance spec for a source: a built library links statically (its archive is baked in), a `System` library
    * links dynamically; a `Git` source's clone URI supplies a default source location and its licence files resolve
    * against the clone; a `Local` source's against its directory; otherwise against the project.
    */
  private def sourceSpec(source: NativeSource, projectBase: File, rootBase: File, staging: File): LibrarySpec =
    val compliance = source.compliance
    val (version, root, static, origin) = source match
      case NativeSource.Git(_, uri, ref, _, _, _) => (Some(ref), cloneDir(uri, ref, staging), true, Some(new URI(uri)))
      case NativeSource.Local(name, dir, _, _, _) => (None, dir.getOrElse(vendorDir(name, projectBase, rootBase)), true, None)
      case NativeSource.System(_, _, _)           => (None, projectBase, false, None)
    LibrarySpec(
      source.name,
      compliance.identity,
      version,
      resolveRelationship(compliance.relationship, static),
      compliance.license,
      compliance.texts,
      compliance.notices,
      compliance.source.orElse(origin),
      compliance.writtenOffer,
      compliance.copyright,
      compliance.originator,
      compliance.contains,
      root
    )

  /** The cached clone directory for a `Git` source - the same path the build uses - computed without fetching, so
    * licence generation stays network-free.
    */
  private def cloneDir(uri: String, ref: String, staging: File): File =
    Resolvers.uniqueSubdirectoryFor(new URI(s"$uri#$ref"), new File(staging, "clones"))

  /** Resolve [[Relationship.Auto]] from how the library links (kept independent of the native toolchain): a static
    * archive or compiled-in content links statically, a link-only library dynamically. An explicit relationship wins.
    */
  private def resolveRelationship(relationship: Relationship, static: Boolean): Relationship =
    if relationship == Relationship.Auto then if static then Relationship.StaticLink else Relationship.DynamicLink
    else relationship

  /** Resolve a `Local` source's default directory: `vendor/<name>` in the project, else in the build root. */
  private def vendorDir(name: String, projectBase: File, rootBase: File): File =
    val inProject = new File(projectBase, s"vendor/$name")
    if inProject.isDirectory then inProject else new File(rootBase, s"vendor/$name")

  /** A stable content identity for a `Local` source directory: each file's path (relative to the directory) and
    * content hash, sorted - so the cache key tracks edits to the sources but not file timestamps or ordering.
    */
  private def sourceIdentity(directory: File): String =
    val root = directory.toPath.nn
    directory.allPaths
      .get()
      .filter(_.isFile)
      .map(file => s"${root.relativize(file.toPath)}:${Digest.sha256Hash(file.toPath.nn).hashHexString}")
      .sorted
      .mkString("\n")

  /** The build toolchain identity (clang and cmake versions) folded into the cache key, so an upgraded toolchain
    * rebuilds rather than reusing an archive compiled against a different one.
    */
  private def toolchainId: String =
    def version(tool: String): String =
      scala.util
        .Try(Process(Seq(tool, "--version")).!!.linesIterator.find(_.nonEmpty).getOrElse(""))
        .toOption
        .getOrElse("")
    s"clang=${version("clang")};cmake=${version("cmake")}"

end SNXPlugin
