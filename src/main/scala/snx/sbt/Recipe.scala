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

import java.io.File

/** How a `command` build makes its library: the [[Step]]s the plugin runs, in order, and the files they leave to link.
  * The build cache keys on the recipe itself, so a change to any step, argument, environment value or output is a
  * cache miss, wherever the code that computed it lives. Each setter returns a new value: [[libraries]] and
  * [[includes]] add to the outputs named before them, so helpers of one recipe each contribute theirs.
  */
final class Recipe private (
  private[sbt] val steps: Seq[Step],
  private[sbt] val libraryFiles: Seq[File | Matching],
  private[sbt] val includeDirectories: Seq[File | Matching]):

  /** The built libraries to link, in this order - static archives, objects, or shared libraries per the build's
    * [[Linkage]] - each under the staging directory; a [[Matching]] contributes the files it matches once the steps
    * have run, in its own order. Two or more static archives link as one group on Linux and MinGW (`--start-group` ...
    * `--end-group`), so references between them resolve whatever their order; ld64 and MSVC resolve archives without
    * regard to order. Order still decides which of two archives defining one symbol supplies it. A library a step did
    * not write, or a `Matching` that matches nothing, fails the build. Each call adds to the libraries named before it.
    */
  def libraries(outputs: (File | Matching)*): Recipe = new Recipe(steps, libraryFiles ++ outputs, includeDirectories)

  /** The header directories to expose (`-I`), each under the staging directory; a [[Matching]] contributes the
    * directories it matches once the steps have run. Each call adds to the directories named before it.
    */
  def includes(directories: (File | Matching)*): Recipe = new Recipe(steps, libraryFiles, includeDirectories ++ directories)
end Recipe

/** Factory for [[Recipe]]. */
object Recipe:

  /** A recipe running `steps` in order, yielding no libraries until [[Recipe.libraries]] names them. */
  def apply(steps: Step*): Recipe = new Recipe(steps, Seq.empty, Seq.empty)

/** One argument of a [[Step.Run]] made of text and files joined into one token, as a tool reads
  * `CFLAGS=-include <header>`, `-I<directory>` or `@<response file>`: written `arg"-I$include"` with the interpolator
  * [[SNXImports]] provides. Each part is keyed as a lone argument of its kind would be: a `File` part by its content,
  * a `String` part as its text.
  */
final class Argument private[sbt] (private[sbt] val parts: Seq[Argument.Part]):
  // An Argument in `s"..."` text renders as this marker, which the key refuses with ArgumentInText: it has no textual
  // value of its own, and `arg"..."` is the form that joins it.
  override def toString: String = Argument.marker

/** Parts of an [[Argument]]. */
object Argument:
  private[sbt] enum Part derives CanEqual:
    case Text(value: String)
    case Path(file: File)
    case Jobs

  private[sbt] val marker: String = "\u0000snx.Argument\u0000"

  // The job count: the host's processors when the step runs, keyed as `${jobs}`, so caches agree across hosts.
  private[sbt] val jobs: Argument = new Argument(Seq(Part.Jobs))

  private[sbt] def interpolate(context: StringContext, values: Seq[String | File | Argument]): Argument =
    val literals = context.parts.map(Part.Text(_))
    val inserted = values.map {
      case text: String       => Seq(Part.Text(text))
      case file: File         => Seq(Part.Path(file))
      case argument: Argument => argument.parts
    }
    new Argument(literals.head +: inserted.zip(literals.tail).flatMap((value, literal) => value :+ literal))
end Argument

/** The files or directories under `directory` whose path relative to it matches `pattern`, listed when they are used,
  * in the order of those relative paths. In the pattern `*` matches within one path segment, `**` across segments and
  * `?` one character; `/` separates segments on every platform, and letters match by case on every platform, so one
  * pattern matches alike on every host. The walk starts below the pattern's literal leading segments and enters no
  * hidden directory beneath them, nor sbt's `target` directories beneath them unless the walk starts inside one, so a
  * pattern over the project matches no build output; links are followed, a cycle entered once. As a [[Step.Run]]
  * argument it expands to the matches when the step starts; as a [[Recipe.libraries]] or [[Recipe.includes]] output, to
  * those the steps left. Keyed by its directory and pattern, and outside this library's source and staging also by the
  * content of what it matches. A walk that would start in `target` above this library's own staging fails the build
  * with [[snx.SNXError$.InputHoldsStaging InputHoldsStaging]].
  */
