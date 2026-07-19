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

import sbt.io.syntax.*
import sbt.util.Digest

import java.io.File

import snx.NativeRuntime

/** A C/C++ library built from source and folded into the link. Declared through the factories on [[Vendored$ Vendored]]:
  * an [[Origin]], a backend, and per-platform [[options]].
  */
final class Vendored private[sbt] (
  private[sbt] val origin: Origin,
  private[sbt] val backend: Backend,
  private val closure: PartialFunction[NativeRuntime, Flags])
    derives CanEqual:

  /** Attach this library's per-platform link closure - the transitive `-l`/flags a static archive does not carry.
    * Distinct from a CMake backend's `flags`, which configure the C build.
    */
  def options(closure: PartialFunction[NativeRuntime, Flags]): Vendored = new Vendored(origin, backend, closure)

  private[sbt] def closureFor(runtime: NativeRuntime): Flags =
    closure.applyOrElse(runtime, (_: NativeRuntime) => Flags.empty)
end Vendored

/** Origin factories for [[Vendored]]. */
object Vendored:

  /** Built from a local `directory`, resolved against the project base directory, then the build root. */
  def local(directory: String): Origin = Origin.Local(directory)

  /** Built from a Git repository `uri` at `ref` (a tag, commit, or branch). The ref is resolved to its current commit
    * and the build cached on that commit, so moving a branch or force-moving a tag rebuilds; a full commit SHA is used
    * directly (no network). The clone is shallow - only the referenced tree is fetched, not the whole history.
    */
  def git(uri: String, ref: String): Origin = Origin.Git(uri, ref)

  // A stable content identity for a source directory: each file's relative path and content hash, sorted.
  private[sbt] def contentDigest(directory: File): String =
    val root = directory.toPath.nn
    directory.allPaths
      .get()
      .filter(_.isFile)
      .map(file => s"${root.relativize(file.toPath)}:${Digest.sha256Hash(file.toPath.nn).hashHexString}")
      .sorted
      .mkString("\n")
end Vendored

/** Where a [[Vendored]] library's source comes from, with the backend methods that build it. See
  * [[Vendored$ Vendored]] for the origin factories.
  */
sealed trait Origin derives CanEqual:

  /** Build with CMake, building `targets` (none builds the default); the library is built static or shared per its
    * resolved per-library [[Linkage]]. Unsupported on the Windows MinGW toolchain.
    */
  def cmake(targets: String*): Vendored = cmake(targets, PartialFunction.empty)

  /** Build with CMake, building `targets` and adding per-platform configure `flags`. */
  def cmake(targets: Seq[String], flags: PartialFunction[NativeRuntime, Seq[String]]): Vendored =
    new Vendored(this, Backend.CMake(flags, targets, None), PartialFunction.empty)

  /** Build with CMake, building `targets`, adding per-platform configure `flags`, and prepending a `moduleOverrides`
    * directory to `CMAKE_MODULE_PATH`.
    */
  def cmake(targets: Seq[String], flags: PartialFunction[NativeRuntime, Seq[String]], moduleOverrides: File): Vendored =
    new Vendored(this, Backend.CMake(flags, targets, Some(moduleOverrides)), PartialFunction.empty)

  /** Build with a user-supplied function from [[BuildContext]] to [[Artefacts]], writing outputs under the context's
    * staging directory and honouring its [[Linkage]] (an archive for `Static`, a shared library for `Dynamic`).
    * `token` keys the cache; change it when the build logic changes.
    */
  def command(token: String)(build: BuildContext => Artefacts): Vendored =
    new Vendored(this, Backend.Command(token, build), PartialFunction.empty)
end Origin

object Origin:
  final private[sbt] case class Local(directory: String) extends Origin
  final private[sbt] case class Git(uri: String, ref: String) extends Origin
