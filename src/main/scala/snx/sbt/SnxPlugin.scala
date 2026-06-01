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
import sbt.Keys.artifacts
import sbt.Keys.configuration
import sbt.Keys.libraryDependencies
import sbt.Keys.moduleName
import sbt.Keys.packageBin
import sbt.Keys.packagedArtifacts
import sbt.librarymanagement.Artifact

import scala.scalanative.build.NativeConfig
import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport.nativeConfig
import scala.sys.process.Process

import snx.NativePlatform
import snx.TargetPlatform
import snx.sbt.SnxImports.*

/** sbt plugin contributing OS/arch classifier injection and per-platform linking to a Scala Native project. See
  * [[SnxImports$ SnxImports]] for the settings and syntax it adds to `build.sbt`.
  */
object SnxPlugin extends AutoPlugin:

  override def requires: Plugins = ScalaNativePlugin
  override def trigger: PluginTrigger = noTrigger

  val autoImport: SnxImports.type = SnxImports

  override def buildSettings: Seq[Setting[?]] = Seq(
    snxTarget := host
  )

  override def projectSettings: Seq[Setting[?]] =
    Seq(
      platformDependencies := Seq.empty,
      snxNative := Seq.empty,
      snxClassified := false,
      libraryDependencies ++= {
        val target = snxTarget.value
        platformDependencies.value.map(_.moduleID(target))
      },
      artifacts := {
        val base = artifacts.value
        if snxClassified.value then base :+ Artifact(moduleName.value, "jar", "jar", snxTarget.value.classifier)
        else base
      },
      packagedArtifacts := Def.uncached {
        val base = packagedArtifacts.value
        if snxClassified.value then
          base.updated(Artifact(moduleName.value, "jar", "jar", snxTarget.value.classifier), (Compile / packageBin).value)
        else base
      },
      nativeConfig := Def.uncached {
        val previous = nativeConfig.value
        val platform = resolved(previous, snxTarget.value)
        snxNative.value.foldLeft(previous)((cfg, transform) => transform.lift(platform).fold(cfg)(_(cfg)))
      }
    ) ++ inConfig(Compile)(dependencySettings) ++ inConfig(Test)(dependencySettings)

  /** Per-configuration dependency options. A dependency contributes only where its configuration is visible to the
    * enclosing one (`Compile`-visible in Compile; `Runtime`/`Test`-only in Test, since Compile-visible options arrive
    * via sbt's configuration delegation).
    */
  private def dependencySettings: Seq[Setting[?]] = Seq(
    nativeConfig := Def.uncached {
      val previous = nativeConfig.value
      val platform = resolved(previous, snxTarget.value)
      val config = configuration.value.name
      platformDependencies.value
        .filter(dependency => visible(config, dependency.module))
        .map(_.optionsFor(platform))
        .foldLeft(previous) { (cfg, options) =>
          cfg
            .withLinkingOptions(cfg.linkingOptions ++ options.linking)
            .withCompileOptions(cfg.compileOptions ++ options.compile)
            .withCOptions(cfg.cOptions ++ options.c)
            .withCppOptions(cfg.cppOptions ++ options.cpp)
        }
    }
  )

  /** The resolved [[snx.NativePlatform NativePlatform]]: the target's os/arch plus the toolchain libc/ABI, taken from
    * the configured target triple, else the discovered clang.
    */
  private def resolved(config: NativeConfig, target: TargetPlatform): NativePlatform =
    val triple = config.targetTriple.getOrElse(clangTriple(config.clang))
    NativePlatform.parse(target, environment(triple))

  private def clangTriple(clang: java.nio.file.Path): String =
    val output = Process(Seq(clang.toString, "--version")).!!
    output.linesIterator.find(_.startsWith("Target: ")).map(_.drop("Target: ".length)).getOrElse("")

  /** The environment (libc/ABI) component of a target triple, mirroring Scala Native's parse (index 3, else 2 for a
    * three-component `arch-os-env` triple).
    */
  private def environment(triple: String): String =
    val parts = triple.split("-", 4).nn
    val index = if parts.length > 3 then 3 else 2
    if parts.length > index then parts(index).nn else ""

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

end SnxPlugin
