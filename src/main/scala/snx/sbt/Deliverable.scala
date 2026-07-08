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

/** The kind of artefact a project produces, and the publish-versus-link discriminant: a platform-independent
  * [[Deliverable.NIR NIR]] (published as a jar), a native [[Deliverable.Library Library]] in one of its two emit forms,
  * or an [[Deliverable.Executable Executable]]. The deliverable fully describes the artefact; how each native library it
  * links is bound is a separate per-library concern ([[NativeLibrary]]), and a static C runtime is a separate opt-in
  * ([[SNXImports.SNX.staticRuntime]]).
  */
sealed trait Deliverable derives CanEqual

/** The [[Deliverable]] variants. */
object Deliverable:

  /** Compiled NIR, published as a platform-independent jar and linked by the consumer. */
  case object NIR extends Deliverable

  /** A linked native executable. */
  case object Executable extends Deliverable

  /** A native library artefact - the parent of its two emit forms. */
  sealed trait Library extends Deliverable

  /** The [[Library]] emit forms. */
  object Library:

    /** A static library archive (`.a`/`.lib`). */
    case object Static extends Library

    /** A shared library (`.so`/`.dylib`/`.dll`). */
    case object Shared extends Library
end Deliverable
