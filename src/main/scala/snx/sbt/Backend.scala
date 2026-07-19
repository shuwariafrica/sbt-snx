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

import sbt.io.IO
import sbt.io.syntax.*
import sbt.util.Logger

import java.io.File

import scala.scalanative.build.Mode
import scala.sys.process.Process
import scala.sys.process.ProcessLogger

import snx.ABI
import snx.NativeRuntime
import snx.NativeRuntime.*
import snx.SNXError

/** The inputs a [[Backend]] receives: the source directory, a staging directory for its outputs, the resolved
  * [[snx.NativeRuntime NativeRuntime]], the requested [[Linkage]] (a `Dynamic` request must build a shared library, a
  * `Static` one an archive), the Scala Native build [[scala.scalanative.build.Mode Mode]] (from which a CMake backend
  * derives `CMAKE_BUILD_TYPE`, matching the deliverable's own optimisation level), the C and C++ compilers, and the
  * build logger.
  */
final case class BuildContext(
  source: File,
  staging: File,
  runtime: NativeRuntime,
  linkage: Linkage,
  mode: Mode,
  clang: File,
  clangPP: File,
  log: Logger)

/** The outputs of a [[Backend]] build: the built library files to link - static archives or shared libraries, per the
  * [[BuildContext]]'s [[Linkage]] - and the header directories to expose (`-I`).
  */
final case class Artefacts(libraries: Seq[File], includes: Seq[File]) derives CanEqual

/** Factory for [[Artefacts]]. */
object Artefacts:
  private[sbt] def empty: Artefacts = Artefacts(Seq.empty, Seq.empty)

// How a Vendored library's source is built into Artefacts; Origin carries the factory methods that select a backend.
sealed private[sbt] trait Backend:

  def build(context: BuildContext): Artefacts

  // The backend's contribution to the per-library cache key for `runtime` - its resolved configuration, so a config
  // change invalidates the cached archive.
  def cacheKey(runtime: NativeRuntime): Seq[String]

