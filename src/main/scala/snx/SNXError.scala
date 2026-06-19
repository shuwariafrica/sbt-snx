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

  /** A vendored CMake build that could not run: a missing `CMakeLists.txt`, absent `install` rules, or a failed
    * `cmake` invocation.
    */
  final case class CMakeBuildFailed(message: String) extends SNXError(message)

  /** A vendored backend output (archive or header directory) that lies outside the build staging directory, so the
    * build cache cannot capture it.
    */
  final case class OutputOutsideStaging(message: String) extends SNXError(message)

  /** A static executable requested on a toolchain that cannot link one - musl or MSVC is required. */
  final case class StaticLinkingUnsupported(message: String) extends SNXError(message)

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
end SNXError
