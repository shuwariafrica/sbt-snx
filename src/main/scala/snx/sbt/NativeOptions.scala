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

/** The additive native options a [[NativeDependency]] or [[NativeSource]] contributes for a platform: linker flags and
  * compile options (`compile` for all native sources, `c`/`cpp` for C and C++ only). See [[NativeOptions$ NativeOptions]]
  * for its identity and constructors.
  */
final case class NativeOptions(linking: Seq[String], compile: Seq[String], c: Seq[String], cpp: Seq[String]):

  /** Append linker flags. */
  def withLinking(flags: String*): NativeOptions = copy(linking = linking ++ flags)

  /** Append compile options applied to all native sources. */
  def withCompile(flags: String*): NativeOptions = copy(compile = compile ++ flags)

  /** Append C-only compile options. */
  def withC(flags: String*): NativeOptions = copy(c = c ++ flags)

  /** Append C++-only compile options. */
  def withCpp(flags: String*): NativeOptions = copy(cpp = cpp ++ flags)

  /** Concatenate two option sets channel by channel; the empty value is the identity. */
  @targetName("concat")
  def ++(other: NativeOptions): NativeOptions =
    NativeOptions(linking ++ other.linking, compile ++ other.compile, c ++ other.c, cpp ++ other.cpp)

/** Identity, equality, and the no-argument constructor for [[NativeOptions]]. */
object NativeOptions:
  given CanEqual[NativeOptions, NativeOptions] = CanEqual.derived

  /** No options - the identity for `++` and the base the channel builders extend. */
  val empty: NativeOptions = NativeOptions(Nil, Nil, Nil, Nil)

  /** An empty bundle; equivalent to [[empty]]. */
  def apply(): NativeOptions = empty
