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

import snx.NativePlatform

/** The inputs a [[NativeBackend]] receives to build one [[NativeSource]]: the resolved source directory, a staging
  * directory for its build outputs, the resolved [[snx.NativePlatform NativePlatform]], and the build logger.
  */
final case class BuildContext(source: File, staging: File, platform: NativePlatform, log: Logger)

/** How a [[NativeSource]]'s code is built into [[NativeArtefacts]]; each backend resolves its own archives and include
  * directories. See [[NativeBackend$ NativeBackend]] for the supported backends.
  */
sealed trait NativeBackend:
  /** Build the sources described by `context` and return the archives to link and include directories to expose. */
  def build(context: BuildContext): NativeArtefacts

  /** The backend's contribution to the per-library build cache key for `platform` - its resolved configuration, so a
    * change of build flags or targets invalidates the cached output.
    */
  def cacheKey(platform: NativePlatform): Seq[String]

/** Supported [[NativeBackend]]s and their factories. */
object NativeBackend:

  /** A CMake build: configure, build the `targets` (empty builds the default), `cmake --install` into a staging
    * prefix, then collect the installed archives and headers. `flags` adds per-platform configure flags;
    * `moduleOverrides`, when set, is prepended to `CMAKE_MODULE_PATH`.
    */
  final case class CMake(flags: PartialFunction[NativePlatform, Seq[String]], targets: Seq[String], moduleOverrides: Option[File])
      extends NativeBackend:

    def build(context: BuildContext): NativeArtefacts =
      if !(context.source / "CMakeLists.txt").isFile then sys.error(s"snx: no CMakeLists.txt in ${context.source.getAbsolutePath}")
      val buildDir = context.staging / "build"
      val prefix = context.staging / "prefix"
      IO.createDirectory(buildDir)
      val overrides = moduleOverrides.toSeq.map(dir => s"-DCMAKE_MODULE_PATH=${dir.getAbsolutePath}")
      val configureFlags = flags.applyOrElse(context.platform, (_: NativePlatform) => Nil)
      run(
        Seq("cmake", "-S", context.source.getAbsolutePath, "-B", buildDir.getAbsolutePath, "-DCMAKE_BUILD_TYPE=Release")
          ++ overrides ++ configureFlags,
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
        sys.error(s"snx: cmake produced no static library under ${prefix.getAbsolutePath}; the project must define install() rules.")
      NativeArtefacts(archives, Seq(prefix / "include").filter(_.isDirectory))
    end build

    def cacheKey(platform: NativePlatform): Seq[String] =
      Seq("cmake", targets.mkString(",")) ++ flags.applyOrElse(platform, (_: NativePlatform) => Nil)
  end CMake

  /** Factories for [[CMake]]. */
  object CMake:
    def apply(targets: Seq[String]): CMake = CMake(PartialFunction.empty, targets, None)

    def apply(flags: PartialFunction[NativePlatform, Seq[String]], targets: Seq[String]): CMake =
      CMake(flags, targets, None)

  private def isArchive(fileName: String): Boolean = fileName.endsWith(".a") || fileName.endsWith(".lib")

  private def run(command: Seq[String], phase: String, log: Logger): Unit =
    val logger = ProcessLogger(line => log.info(line), line => log.error(line))
    if Process(command).!(logger) != 0 then sys.error(s"snx: cmake $phase failed: ${command.mkString(" ")}")
end NativeBackend
