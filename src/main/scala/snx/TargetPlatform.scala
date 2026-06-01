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

import java.util.Locale

/** A native target: an [[Os]] paired with an [[Arch]]. See [[TargetPlatform$ TargetPlatform]] for construction,
  * parsing, and the `classifier` rendering.
  */
final case class TargetPlatform(os: Os, arch: Arch)

/** Factory, parser, and `classifier` rendering for [[TargetPlatform]]. */
object TargetPlatform:

  given CanEqual[TargetPlatform, TargetPlatform] = CanEqual.derived

  /** Parse operating-system and architecture identifiers (host `os.name` / `os.arch` or Scala Native target-triple
    * components) into a [[TargetPlatform]].
    *
    * @throws UnsupportedTargetException
    *   if either component is unsupported.
    */
  def parse(os: String, arch: String): TargetPlatform = TargetPlatform(Os.parse(os), Arch.parse(arch))

  extension (target: TargetPlatform)
    /** The classifier `<os>-<arch>` (for example `osx-aarch_64`). */
    def classifier: String = s"${target.os.token}-${target.arch.token}"

private[snx] def normalise(value: String): String =
  value.toLowerCase(Locale.US).nn.replaceAll("[^a-z0-9]+", "").nn
