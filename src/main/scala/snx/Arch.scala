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

/** Architecture axis of a native [[TargetPlatform]], carrying the classifier token it renders to. See [[Arch$ Arch]] to
  * parse an identifier.
  */
enum Arch(val token: String):
  case X86_64 extends Arch("x86_64")
  case Aarch64 extends Arch("aarch_64")

/** Parser for [[Arch]]. */
object Arch:

  given CanEqual[Arch, Arch] = CanEqual.derived

  /** Parse a host `os.arch` or a Scala Native target-triple architecture component into a supported [[Arch]].
    *
    * @throws UnsupportedTargetException
    *   if `value` is not [[X86_64]] or [[Aarch64]].
    */
  def parse(value: String): Arch =
    normalise(value) match
      case v if v.matches("^(x8664|amd64|ia32e|em64t|x64)$") => X86_64
      case "aarch64" | "arm64"                               => Aarch64
      case _ => throw UnsupportedTargetException(s"Unsupported architecture: '$value'") // scalafix:ok DisableSyntax.throw
