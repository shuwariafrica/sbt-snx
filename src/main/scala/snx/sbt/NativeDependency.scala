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

import snx.NativeRuntime

/** Marker for `moduleID % NativeClassifier`: resolve the dependency under the build's OS/arch classifier. */
object NativeClassifier

/** A managed native dependency: a fluent `ModuleID` superset that additionally carries whether to resolve under the
  * build's OS/arch classifier (`% NativeClassifier`) and the per-platform link [[Usage]] requirements it contributes
  * (`options`). The requirements supplement an under-declaring dependency - one that ships no `native.json` of its
  * own - so they fold into this project's link AND propagate into this project's published descriptor, reaching every
  * downstream consumer. The dependency builders are forwarded, each returning a [[NativeDependency]]; a `ModuleID`
  * lifts into one through the conversions in [[SNXImports$ SNXImports]]. See [[NativeDependency$ NativeDependency]].
  */
final case class NativeDependency private[sbt] (
  module: ModuleID,
  classified: Boolean,
  requirements: PartialFunction[NativeRuntime, Usage]
) derives CanEqual:

  /** Resolve under the build's OS/arch classifier. */
  @targetName("classified")
  def %(marker: NativeClassifier.type): NativeDependency = copy(classified = true)

  /** Restrict to a configuration (for example `Test`). */
  @targetName("configuration")
  def %(configuration: Configuration): NativeDependency = copy(module = module.withConfigurations(Some(configuration.name)))

  /** Restrict to the named configurations. */
  @targetName("configurations")
  def %(configurations: String): NativeDependency = copy(module = module.withConfigurations(Some(configurations)))

  /** Attach the per-platform link requirements this dependency needs but does not declare itself; platforms the
    * partial function does not match contribute nothing.
    */
  infix def options(requirements: PartialFunction[NativeRuntime, Usage]): NativeDependency = copy(requirements = requirements)

  def exclude(org: String, name: String): NativeDependency = copy(module = module.exclude(org, name))
  def intransitive(): NativeDependency = copy(module = module.intransitive())
  def changing(): NativeDependency = copy(module = module.changing())
  def force(): NativeDependency = copy(module = module.force())
end NativeDependency

/** Factory for [[NativeDependency]]. */
object NativeDependency:

  private[sbt] def apply(module: ModuleID, classified: Boolean): NativeDependency =
    NativeDependency(module, classified, PartialFunction.empty)
