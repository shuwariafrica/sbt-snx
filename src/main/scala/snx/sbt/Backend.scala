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

import scala.sys.process.Process
import scala.sys.process.ProcessLogger

import snx.ABI
import snx.NativeRuntime
import snx.NativeRuntime.*
import snx.SNXError

/** The inputs a [[Backend]] receives to build one [[Vendored]] library: the resolved source directory, a staging
  * directory for its build outputs, the resolved [[snx.NativeRuntime NativeRuntime]], and the build logger.
  */
final case class BuildContext(source: File, staging: File, runtime: NativeRuntime, log: Logger)

/** The outputs of a [[Backend]] build: the static archives to link and the header directories to expose (`-I`). */
final case class Artefacts(archives: Seq[File], includes: Seq[File]) derives CanEqual

/** Factory for [[Artefacts]]. */
object Artefacts:
  private[sbt] def empty: Artefacts = Artefacts(Seq.empty, Seq.empty)

/** How a [[Vendored]] library's source is built into [[Artefacts]]. See [[Origin]] for the factory methods that
  * select a backend.
  */
sealed private[sbt] trait Backend:

  /** Build the source described by `context` and return the archives to link and header directories to expose. */
  def build(context: BuildContext): Artefacts

  /** The backend's contribution to the per-library cache key for `runtime` - its resolved configuration, so a change
    * of targets, flags, or module overrides invalidates the cached output.
    */
  def cacheKey(runtime: NativeRuntime): Seq[String]

/** Supported [[Backend]]s and their build mechanics. */
private[sbt] object Backend:

  /** A CMake build: configure (forcing static libraries), build the `targets` (empty builds the default),
    * `cmake --install` into a staging prefix, then collect the installed archives and headers. `flags` adds
    * per-platform configure flags - applied after the static-library default, so a `flags` entry may override it;
    * `moduleOverrides`, when set, is prepended to `CMAKE_MODULE_PATH`.
    */
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
      val buildDir = context.staging / "build"
      val prefix = context.staging / "prefix"
      IO.createDirectory(buildDir)
      val overrides = moduleOverrides.toSeq.map(dir => s"-DCMAKE_MODULE_PATH=${dir.getAbsolutePath}")
      val configureFlags = flags.applyOrElse(context.runtime, (_: NativeRuntime) => Nil)
      run(
        Seq(
          "cmake",
          "-S",
          context.source.getAbsolutePath,
          "-B",
          buildDir.getAbsolutePath,
          "-DCMAKE_BUILD_TYPE=Release",
          "-DBUILD_SHARED_LIBS=OFF") ++ overrides ++ configureFlags,
        "configure",
        context.log
      )
      run(
        Seq("cmake", "--build", buildDir.getAbsolutePath, "--config", "Release", "--parallel")
          ++ targets.flatMap(target => Seq("--target", target)),
        "build",
        context.log
      )
      run(
        Seq("cmake", "--install", buildDir.getAbsolutePath, "--prefix", prefix.getAbsolutePath, "--config", "Release"),
        "install",
        context.log)
      val archives = prefix.allPaths.get().filter(file => file.isFile && isArchive(file.getName.nn))
      if archives.isEmpty then
        fail(
          SNXError.CMakeBuildFailed(
            s"snx: cmake produced no static library under ${prefix.getAbsolutePath}; the project must define install() rules."))
      Artefacts(archives, Seq(prefix / "include").filter(_.isDirectory))
    end build

    def cacheKey(runtime: NativeRuntime): Seq[String] =
      val overridesKey = moduleOverrides.filter(_.isDirectory).map(Vendored.contentDigest).toSeq
      Seq("cmake", targets.mkString(",")) ++ flags.applyOrElse(runtime, (_: NativeRuntime) => Nil) ++ overridesKey
  end CMake

  /** A user-supplied build: `action` produces the [[Artefacts]] from the [[BuildContext]]; `token` keys the cache, as
    * the function itself cannot be hashed.
    */
  final case class Command(token: String, action: BuildContext => Artefacts) extends Backend:
    def build(context: BuildContext): Artefacts = action(context)
    def cacheKey(runtime: NativeRuntime): Seq[String] = Seq("command", token)

  private def isArchive(fileName: String): Boolean = fileName.endsWith(".a") || fileName.endsWith(".lib")

  private def fail(error: SNXError): Nothing = throw error // scalafix:ok DisableSyntax.throw

  private def run(command: Seq[String], phase: String, log: Logger): Unit =
    val logger = ProcessLogger(line => log.info(line), line => log.error(line))
    if Process(command).!(logger) != 0 then fail(SNXError.CMakeBuildFailed(s"snx: cmake $phase failed: ${command.mkString(" ")}"))
end Backend
