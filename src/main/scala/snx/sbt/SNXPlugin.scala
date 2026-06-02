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
import sbt.Keys.configuration
import sbt.Keys.crossPaths
import sbt.Keys.fileConverter
import sbt.Keys.libraryDependencies
import sbt.Keys.libraryDependencySchemes
import sbt.Keys.moduleName
import sbt.Keys.packageBin
import sbt.Keys.packagedArtifacts
import sbt.Keys.sLog
import sbt.Keys.scalaBinaryVersion
import sbt.Keys.scalaVersion
import sbt.Keys.sourceDirectory
import sbt.Keys.target
import sbt.Keys.unmanagedResourceDirectories
import sbt.Keys.unmanagedSourceDirectories
import sbt.Keys.virtualAxes
import sbt.io.IO
import xsbti.HashedVirtualFileRef

import java.util.jar.Manifest

import scala.scalanative.sbtplugin.ScalaNativeCrossVersion
import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport.nativeConfig
import scala.sys.process.Process

import snx.sbt.SNXImports.*

/** sbt plugin contributing OS/arch classifier injection and per-platform linking to a Scala Native project. See
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
      nativeConfig := Def.uncached {
        val previous = nativeConfig.value
        val resolved = SNX.platform.value
        SNX.config.value.foldLeft(previous)((cfg, transform) => transform.lift(resolved).fold(cfg)(_(cfg)))
      }
    ) ++ inConfig(Compile)(dependencySettings) ++ inConfig(Test)(dependencySettings) ++
      inConfig(Compile)(pathSettings) ++ inConfig(Test)(pathSettings)

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
        .foldLeft(previous) { (cfg, options) =>
          cfg
            .withLinkingOptions(cfg.linkingOptions ++ options.linking)
            .withCompileOptions(cfg.compileOptions ++ options.compile)
            .withCOptions(cfg.cOptions ++ options.c)
            .withCppOptions(cfg.cppOptions ++ options.cpp)
        }
    }
  )

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

end SNXPlugin
