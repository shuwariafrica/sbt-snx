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

import sbt.librarymanagement.Configuration
import sbt.librarymanagement.ModuleID

import scala.annotation.targetName

/** Marker for `moduleID % NativeClassifier`: resolve the dependency under the build's OS/arch classifier. */
object NativeClassifier

/** A managed native dependency: a `ModuleID` superset carrying whether to resolve under the build's OS/arch classifier
  * (`% NativeClassifier`). The `ModuleID` builders are forwarded; a `ModuleID` lifts into one through the conversions in
  * [[SNXImports$ SNXImports]].
  */
final case class NativeDependency private[sbt] (module: ModuleID, classified: Boolean) derives CanEqual:

  /** Resolve under the build's OS/arch classifier. */
  @targetName("classified")
  def %(marker: NativeClassifier.type): NativeDependency = copy(classified = true)

  /** Restrict to a configuration (for example `Test`). */
  @targetName("configuration")
  def %(configuration: Configuration): NativeDependency = copy(module = module.withConfigurations(Some(configuration.name)))

  /** Restrict to the named configurations. */
  @targetName("configurations")
  def %(configurations: String): NativeDependency = copy(module = module.withConfigurations(Some(configurations)))

  def exclude(org: String, name: String): NativeDependency = copy(module = module.exclude(org, name))
  def intransitive(): NativeDependency = copy(module = module.intransitive())
  def changing(): NativeDependency = copy(module = module.changing())
  def force(): NativeDependency = copy(module = module.force())
end NativeDependency
