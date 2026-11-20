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
package snx

import scala.util.control.NoStackTrace

/** A build failure raised by sbt-snx; its message is the reported error. See [[SNXError$ SNXError]] for the variants. */
sealed abstract class SNXError(message: String) extends RuntimeException(message), NoStackTrace derives CanEqual:
  final override def toString: String = message

/** The [[SNXError]] variants. */
object SNXError:

  /** An operating system, architecture, or toolchain libc/ABI that sbt-snx does not support. */
  final case class UnsupportedTarget(message: String) extends SNXError(message)

  /** A plugin feature the resolved toolchain cannot provide - for example a build backend it cannot drive. */
  final case class UnsupportedToolchain(message: String) extends SNXError(message)

  /** A vendored CMake build that could not run: a missing `CMakeLists.txt`, absent `install` rules, a failed `cmake`
    * invocation, or an install that left no `install_manifest.txt` to check.
    */
  final case class CMakeBuildFailed(message: String) extends SNXError(message)

  /** A vendored `command` build that could not run: a step that exited non-zero, a program not found or not startable,
    * a copy that failed, or an output the recipe names that its steps did not write.
    */
  final case class CommandBuildFailed(message: String) extends SNXError(message)

  /** A `command` recipe that came out differently once its source was fetched or its staging cleared: it read one of
    * them while it was computed, so the cache key computed before the fetch does not describe the build.
    */
  final case class RecipeChanged(message: String) extends SNXError(message)

  /** A `command` recipe whose function threw while it was computed - before the source is fetched, where reading the
    * source is the likeliest cause.
    */
  final case class RecipeFailed(message: String) extends SNXError(message)

  /** A `command` recipe's text holding an [[snx.sbt.Argument Argument]] interpolated into a `String` (`s"-j${...}"`),
    * which would reach the program as no value of its own; such a token is written with `arg"..."`.
    */
  final case class ArgumentInText(message: String) extends SNXError(message)

  /** A file or directory a `command` recipe names that could not be read to key it - an unreadable file it names, or an
    * unreadable directory beneath one it names or a `Matching` walks - so the build cannot know what it would read.
    */
  final case class InputUnreadable(message: String) extends SNXError(message)

  /** A directory a `command` recipe names that holds the library's own staging inside sbt's `target` - the target
    * itself, or a directory in it above the staging - whose content is what the build writes, so it cannot key the
    * build; the recipe names the directory beneath it that the build reads.
    */
  final case class InputHoldsStaging(message: String) extends SNXError(message)

  /** A vendored Git source that could not be fetched: a `git` invocation that failed against its repository or ref. */
  final case class FetchFailed(message: String) extends SNXError(message)

  /** A vendored build output outside the build staging directory: an archive or header directory a backend returned,
    * which the build cache cannot capture, or a file a CMake install wrote into the host.
    */
  final case class OutputOutsideStaging(message: String) extends SNXError(message)

  /** A vendored CMake project directory that is absolute or reaches outside the vendored source, whose content alone
    * keys the build cache.
    */
  final case class DirectoryOutsideSource(message: String) extends SNXError(message)

  /** A vendored source directory that changed while the build was reading it to compute its cache identity. */
  final case class SourceChanged(message: String) extends SNXError(message)

  /** A static executable requested on a toolchain that cannot link one - musl or MSVC is required. */
  final case class StaticLinkingUnsupported(message: String) extends SNXError(message)

  /** A per-library linkage sbt-snx cannot render - a statically-linked macOS framework, a static system library on a
    * platform with no static system archive, a whole-archive vendored library requested dynamic (whole-archive is a
    * static-archive operation), or a dynamically-linked vendored library on Windows (DLL redistribution is a follow-on).
    */
  final case class UnsupportedLinkage(message: String) extends SNXError(message)

  /** A native library declared to have no system default that no provisioning supplies. */
  final case class UnprovisionedLibrary(message: String) extends SNXError(message)

  /** A native library declared as provisioning an inherited requirement that no resolved descriptor names. */
  final case class UnmatchedLibrary(message: String) extends SNXError(message)

  /** A build target whose operating system contradicts the one the toolchain's target triple names. */
  final case class TargetMismatch(message: String) extends SNXError(message)

  /** A native library the linker could not resolve at the link. */
  final case class UnresolvedLibrary(message: String) extends SNXError(message)

  /** An `Executable` deliverable with no main class to link. */
  final case class MissingMainClass(message: String) extends SNXError(message)

  /** A link requested for a deliverable that is not linked - the `NIR` deliverable publishes a jar. */
  final case class NotLinkable(message: String) extends SNXError(message)

  /** A dependency that requires multithreading the project has disabled. */
  final case class MultithreadingRequired(message: String) extends SNXError(message)

  /** A launched native binary that exited with a non-zero status. */
  final case class RunFailed(message: String) extends SNXError(message)

  /** `runMain` invoked on a Scala Native project - a native binary has a single entry point; use `run`. */
  final case class RunMainUnsupported(message: String) extends SNXError(message)

  /** `Test / fork` set on a Scala Native project - a native test binary must run in-process. */
  final case class TestForkUnsupported(message: String) extends SNXError(message)

  // `error` naming the vendored library it arose in, for a backend whose own messages do not: its message, after the
  // `snx: ` that begins it, opens with the library.
  private[snx] def forLibrary(library: String, error: SNXError): SNXError =
    def named(message: String): String = s"snx: vendored library '$library': ${message.stripPrefix("snx: ")}"
    error match
      case UnsupportedTarget(message)        => UnsupportedTarget(named(message))
      case UnsupportedToolchain(message)     => UnsupportedToolchain(named(message))
      case CMakeBuildFailed(message)         => CMakeBuildFailed(named(message))
      case CommandBuildFailed(message)       => CommandBuildFailed(named(message))
      case RecipeChanged(message)            => RecipeChanged(named(message))
      case RecipeFailed(message)             => RecipeFailed(named(message))
      case ArgumentInText(message)           => ArgumentInText(named(message))
      case InputUnreadable(message)          => InputUnreadable(named(message))
      case InputHoldsStaging(message)        => InputHoldsStaging(named(message))
      case FetchFailed(message)              => FetchFailed(named(message))
      case OutputOutsideStaging(message)     => OutputOutsideStaging(named(message))
      case DirectoryOutsideSource(message)   => DirectoryOutsideSource(named(message))
      case SourceChanged(message)            => SourceChanged(named(message))
      case StaticLinkingUnsupported(message) => StaticLinkingUnsupported(named(message))
      case UnsupportedLinkage(message)       => UnsupportedLinkage(named(message))
      case UnprovisionedLibrary(message)     => UnprovisionedLibrary(named(message))
      case UnmatchedLibrary(message)         => UnmatchedLibrary(named(message))
      case TargetMismatch(message)           => TargetMismatch(named(message))
      case UnresolvedLibrary(message)        => UnresolvedLibrary(named(message))
      case MissingMainClass(message)         => MissingMainClass(named(message))
      case NotLinkable(message)              => NotLinkable(named(message))
      case MultithreadingRequired(message)   => MultithreadingRequired(named(message))
      case RunFailed(message)                => RunFailed(named(message))
      case RunMainUnsupported(message)       => RunMainUnsupported(named(message))
      case TestForkUnsupported(message)      => TestForkUnsupported(named(message))
    end match
  end forLibrary
end SNXError
