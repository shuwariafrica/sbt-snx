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
import java.nio.file.NoSuchFileException

import snx.NativeRuntime
import snx.SNXError

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

  /** Built from a Git repository `uri` at `ref` - a tag, branch, or commit. The build is cached on the commit the ref
    * resolves to, so moving a branch or force-moving a tag rebuilds; a full commit SHA resolves without a network
    * call. Only the referenced tree is fetched, and a backend is handed the checked-out sources alone - the repository
    * itself is staged outside the directory it receives.
    */
  def git(uri: String, ref: String): Origin = Origin.Git(uri, ref)

  // A source directory's identity for the build cache. Paths are relative and the entries sorted, so neither the
  // directory's location nor the order the filesystem enumerates it reaches the key; including the path alongside the
  // content means a rename invalidates as a content change does.
  private[sbt] def contentDigest(directory: File): String =
    contentDigest(directory, directory.allPaths.get().filter(_.isFile))

  // The identity of an already-enumerated file set. A listed file that is gone by the time it is read means the
  // directory changed between the two steps, and that stops the build rather than being skipped: a digest over the
  // survivors describes a file set that never existed on disk, and it can equal the digest of a state that legitimately
  // did - serving a cached archive built from different sources, which is the silent staleness the cache exists to
  // prevent.
  private[sbt] def contentDigest(directory: File, files: Seq[File]): String =
    val root = directory.toPath.nn
    files
      .map: file =>
        val content =
          try Digest.sha256Hash(file.toPath.nn).hashHexString
          catch
            case _: NoSuchFileException =>
              fail(
                SNXError.SourceChanged(
                  s"snx: the vendored source directory '${directory.getAbsolutePath}' changed while the build was " +
                    s"reading it - '${file.getAbsolutePath}' was listed but no longer exists. The vendored build cache " +
                    "is keyed on this directory's contents, so it must not be modified while a build is running."))
        s"${root.relativize(file.toPath)}:$content"
      .sorted
      .mkString("\n")

  private def fail(error: SNXError): Nothing = throw error // scalafix:ok DisableSyntax.throw
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
    *
    * `token` is the build's cache identity, and it is your responsibility. This function is opaque to snx, so the cache
    * keys on `token` alone - the source content, the toolchain, and the resolved runtime/linkage/mode are tracked for
    * you, but a change to the function's OWN logic is invisible. Change `token` on every change to the build (a flag, a
    * target, the recipe); otherwise a warm cache silently reuses the previous archive and a green build links a stale
    * one. Deriving `token` from the build's inputs - a version suffix you bump, or the flags folded into the string -
    * keeps it honest.
    */
  def command(token: String)(build: BuildContext => Artefacts): Vendored =
    new Vendored(this, Backend.Command(token, build), PartialFunction.empty)
end Origin

object Origin:
  final private[sbt] case class Local(directory: String) extends Origin
  final private[sbt] case class Git(uri: String, ref: String) extends Origin
