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

import sbt.SettingKey
import sbt.TaskKey
import sbt.librarymanagement.ModuleID

/** Types, settings, and syntax auto-imported into `build.sbt` by [[SNXPlugin$ SNXPlugin]]. The settings and tasks live
  * under [[SNXImports.SNX$ SNX]] so they never clash with sbt or Scala Native keys.
  */
object SNXImports:

  type TargetPlatform = snx.TargetPlatform
  val TargetPlatform: snx.TargetPlatform.type = snx.TargetPlatform

  type OS = snx.OS
  val OS: snx.OS.type = snx.OS

  type Arch = snx.Arch
  val Arch: snx.Arch.type = snx.Arch

  type NativePlatform = snx.NativePlatform
  val NativePlatform: snx.NativePlatform.type = snx.NativePlatform

  type LinuxLibc = snx.LinuxLibc
  val LinuxLibc: snx.LinuxLibc.type = snx.LinuxLibc

  type WindowsABI = snx.WindowsABI
  val WindowsABI: snx.WindowsABI.type = snx.WindowsABI

  type NativeDependency = snx.sbt.NativeDependency
  val NativeDependency: snx.sbt.NativeDependency.type = snx.sbt.NativeDependency

  type NativeConfig = scala.scalanative.build.NativeConfig
  val NativeConfig: scala.scalanative.build.NativeConfig.type = scala.scalanative.build.NativeConfig

  type LTO = scala.scalanative.build.LTO
  val LTO: scala.scalanative.build.LTO.type = scala.scalanative.build.LTO

  type Mode = scala.scalanative.build.Mode
  val Mode: scala.scalanative.build.Mode.type = scala.scalanative.build.Mode

  type GC = scala.scalanative.build.GC
  val GC: scala.scalanative.build.GC.type = scala.scalanative.build.GC

  type BuildTarget = scala.scalanative.build.BuildTarget
  val BuildTarget: scala.scalanative.build.BuildTarget.type = scala.scalanative.build.BuildTarget

  type Sanitizer = scala.scalanative.build.Sanitizer
  val Sanitizer: scala.scalanative.build.Sanitizer.type = scala.scalanative.build.Sanitizer

  /** A per-platform `nativeConfig` transform; unmatched platforms contribute none. Element type of [[SNX.config]]; see
    * [[nativeTransform]] to type a literal in a `+=`/`++=`.
    */
  type NativeTransform = PartialFunction[NativePlatform, NativeConfig => NativeConfig]

  /** Settings, tasks, and the build host of the sbt-native-extras plugin, namespaced to avoid clashing with sbt or
    * Scala Native keys (for example sbt's own `target`).
    */
  object SNX:

    /** The build host's [[snx.TargetPlatform TargetPlatform]], from the `os.name` and `os.arch` system properties. */
    lazy val host: TargetPlatform =
      TargetPlatform.parse(sys.props.getOrElse("os.name", "").nn, sys.props.getOrElse("os.arch", "").nn)

    /** The OS/arch target to classify and link for. Defaults to [[host]]. */
    val target: SettingKey[TargetPlatform] =
      SettingKey[TargetPlatform]("snxTarget", "OS/arch target for classifier injection and per-platform linking (default: host).")

    /** The platform resolved for [[target]] plus the toolchain libc/ABI, read from the Scala Native target triple (or
      * the discovered clang). The match key per-platform settings condition on.
      */
    val platform: TaskKey[NativePlatform] =
      TaskKey[NativePlatform]("snxPlatform", "Resolved native platform (target OS/arch plus toolchain libc/ABI).")

    /** Per-platform native dependencies contributed for [[target]]. */
    val dependencies: SettingKey[Seq[NativeDependency]] =
      SettingKey[Seq[NativeDependency]](
        "snxDependencies",
        "Per-platform native dependencies (OS/arch classifier + per-platform native options).")

    /** Project-level per-platform `nativeConfig` transforms applied for the resolved [[platform]]. */
    val config: SettingKey[Seq[NativeTransform]] =
      SettingKey[Seq[NativeTransform]]("snxConfig", "Per-platform nativeConfig transforms applied for the resolved platform.")

    /** Publish this project's artifact with the [[target]] OS/arch classifier carrying the built native content, with a
      * placeholder main artifact. Defaults to `false`.
      */
    val classified: SettingKey[Boolean] =
      SettingKey[Boolean](
        "snxClassified",
        "Publish the built native content under the OS/arch classifier, with a placeholder main artifact (default: false).")

  end SNX

  /** Lifts a `ModuleID` into a [[NativeDependency]]. */
  given Conversion[ModuleID, NativeDependency] = NativeDependency(_)

  extension (module: ModuleID)

    /** Lift `module` into a classified [[NativeDependency]]. */
    def native: NativeDependency = NativeDependency(module)

    /** Lift `module` and attach per-platform linker flags. */
    infix def linking(flags: PartialFunction[NativePlatform, Seq[String]]): NativeDependency =
      NativeDependency(module).linking(flags)

    /** Lift `module` and attach the full per-platform additive options. */
    infix def options(bundle: PartialFunction[NativePlatform, NativeDependency.Options]): NativeDependency =
      NativeDependency(module).options(bundle)

    /** Lift `module` as an unclassified (plain NIR) [[NativeDependency]]. */
    def plain: NativeDependency = NativeDependency(module).plain

  /** Type a [[NativeTransform]] literal so it infers in a `SNX.config +=`/`++=` (sbt's `Append` does not propagate the
    * element type to a partial-function literal). A `SNX.config :=` propagates it and needs no wrapper.
    */
  def nativeTransform(transform: NativeTransform): NativeTransform = transform

end SNXImports
