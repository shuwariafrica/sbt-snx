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

/** The built output of a [[NativeSource]]: the archives to link and the directories to expose on the include path.
  * See [[NativeArtefacts$ NativeArtefacts]].
  */
final case class NativeArtefacts(archives: Seq[File], includes: Seq[File])

/** Equality instance and the empty value for [[NativeArtefacts]]. */
object NativeArtefacts:
  given CanEqual[NativeArtefacts, NativeArtefacts] = CanEqual.derived

  /** Nothing built - for a link-only source. */
  val empty: NativeArtefacts = NativeArtefacts(Nil, Nil)
