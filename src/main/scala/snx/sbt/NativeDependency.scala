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

/** A native dependency: a `ModuleID`, whether to inject an OS/arch classifier, and the per-platform additive options it
  * contributes. See [[NativeDependency$ NativeDependency]].
  */
final case class NativeDependency(
  module: ModuleID,
  classified: Boolean,
  nativeOptions: PartialFunction[NativePlatform, NativeDependency.Options]
):

  /** Attach per-platform linker flags; unmatched platforms contribute none. */
  infix def linking(flags: PartialFunction[NativePlatform, Seq[String]]): NativeDependency =
    copy(nativeOptions = flags.andThen(seq => NativeDependency.Options(seq, Nil, Nil, Nil)))

  /** Attach the full per-platform additive options. */
  infix def options(bundle: PartialFunction[NativePlatform, NativeDependency.Options]): NativeDependency =
    copy(nativeOptions = bundle)

  /** Resolve as ordinary NIR without an OS/arch classifier, keeping any options. */
  def plain: NativeDependency = copy(classified = false)

/** Factories for [[NativeDependency]] and its additive [[NativeDependency.Options$ Options]]. */
object NativeDependency:

  given CanEqual[NativeDependency, NativeDependency] = CanEqual.derived

  def apply(module: ModuleID): NativeDependency = NativeDependency(module, true, PartialFunction.empty)

  def apply(module: ModuleID, classified: Boolean): NativeDependency =
    NativeDependency(module, classified, PartialFunction.empty)

  /** Additive native options a dependency contributes for a platform: linker flags and compile options (`compile` for
    * all sources, `c`/`cpp` for C/C++ only). See [[NativeDependency.Options$ Options]] for the empty value and channel
    * builders.
    */
  final case class Options(linking: Seq[String], compile: Seq[String], c: Seq[String], cpp: Seq[String])

  /** Empty value and channel builders for [[NativeDependency.Options Options]]. */
  object Options:

    given CanEqual[Options, Options] = CanEqual.derived

    /** Contributes nothing - the base for the channel builders. */
    val empty: Options = Options(Nil, Nil, Nil, Nil)

    extension (options: Options)
      /** Append linker flags. */
      def withLinking(flags: String*): Options = options.copy(linking = options.linking ++ flags)

      /** Append compile options applied to all native sources. */
      def withCompile(flags: String*): Options = options.copy(compile = options.compile ++ flags)

      /** Append C-only compile options. */
      def withC(flags: String*): Options = options.copy(c = options.c ++ flags)

      /** Append C++-only compile options. */
      def withCpp(flags: String*): Options = options.copy(cpp = options.cpp ++ flags)

  extension (dependency: NativeDependency)
    private[sbt] def moduleID(target: TargetPlatform): ModuleID =
      if dependency.classified then dependency.module.classifier(target.classifier) else dependency.module

    private[sbt] def optionsFor(platform: NativePlatform): Options =
      dependency.nativeOptions.applyOrElse(platform, (_: NativePlatform) => Options.empty)
end NativeDependency
