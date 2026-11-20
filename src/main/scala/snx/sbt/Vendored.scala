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
  * an [[Origin]], a backend, and per-platform [[options]]; a CMake build is a [[Vendored$.CMake Vendored.CMake]], which
  * adds its CMake settings.
  */
sealed class Vendored private[sbt] (
  private[sbt] val origin: Origin,
  private[sbt] val backend: Backend,
  private val closure: PartialFunction[NativeRuntime, Flags])
    derives CanEqual:

  /** Attach this library's per-platform link closure - the transitive `-l`/flags a static archive does not carry.
    * Distinct from a CMake build's `configure` flags, which configure the C build.
    */
  def options(closure: PartialFunction[NativeRuntime, Flags]): Vendored = new Vendored(origin, backend, closure)

  private[sbt] def closureFor(runtime: NativeRuntime): Flags =
    closure.applyOrElse(runtime, (_: NativeRuntime) => Flags.empty)
end Vendored

/** Origin factories for [[Vendored]]. */
object Vendored:

  /** A [[Vendored]] library built with CMake, from [[Origin.cmake]]. Each setter returns a new value with that one
    * setting replaced, so they chain in any order and a setter called twice keeps the last value.
    */
  final class CMake private[sbt] (origin: Origin, settings: Backend.CMake, closure: PartialFunction[NativeRuntime, Flags])
      extends Vendored(origin, settings, closure):

    /** Per-platform configure flags (`-D<option>=<value>`), passed after the plugin's own, so one can override them. A
      * runtime the function does not match configures with none.
      */
    def configure(flags: PartialFunction[NativeRuntime, Seq[String]]): CMake =
      new CMake(origin, settings.copy(flags = flags), closure)

    /** The directory holding the project's top-level `CMakeLists.txt`, relative to the vendored source root (default
      * `.`), for a repository that keeps its CMake project in a subdirectory (zstd's `build/cmake`). The whole source
      * is still fetched and keyed, so the project may read files elsewhere in it, as zstd's reads `../../lib`. A path
      * that is absolute or reaches outside the source fails the build with
      * [[snx.SNXError$.DirectoryOutsideSource DirectoryOutsideSource]] before the source is fetched.
      */
    def directory(path: String): CMake = new CMake(origin, settings.copy(directory = path), closure)

    /** A directory prepended to `CMAKE_MODULE_PATH`, whose modules then take precedence over the project's own. Its
      * content is part of the build's cache key.
      */
    def moduleOverrides(directory: File): CMake = new CMake(origin, settings.copy(moduleOverrides = Some(directory)), closure)

    override def options(closure: PartialFunction[NativeRuntime, Flags]): CMake = new CMake(origin, settings, closure)
  end CMake

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

  /** Build with CMake, building `targets` (none builds the project's default), static or shared per the library's
    * resolved [[Linkage]]. Every library the install writes under its staging prefix is linked, not only those of
    * `targets`, in the order of their paths under the prefix. Unsupported on the Windows MinGW toolchain.
    */
  def cmake(targets: String*): Vendored.CMake =
    new Vendored.CMake(this, Backend.CMake(targets, PartialFunction.empty, ".", None), PartialFunction.empty)

  /** Build by running the [[Recipe]] `recipe` computes for each build's [[BuildContext]] - a Makefile, autotools or
    * hand-rolled compile CMake does not cover, on any toolchain, MinGW included. The plugin runs the recipe's steps and
    * links the libraries it names, in the order named.
    *
    * The cache keys on the recipe the function returns, not on the function: any change to a step, an argument, an
    * environment value, an output, or the content of a file or directory passed as a `File`, an `arg` part or a
    * `Matching` - anywhere but this library's own source and staging - rebuilds the library, wherever the code
    * computing it lives. A path written in text is keyed as text; pass a file or directory whose content the build
    * depends on as a `File`, an `arg` part or a `Matching` ([[Step$.Run Step.Run]] says what each keys). Not keyed:
    * what a file refers to inside its content (a response file, a header's includes, a Makefile's); what a program
    * reads by a path it computes itself; the programs it starts; and the inherited environment beyond the variables
    * the toolchain identity keys. `recipe` runs on every build, so it computes and returns a value and does nothing
    * else.
    */
  def command(recipe: BuildContext => Recipe): Vendored =
    new Vendored(this, Backend.Command(recipe), PartialFunction.empty)
end Origin

object Origin:
  final private[sbt] case class Local(directory: String) extends Origin
  final private[sbt] case class Git(uri: String, ref: String) extends Origin
