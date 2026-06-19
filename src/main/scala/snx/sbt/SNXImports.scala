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

import sbt.Def
import sbt.Project
import sbt.ProjectMatrix
import sbt.SettingKey
import sbt.TaskKey
import sbt.VirtualAxis
import sbt.librarymanagement.ModuleID

import java.io.File

/** Types, settings, and tasks auto-imported into `build.sbt` by [[SNXPlugin$ SNXPlugin]]. The settings and tasks live
  * under [[SNXImports.SNX$ SNX]] so they never clash with sbt or Scala Native keys.
  */
object SNXImports:

  type TargetPlatform = snx.TargetPlatform
  val TargetPlatform: snx.TargetPlatform.type = snx.TargetPlatform

  type OS = snx.OS
  val OS: snx.OS.type = snx.OS

  type Arch = snx.Arch
  val Arch: snx.Arch.type = snx.Arch

  type NativeRuntime = snx.NativeRuntime
  val NativeRuntime: snx.NativeRuntime.type = snx.NativeRuntime

  type ABI[A <: snx.OS] = snx.ABI[A]
  val ABI: snx.ABI.type = snx.ABI

  type Native = snx.sbt.Native
  val Native: snx.sbt.Native.type = snx.sbt.Native

  type Contribution = snx.sbt.Contribution
  val Contribution: snx.sbt.Contribution.type = snx.sbt.Contribution

  type Modifier[A] = snx.sbt.Modifier[A]
  val Modifier: snx.sbt.Modifier.type = snx.sbt.Modifier

  type Usage = snx.sbt.Usage
  val Usage: snx.sbt.Usage.type = snx.sbt.Usage

  type Deliverable = snx.sbt.Deliverable
  val Deliverable: snx.sbt.Deliverable.type = snx.sbt.Deliverable

  type Linkage = snx.sbt.Linkage
  val Linkage: snx.sbt.Linkage.type = snx.sbt.Linkage

  type NativeDependency = snx.sbt.NativeDependency
  val NativeDependency: snx.sbt.NativeDependency.type = snx.sbt.NativeDependency

  type Vendored = snx.sbt.Vendored
  val Vendored: snx.sbt.Vendored.type = snx.sbt.Vendored

  val NativeClassifier: snx.sbt.NativeClassifier.type = snx.sbt.NativeClassifier

  export Deliverable.{NIR, Library, Executable}
  export Linkage.{Static, Dynamic}
  export NativeRuntime.{Linux, Darwin, Windows}
  export ABI.{Glibc, Musl, Msvc, MinGw}

  type NativeConfig = scala.scalanative.build.NativeConfig
  val NativeConfig: scala.scalanative.build.NativeConfig.type = scala.scalanative.build.NativeConfig

  type Mode = scala.scalanative.build.Mode
  val Mode: scala.scalanative.build.Mode.type = scala.scalanative.build.Mode

  type GC = scala.scalanative.build.GC
  val GC: scala.scalanative.build.GC.type = scala.scalanative.build.GC

  type LTO = scala.scalanative.build.LTO
  val LTO: scala.scalanative.build.LTO.type = scala.scalanative.build.LTO

  type Sanitizer = scala.scalanative.build.Sanitizer
  val Sanitizer: scala.scalanative.build.Sanitizer.type = scala.scalanative.build.Sanitizer

  type JVMMemoryModelCompliance = scala.scalanative.build.JVMMemoryModelCompliance
  val JVMMemoryModelCompliance: scala.scalanative.build.JVMMemoryModelCompliance.type =
    scala.scalanative.build.JVMMemoryModelCompliance

  /** Lift a `ModuleID` into a [[NativeDependency]] (no classifier); the reverse recovers the underlying `ModuleID`.
    * `% NativeClassifier`, `options`, and the forwarded builders are methods on [[NativeDependency]] - the marker
    * argument selects this conversion, while a plain `% Test` / `% "config"` resolves through sbt's own path.
    */
  given Conversion[ModuleID, NativeDependency] = module => NativeDependency(module, classified = false)
  given Conversion[NativeDependency, ModuleID] = dependency => dependency.module

  /** Add a Scala Native row driven by sbt-snx to a project matrix, for each Scala version. The sbt-snx counterpart of
    * the matrix's built-in `nativePlatform`, which enables the official Scala Native plugin; this enables
    * [[SNXPlugin$ SNXPlugin]] on the standard `native` platform axis instead. `settings` or `configure` add per-row
    * configuration, exactly as `nativePlatform` accepts them.
    */
  extension (matrix: ProjectMatrix)
    def snxPlatform(scalaVersions: Seq[String]): ProjectMatrix =
      snxRow(matrix, scalaVersions, Seq.empty, identity)
    def snxPlatform(scalaVersions: Seq[String], settings: Seq[Def.Setting[?]]): ProjectMatrix =
      snxRow(matrix, scalaVersions, Seq.empty, _.settings(settings*))
    def snxPlatform(scalaVersions: Seq[String], axisValues: Seq[VirtualAxis], settings: Seq[Def.Setting[?]]): ProjectMatrix =
      snxRow(matrix, scalaVersions, axisValues, _.settings(settings*))
    def snxPlatform(scalaVersions: Seq[String], axisValues: Seq[VirtualAxis], configure: Project => Project): ProjectMatrix =
      snxRow(matrix, scalaVersions, axisValues, configure)

  private def snxRow(
    matrix: ProjectMatrix,
    scalaVersions: Seq[String],
    axisValues: Seq[VirtualAxis],
    configure: Project => Project): ProjectMatrix =
    matrix.customRow(scalaVersions, VirtualAxis.native +: axisValues, (project: Project) => configure(project.enablePlugins(SNXPlugin)))

  /** Settings and tasks of the sbt-snx plugin, namespaced to avoid clashing with sbt or Scala Native keys
    * (for example sbt's own `target`).
    */
  object SNX:

    /** The build host's [[TargetPlatform]], from the `os.name` and `os.arch` system properties. */
    lazy val host: TargetPlatform =
      TargetPlatform.parse(sys.props.getOrElse("os.name", "").nn, sys.props.getOrElse("os.arch", "").nn)

    /** The OS/arch target the native platform resolves for. Defaults to [[host]]. */
    val target: SettingKey[TargetPlatform] =
      SettingKey[TargetPlatform]("snxTarget", "OS/arch target the native platform resolves for (default: host).")

    /** The native runtime resolved for [[target]], its ABI taken from the toolchain target triple. */
    val runtime: TaskKey[NativeRuntime] =
      TaskKey[NativeRuntime]("snxRuntime", "Resolved native runtime (target OS/arch plus toolchain ABI).")

    /** The kind of artefact to produce; fixes the publish-versus-link fork. Defaults to [[Deliverable.NIR]]. */
    val deliverable: SettingKey[Deliverable] =
      SettingKey[Deliverable]("snxDeliverable", "Artefact kind: NIR, Library, or Executable (default: NIR).")

    /** The per-platform link mode for a `Library`/`Executable` deliverable. Defaults to [[Linkage.Dynamic]]. */
    val linkage: SettingKey[PartialFunction[NativeRuntime, Linkage]] =
      SettingKey[PartialFunction[NativeRuntime, Linkage]]("snxLinkage", "Per-platform link mode (default: Dynamic).")

    /** Links the Scala Native binary for the enclosing configuration and returns it. */
    val link: TaskKey[File] =
      TaskKey[File]("snxLink", "Link the Scala Native binary.")

    /** Override the C compiler; defaults to the toolchain's discovered `clang`. */
    val clang: SettingKey[Option[File]] =
      SettingKey[Option[File]]("snxClang", "C compiler override (default: discovered clang).")

    /** Override the C++ compiler; defaults to the toolchain's discovered `clang++`. */
    val clangPP: SettingKey[Option[File]] =
      SettingKey[Option[File]]("snxClangPP", "C++ compiler override (default: discovered clang++).")

    /** Header search directories added to the native compile (`-I`). When [[target]] differs from the build host, the
      * toolchain's host-discovered header directories are dropped, so a cross build is not contaminated by host paths.
      */
    val includeDirs: SettingKey[Seq[File]] =
      SettingKey[Seq[File]]("snxIncludeDirs", "Header search directories for the native compile (-I).")

    /** Library search directories added to the native link (`-L`). When [[target]] differs from the build host, the
      * toolchain's host-discovered library directories are dropped, so a cross build is not contaminated by host paths.
      */
    val libDirs: SettingKey[Seq[File]] =
      SettingKey[Seq[File]]("snxLibDirs", "Library search directories for the native link (-L).")

    val mode: SettingKey[Mode] =
      SettingKey[Mode]("snxMode", "Scala Native build mode (default: Scala Native's).")

    val gc: SettingKey[GC] =
      SettingKey[GC]("snxGc", "Garbage collector (default: Scala Native's).")

    val lto: SettingKey[LTO] =
      SettingKey[LTO]("snxLto", "Link-time optimisation (default: Scala Native's).")

    val optimize: SettingKey[Boolean] =
      SettingKey[Boolean]("snxOptimize", "Whether to run the optimiser (default: Scala Native's).")

    val sanitizer: SettingKey[Option[Sanitizer]] =
      SettingKey[Option[Sanitizer]]("snxSanitizer", "Sanitizer to instrument the build with (default: none).")

    val multithreading: SettingKey[Option[Boolean]] =
      SettingKey[Option[Boolean]]("snxMultithreading", "Force multithreading on or off (default: Scala Native's auto-detection).")

    /** Per-platform [[Native]] transforms, applied after the scalar settings so a modifier has the final say. */
    val modifiers: SettingKey[Seq[Modifier[Native]]] =
      SettingKey[Seq[Modifier[Native]]]("snxModifiers", "Per-platform native configuration transforms.")

    /** The resolved native configuration: the discovered toolchain base, the scalar settings, the link requirements
      * propagated from [[usage]], [[dependencies]], and the resolved classpath's descriptors, then the matched
      * [[modifiers]].
      */
    val config: TaskKey[Native] =
      TaskKey[Native]("snxConfig", "Resolved native configuration.")

    /** Managed native dependencies, resolved under the build's OS/arch classifier and folding their per-platform
      * options into the native configuration. Plain NIR dependencies may stay in `libraryDependencies`.
      */
    val dependencies: SettingKey[Seq[NativeDependency]] =
      SettingKey[Seq[NativeDependency]]("snxDependencies", "Managed native dependencies (classified or option-carrying).")

    /** Native libraries built from source and folded into this configuration's link - `Compile` into the main link,
      * `Test` into the test link. Config-scoped and purely local: never published and never in the descriptor, so for
      * a NIR library (which ships its C as source) vendored C belongs only in a `Test` link.
      */
    val vendored: SettingKey[Seq[Vendored]] =
      SettingKey[Seq[Vendored]]("snxVendored", "Native libraries built from source and folded into the link.")

    /** The per-platform link requirements this library exports to consumers, published in its descriptor. A consumer
      * resolving this library folds the requirements for its own runtime into its own link.
      */
    val usage: SettingKey[PartialFunction[NativeRuntime, Usage]] =
      SettingKey[PartialFunction[NativeRuntime, Usage]]("snxUsage", "Per-platform link requirements exported to consumers.")

    /** Whether to publish the native artefact under its OS/arch classifier, for a per-platform NIR library. Defaults
      * to `false` - a single, platform-independent jar.
      */
    val classified: SettingKey[Boolean] =
      SettingKey[Boolean]("snxClassified", "Publish under the OS/arch classifier (per-platform NIR; default: false).")

  end SNX

end SNXImports
