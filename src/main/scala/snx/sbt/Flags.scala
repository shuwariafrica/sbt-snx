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

import scala.annotation.targetName

/** Native link requirements other than libraries: preprocessor defines, raw link flags, and a multithreading
  * requirement. See [[Flags$ Flags]].
  */
final case class Flags(defines: Seq[String], linkFlags: Seq[String], multithreaded: Boolean) derives CanEqual:

  /** Combine two flag sets. */
  @targetName("combine") def ++(that: Flags): Flags =
    Flags(defines ++ that.defines, linkFlags ++ that.linkFlags, multithreaded || that.multithreaded)

/** Constructors and the empty value for [[Flags]], composed with `++`. */
object Flags:

  /** The empty flag set. */
  def apply(): Flags = empty

  /** The empty flag set. */
  val empty: Flags = Flags(Nil, Nil, false)

  /** Preprocessor defines (`-D<name>`) for the consuming native compile. */
  def defines(name: String*): Flags = empty.copy(defines = name.toSeq)

  /** Raw linker flags. */
  def linkFlags(flag: String*): Flags = empty.copy(linkFlags = flag.toSeq)

  /** System libraries to link as raw `-l<name>` flags - a deliberate escape hatch for a base library never rebound or
    * provisioned (`m`, `dl`, `pthread`). These bypass the name-keyed rebind and de-duplication of `SNX.libraries`; a
    * library a consumer might provision or link statically belongs in `SNX.libraries` (a `NativeLibrary`), not here.
    */
  def libraries(name: String*): Flags = empty.copy(linkFlags = name.map("-l" + _))

  /** Requires the consumer to link with multithreading. */
  val multithreaded: Flags = empty.copy(multithreaded = true)
end Flags