private[sbt] object Backend:

  // A CMake build of `targets` with per-platform configure `flags`, `BUILD_SHARED_LIBS` set from the requested Linkage
  // (Static forces archives, Dynamic shared libraries), and an optional `moduleOverrides` dir prepended to
  // CMAKE_MODULE_PATH.
  final case class CMake(flags: PartialFunction[NativeRuntime, Seq[String]], targets: Seq[String], moduleOverrides: Option[File])
      extends Backend:

    def build(context: BuildContext): Artefacts =
      context.runtime match
        case Windows(_, ABI.MinGw) =>
          val message =
            "snx: source-built C via the CMake backend is not supported on the MinGW toolchain; " +
              "use the MSVC toolchain to build vendored C on Windows."
          fail(SNXError.UnsupportedToolchain(message))
        case Linux(_, _) | Darwin(_) | Windows(_, ABI.Msvc) => ()
      if !(context.source / "CMakeLists.txt").isFile then
        fail(SNXError.CMakeBuildFailed(s"snx: no CMakeLists.txt in ${context.source.getAbsolutePath}"))
      val shared = context.linkage == Linkage.Dynamic
      val buildType = cmakeBuildType(context.mode)
      val buildDir = context.staging / "build"
      val prefix = context.staging / "prefix"
      IO.createDirectory(buildDir)
      // CMake re-parses a -D string value as CMake code, so a Windows backslash path reads as escape sequences (`\U`,
      // ...) and aborts configure; forward slashes are accepted on every platform.
      val overrides = moduleOverrides.toSeq.map(dir => s"-DCMAKE_MODULE_PATH=${dir.getAbsolutePath.nn.replace('\\', '/')}")
      val configureFlags = flags.applyOrElse(context.runtime, (_: NativeRuntime) => Nil)
      run(
        Seq(
          "cmake",
          "-S",
          context.source.getAbsolutePath,
          "-B",
          buildDir.getAbsolutePath,
          s"-DCMAKE_BUILD_TYPE=$buildType",
          s"-DBUILD_SHARED_LIBS=${if shared then "ON" else "OFF"}"
        ) ++ compilerPin(context.runtime, context.clang, context.clangPP) ++ overrides ++ configureFlags,
        "configure",
        context.log
      )
      run(
        Seq("cmake", "--build", buildDir.getAbsolutePath, "--config", buildType, "--parallel", buildJobs.toString)
          ++ targets.flatMap(target => Seq("--target", target)),
        "build",
        context.log
      )
      run(
        Seq("cmake", "--install", buildDir.getAbsolutePath, "--prefix", prefix.getAbsolutePath, "--config", buildType),
        "install",
        context.log)
      val accept: String => Boolean = if shared then isShared(context.runtime) else isArchive(context.runtime)
      val libraries = prefix.allPaths.get().filter(file => file.isFile && accept(file.getName.nn))
      if libraries.isEmpty then
        fail(
          SNXError.CMakeBuildFailed(
            s"snx: cmake produced no ${if shared then "shared" else "static"} library under ${prefix.getAbsolutePath}; " +
              s"the project must define install() rules for its ${if shared then "LIBRARY/RUNTIME" else "ARCHIVE"} artefacts."))
      Artefacts(libraries, Seq(prefix / "include").filter(_.isDirectory))
    end build

    def cacheKey(runtime: NativeRuntime): Seq[String] =
      val overridesKey = moduleOverrides.filter(_.isDirectory).map(Vendored.contentDigest).toSeq
      Seq("cmake", targets.mkString(",")) ++ flags.applyOrElse(runtime, (_: NativeRuntime) => Nil) ++ overridesKey
  end CMake

  // A user-supplied build: `action` produces the Artefacts; `token` keys the cache (the action itself is opaque).
  final case class Command(token: String, action: BuildContext => Artefacts) extends Backend:
    def build(context: BuildContext): Artefacts = action(context)
    def cacheKey(runtime: NativeRuntime): Seq[String] = Seq("command", token)

  // The CMAKE_BUILD_TYPE for a Scala Native build Mode, so a vendored library matches the deliverable's own
  // optimisation: debug (-O0) -> Debug, release-size -> MinSizeRel, the other release modes -> Release. Matched on
  // Mode.name because the Mode cases are private[scalanative] (and carry no CanEqual for strict equality), so it is
  // coupled to Scala Native's mode-name strings; the value is keyed into the vendored build cache.
  private[sbt] def cmakeBuildType(mode: Mode): String = mode.name match
    case "debug"        => "Debug"
    case "release-size" => "MinSizeRel"
    case _              => "Release"

  // Whether `fileName` is a static archive for `runtime`: .lib on MSVC, .a elsewhere. Per-runtime so a Unix build does
  // not accept a stray Windows import .lib, and vice versa.
  private def isArchive(runtime: NativeRuntime)(fileName: String): Boolean = runtime match
    case Windows(_, ABI.Msvc)                            => fileName.endsWith(".lib")
    case Linux(_, _) | Darwin(_) | Windows(_, ABI.MinGw) => fileName.endsWith(".a")

  private def isShared(runtime: NativeRuntime)(fileName: String): Boolean = runtime match
    case Linux(_, _)   => fileName.endsWith(".so")
    case Darwin(_)     => fileName.endsWith(".dylib")
    case Windows(_, _) => fileName.endsWith(".dll")

  // Pin the compiler the CMake build uses, per platform. On Linux and macOS the vendored C is pinned to the same
  // clang/clang++ the Scala Native link uses (context.clang/clangPP, so an SNX.clang override reaches it too), so the
  // archive is built by an ABI-compatible compiler rather than CMake's default (which may be cc/gcc). On Windows the
  // MSVC toolchain is CMake's default and is ABI-compatible with the clang-windows-msvc link, so it is left to CMake's
  // own detection - forcing clang there would need a generator/toolset override (MinGW has already failed fast in
  // build). User `flags` follow the pin, so a project can still override it.
  private[sbt] def compilerPin(runtime: NativeRuntime, clang: File, clangPP: File): Seq[String] =
    runtime match
      case Linux(_, _) | Darwin(_) =>
        Seq(s"-DCMAKE_C_COMPILER=${clang.getAbsolutePath}", s"-DCMAKE_CXX_COMPILER=${clangPP.getAbsolutePath}")
      case Windows(_, _) => Seq.empty

  // The job count for `cmake --build --parallel`, bounded to the host's processor count. A bare --parallel defers to
  // the native tool's default, UNBOUNDED for a Makefiles generator - a fork bomb when several vendored builds run at
  // once; an explicit count bounds each build.
  private def buildJobs: Int = math.max(1, Runtime.getRuntime.nn.availableProcessors)

  private def fail(error: SNXError): Nothing = throw error // scalafix:ok DisableSyntax.throw

  private def run(command: Seq[String], phase: String, log: Logger): Unit =
    val logger = ProcessLogger(line => log.info(line), line => log.error(line))
    if Process(command).!(logger) != 0 then fail(SNXError.CMakeBuildFailed(s"snx: cmake $phase failed: ${command.mkString(" ")}"))
end Backend
