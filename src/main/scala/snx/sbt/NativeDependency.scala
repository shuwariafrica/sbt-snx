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

import sbt.librarymanagement.ModuleID

import snx.NativePlatform
import snx.TargetPlatform

/** A managed native dependency: a `ModuleID`, whether to inject an OS/arch classifier, and the per-platform additive
  * [[NativeOptions]] it contributes. Its source-built counterpart is [[NativeSource]]. See
  * [[NativeDependency$ NativeDependency]].
  */
final case class NativeDependency(module: ModuleID, classified: Boolean, nativeOptions: PartialFunction[NativePlatform, NativeOptions]):

  /** Attach the per-platform additive options; unmatched platforms contribute none. */
  infix def options(bundle: PartialFunction[NativePlatform, NativeOptions]): NativeDependency =
    copy(nativeOptions = bundle)

  /** Resolve as ordinary NIR without an OS/arch classifier, keeping any options. */
  def plain: NativeDependency = copy(classified = false)

  private[sbt] def moduleID(target: TargetPlatform): ModuleID =
    if classified then module.classifier(target.classifier) else module

  private[sbt] def optionsFor(platform: NativePlatform): NativeOptions =
    nativeOptions.applyOrElse(platform, (_: NativePlatform) => NativeOptions.empty)

/** Factories for [[NativeDependency]]. */
object NativeDependency:

  given CanEqual[NativeDependency, NativeDependency] = CanEqual.derived

  def apply(module: ModuleID): NativeDependency = NativeDependency(module, true, PartialFunction.empty)

  def apply(module: ModuleID, classified: Boolean): NativeDependency =
    NativeDependency(module, classified, PartialFunction.empty)
