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
import java.nio.file.Path
import java.nio.file.Paths

import scala.scalanative.build.Mode
import scala.sys.process.Process
import scala.sys.process.ProcessLogger
import scala.util.Try

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
  *
  * Two or more static archives link as one group on Linux and MinGW (`--start-group` ... `--end-group`), so references
  * between them resolve whatever their order, cycles included; ld64 and MSVC resolve archives without regard to order
  * and take them ungrouped, as does a whole-archive library, which loads every member. Order still decides which of two
  * archives defining one symbol supplies it.
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

  // A CMake build of `targets` from the project in `directory` (relative to the source root), with per-platform
  // configure `flags`, `BUILD_SHARED_LIBS` set from the requested Linkage (Static forces archives, Dynamic shared
  // libraries), and an optional `moduleOverrides` dir prepended to CMAKE_MODULE_PATH.
  final case class CMake(
    targets: Seq[String],
    flags: PartialFunction[NativeRuntime, Seq[String]],
    directory: String,
    moduleOverrides: Option[File])
      extends Backend:

    def build(context: BuildContext): Artefacts =
      context.runtime match
        case Windows(_, ABI.MinGw) =>
          val message =
            "snx: source-built C via the CMake backend is not supported on the MinGW toolchain; " +
              "use the MSVC toolchain to build vendored C on Windows."
          fail(SNXError.UnsupportedToolchain(message))
        case Linux(_, _) | Darwin(_) | Windows(_, ABI.Msvc) => ()
      val project = context.source.toPath.nn.resolve(projectDirectory(directory)).nn.toFile.nn
      if !(project / "CMakeLists.txt").isFile then
        fail(
          SNXError.CMakeBuildFailed(
            s"snx: no CMakeLists.txt in ${project.getAbsolutePath}; a project whose top-level CMakeLists.txt sits in a " +
              "subdirectory of the vendored source names it with .directory(\"<path>\")"))
      val shared = context.linkage == Linkage.Dynamic
      val buildType = cmakeBuildType(context.mode)
      val buildDir = context.staging / "build"
      val prefix = context.staging / "prefix"
      val prefixPath = cmakePath(prefix)
      IO.createDirectory(buildDir)
      val overrides = moduleOverrides.toSeq.map(dir => s"-DCMAKE_MODULE_PATH=${cmakePath(dir)}")
      val configureFlags = flags.applyOrElse(context.runtime, (_: NativeRuntime) => Nil)
      // The prefix is set at configure as well as at install: an install() rule's destination resolved at configure
      // (GNUInstallDirs' CMAKE_INSTALL_FULL_<dir>, brotli's man pages) would otherwise point at the system prefix.
      run(
        compilerPin(context.runtime, context.clang, context.clangPP),
        Seq(
          "cmake",
          "-S",
          project.getAbsolutePath,
          "-B",
          buildDir.getAbsolutePath,
          s"-DCMAKE_BUILD_TYPE=$buildType",
          s"-DBUILD_SHARED_LIBS=${if shared then "ON" else "OFF"}",
          s"-DCMAKE_INSTALL_PREFIX=$prefixPath"
        ) ++ overrides ++ configureFlags,
        "configure",
        context.log
      )
      run(
        Seq.empty,
        Seq("cmake", "--build", buildDir.getAbsolutePath, "--config", buildType, "--parallel", buildJobs.toString)
          ++ targets.flatMap(target => Seq("--target", target)),
        "build",
        context.log
      )
      val install = Seq("cmake", "--install", buildDir.getAbsolutePath, "--prefix", prefixPath, "--config", buildType)
      if !succeeds(Seq.empty, install, context.log) then
        fail(
          SNXError.CMakeBuildFailed(
            s"snx: cmake install failed: ${install.mkString(" ")}. cmake --install runs every install() rule of the " +
              "project, so a rule for a target outside `targets`, or one writing to a fixed absolute path the build " +
              "cannot write, fails it; turn such a rule off through the project's own option in `configure`."))
      requireInstalled(buildDir / "install_manifest.txt", prefix)
      val libraries = collected(context.runtime, context.linkage, prefix, prefix.allPaths.get().filter(_.isFile))
      if libraries.isEmpty then
        fail(
          SNXError.CMakeBuildFailed(
            s"snx: cmake produced no ${if shared then "shared" else "static"} library under ${prefix.getAbsolutePath}; " +
              s"the project must define install() rules for its ${if shared then "LIBRARY/RUNTIME" else "ARCHIVE"} artefacts."))
      Artefacts(libraries, Seq(prefix / "include").filter(_.isDirectory))
    end build

    // The project directory enters the key in its normal form and tagged, so equivalent spellings share one key and no
    // configure flag can stand in for it.
    def cacheKey(runtime: NativeRuntime): Seq[String] =
      val overridesKey = moduleOverrides.filter(_.isDirectory).map(Vendored.contentDigest).toSeq
      Seq("cmake", targets.mkString(","), s"directory=${projectDirectory(directory)}") ++
        flags.applyOrElse(runtime, (_: NativeRuntime) => Nil) ++ overridesKey
  end CMake

  // The normal form of a CMake project `directory`, relative to the vendored source root. One with a root component
  // (on Windows also `\x` and the drive-relative `C:x`) or whose normal form climbs out of the root fails: the cache
  // keys on the source root's content, so a project outside it would build from inputs the key does not cover.
  private[sbt] def projectDirectory(directory: String): Path =
    Try(Paths.get(directory).nn).toOption
      .filter(path => Option(path.getRoot).isEmpty)
      .map(_.normalize.nn)
      .filterNot(_.startsWith(".."))
      .getOrElse(
        fail(
          SNXError.DirectoryOutsideSource(
            s"snx: the CMake project directory '$directory' is not a path inside the vendored source; name the directory " +
              "holding the top-level CMakeLists.txt relative to the source root, for example .directory(\"build/cmake\")")))

  // The libraries a CMake install yields, from the files found under its `prefix`: archives for a Static build, shared
  // libraries for a Dynamic one, sorted by their path under the prefix. The sort, not the filesystem's enumeration,
  // decides the link order, and with it which of two archives defining one symbol supplies it.
  private[sbt] def collected(runtime: NativeRuntime, linkage: Linkage, prefix: File, files: Seq[File]): Seq[File] =
    val accept: String => Boolean = linkage match
      case Linkage.Static  => isArchive(runtime)
      case Linkage.Dynamic => isShared(runtime)
    val root = prefix.toPath.nn
    files.filter(file => accept(file.getName.nn)).sortBy(file => root.relativize(file.toPath).toString.replace('\\', '/'))

  // Fail when the CMake install `manifest` (one absolute path per installed file) names a path outside `prefix`. Such a
  // path is an install() rule with a fixed absolute destination that the host let the build write: the write has
  // happened, and this makes it fail the build by name rather than pass unnoticed.
  private[sbt] def requireInstalled(manifest: File, prefix: File): Unit =
    if !manifest.isFile then
      fail(
        SNXError.CMakeBuildFailed(
          s"snx: cmake install wrote no ${manifest.getAbsolutePath}, so what it installed cannot be checked against the " +
            s"staging prefix ${prefix.getAbsolutePath}"))
    val root = prefix.toPath.nn.toAbsolutePath.nn.normalize.nn
    val outside = IO.readLines(manifest).filterNot(line => Paths.get(line).nn.toAbsolutePath.nn.normalize.nn.startsWith(root))
    if outside.nonEmpty then
      fail(
        SNXError.OutputOutsideStaging(
          s"snx: cmake install wrote outside the staging prefix ${prefix.getAbsolutePath}: ${outside.mkString(", ")}. An " +
            "install() rule with a fixed absolute destination wrote there; turn it off through the project's own option " +
            "in `configure`."))
  end requireInstalled

  // A path for CMake. CMake re-parses a -D string value as CMake code, so a Windows backslash path reads as escape
  // sequences (`\U`, ...) and aborts configure; forward slashes are accepted on every platform.
  private def cmakePath(file: File): String = file.getAbsolutePath.nn.replace('\\', '/')

  // A user-supplied build: `action` produces the Artefacts; `token` is the cache identity - the action is opaque, so
  // its logic is not otherwise keyed and the consumer must bump `token` on a build-logic change (see Vendored.command).
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

  // The compiler the CMake build uses, as the configure process's CC and CXX. On Linux and macOS the vendored C is built
  // by the clang/clang++ the Scala Native link uses (context.clang/clangPP, so an SNX.clang override reaches it too), an
  // ABI-compatible compiler rather than CMake's default (which may be cc/gcc). The pair replaces any ambient CC/CXX, so
  // the host environment cannot choose the compiler. CMake reads them only on a project's first configure, which every
  // vendored configure is (a cache miss deletes the staging), and not for a language whose CMAKE_<LANG>_COMPILER is
  // already set, so a -DCMAKE_C_COMPILER in `configure` still wins. Unlike a -D pin, an environment raises no
  // unused-variable warning in a project that never enables C++. On Windows MSVC is CMake's default and is
  // ABI-compatible with the clang-windows-msvc link, so it is left to CMake's own detection - forcing clang there would
  // need a generator/toolset override (MinGW has already failed fast in build).
  private[sbt] def compilerPin(runtime: NativeRuntime, clang: File, clangPP: File): Seq[(String, String)] =
    runtime match
      case Linux(_, _) | Darwin(_) => Seq("CC" -> clang.getAbsolutePath.nn, "CXX" -> clangPP.getAbsolutePath.nn)
      case Windows(_, _)           => Seq.empty

  // The job count for `cmake --build --parallel`, bounded to the host's processor count. A bare --parallel defers to
  // the native tool's default, UNBOUNDED for a Makefiles generator - a fork bomb when several vendored builds run at
  // once; an explicit count bounds each build.
  private def buildJobs: Int = math.max(1, Runtime.getRuntime.nn.availableProcessors)

  private def fail(error: SNXError): Nothing = throw error // scalafix:ok DisableSyntax.throw

  private def run(environment: Seq[(String, String)], command: Seq[String], phase: String, log: Logger): Unit =
    if !succeeds(environment, command, log) then fail(SNXError.CMakeBuildFailed(s"snx: cmake $phase failed: ${command.mkString(" ")}"))

  // CMake writes its warnings to stderr, so stderr is logged at warn; a failure is the exit status, which the caller
  // raises as its own error.
  private def succeeds(environment: Seq[(String, String)], command: Seq[String], log: Logger): Boolean =
    Process(command, None, environment*).!(ProcessLogger(line => log.info(line), line => log.warn(line))) == 0
end Backend
