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

/** Operating-system axis of a native [[TargetPlatform]], carrying the classifier token it renders to. See [[OS$ OS]] to
  * parse an identifier.
  */
enum OS(val token: String):
  case Linux extends OS("linux")
  case Osx extends OS("osx")
  case Windows extends OS("windows")

/** Parser for [[OS]]. */
object OS:

  given CanEqual[OS, OS] = CanEqual.derived

  /** Parse a host `os.name` or a Scala Native target-triple operating-system component into a supported [[OS]].
    *
    * @throws UnsupportedTargetException
    *   if `value` is not [[Linux]], [[Osx]], or [[Windows]].
    */
  def parse(value: String): OS =
    val n = normalise(value)
    if n.startsWith("linux") then Linux
    else if n.startsWith("mac") || n.startsWith("osx") || n.startsWith("darwin") then Osx
    else if n.startsWith("windows") then Windows
    else throw UnsupportedTargetException(s"Unsupported operating system: '$value'") // scalafix:ok DisableSyntax.throw
