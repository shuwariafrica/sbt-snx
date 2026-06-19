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

/** A C/C++ library built from source and folded into the consuming Scala Native link - the source-built counterpart
  * to [[NativeDependency]] (resolved from a `ModuleID`). Declared through the fluent factories on
  * [[Vendored$ Vendored]]: an [[Origin]] selects where the source comes from, a backend method how it is built, and
  * [[options]] the per-platform link contribution it adds.
  */
final class Vendored private[sbt] (
  private[sbt] val origin: Origin,
  private[sbt] val backend: Backend,
  private val modifier: Modifier[Contribution])
    derives CanEqual:

  /** Attach the per-platform link contribution this library adds to the consuming link (libraries, link/compile
    * options, includes, defines); unmatched platforms contribute nothing. Distinct from a CMake backend's `flags`,
    * which configure the C build itself.
    */
  def options(configure: Modifier[Contribution]): Vendored = new Vendored(origin, backend, configure)

  private[sbt] def contributionFor(runtime: NativeRuntime): Contribution =
    modifier.lift(runtime).fold(Contribution.empty)(transform => transform(Contribution.empty))
end Vendored

/** Origin factories for [[Vendored]], and the shared content digest used for its cache keys. */
object Vendored:

  /** Built from a local `directory`, resolved against the project base directory, then the build root. */
  def local(directory: String): Origin = Origin.Local(directory)

  /** Built from a Git repository `uri` cloned at `ref` (a tag, commit, or branch), pinned and cached. A branch is
    * cloned once and then frozen, so pin a tag or commit for a reproducible build.
    */
  def git(uri: String, ref: String): Origin = Origin.Git(uri, ref)

  /** A stable content identity for a source directory: each file's path (relative to the directory) and content
    * hash, sorted - so a cache key tracks edits to the sources but not file timestamps or ordering.
    */
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

  /** Build with CMake, building the given `targets` (none builds the default). Static libraries are forced. */
  def cmake(targets: String*): Vendored = cmake(targets, PartialFunction.empty)

  /** Build with CMake, building `targets` and adding per-platform configure `flags`. */
  def cmake(targets: Seq[String], flags: PartialFunction[NativeRuntime, Seq[String]]): Vendored =
    new Vendored(this, Backend.CMake(flags, targets, None), PartialFunction.empty)

  /** Build with CMake, building `targets`, adding per-platform configure `flags`, and prepending a `moduleOverrides`
    * directory to `CMAKE_MODULE_PATH`.
    */
  def cmake(targets: Seq[String], flags: PartialFunction[NativeRuntime, Seq[String]], moduleOverrides: File): Vendored =
    new Vendored(this, Backend.CMake(flags, targets, Some(moduleOverrides)), PartialFunction.empty)
end Origin

/** Variants of [[Origin]]. */
object Origin:
  final private[sbt] case class Local(directory: String) extends Origin
  final private[sbt] case class Git(uri: String, ref: String) extends Origin