final class Matching private (private[sbt] val directory: File, private[sbt] val pattern: String)

/** Factory for [[Matching]]. */
object Matching:

  /** The paths under `directory` that `pattern` matches. */
  def apply(directory: File, pattern: String): Matching = new Matching(directory, pattern)

/** One action of a [[Recipe]]. A path a step writes lies under the build's staging directory. */
sealed trait Step

/** The [[Step]] factories. */
object Step:

  /** A program and its arguments, run in the staging directory unless [[Run.in]] names another, with the inherited
    * environment plus any [[Run.env]] values. A program named without a directory is found on the `PATH` (and on
    * Windows the `PATHEXT`) the step runs with, its own `env` over the inherited one, and that file is keyed by its
    * content; the step runs that file. A program outside the build is hashed again when its size or modification time
    * changes, or on Linux and macOS its change time or file key; every other file the recipe names is hashed on every
    * build.
    *
    * A path written in text - a `String` argument, a `String` part of an [[Argument]], a `String` environment value -
    * is keyed as text, as written, with this library's source and staging paths and the link's compilers in it replaced
    * by tokens; what it names is not read. Pass a file or directory whose content the build depends on as a `File`, an
    * `arg` part (`arg"-include$header"`, not `s"-include$header"`) or a [[Matching]], which are keyed by content
    * wherever they are, a file under sbt's `target` (a generated header, another library's staging) included, and this
    * library's own source and staging by their tokens. A directory is keyed by the files beneath it, links followed,
    * leaving out hidden directories and sbt's `target` directories beneath it unless it lies inside one, so passing the
    * project directory rebuilds the library whenever any of its sources change. A directory in `target` that holds this
    * library's own staging - `target` itself - fails the build with
    * [[snx.SNXError$.InputHoldsStaging InputHoldsStaging]], since its content is what the build writes: pass the
    * directory beneath it that the build reads. An unreadable file passed, or an unreadable directory beneath one,
    * fails the build with [[snx.SNXError$.InputUnreadable InputUnreadable]]. Not read: what a file refers to inside its
    * content - a response file's (`@args.rsp`) flags, a header's `#include`s, a Makefile's `include`s - nor what a
    * program finds by a path it computes itself; pass the files they name as well to key them.
    */
  final class Run private[sbt] (
    private[sbt] val program: String | File,
    private[sbt] val arguments: Seq[String | File | Argument | Matching],
    private[sbt] val directory: Option[File],
    private[sbt] val environment: Seq[(String, String | File | Argument)])
      extends Step:

    /** Run in `directory`, under the staging directory, created if absent. */
    def in(directory: File): Run = new Run(program, arguments, Some(directory), environment)

    /** Environment variables set for this program, over the inherited ones. Each call adds to those set before it, and
      * a variable set twice takes its last value.
      */
    def env(variables: (String, String | File | Argument)*): Run =
      new Run(program, arguments, directory, environment ++ variables)
  end Run

  /** A file or directory copied to `to`, under the staging directory. A directory is merged into `to`, replacing the
    * files of the same path that `to` already holds. A `from` under the source or the staging directory is keyed by
    * its path there; any other `from` by its content.
    */
  final class Copy private[sbt] (private[sbt] val from: File, private[sbt] val to: File) extends Step

  /** Run `program` with `arguments`; [[Run]] says what of each is keyed, and that a path written in text is keyed as
    * text.
    */
  def run(program: String | File, arguments: (String | File | Argument | Matching)*): Run =
    new Run(program, arguments, None, Seq.empty)

  /** Copy `from` to `to`. */
  def copy(from: File, to: File): Copy = new Copy(from, to)
end Step
