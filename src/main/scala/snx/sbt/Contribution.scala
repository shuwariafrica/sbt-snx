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

import scala.scalanative.build.NativeConfig

/** An additive native contribution: the four option channels and the structured link/compile intents, with no
  * scalar settings. Folded into a [[Native]] by appending its channels only. See [[Contribution$ Contribution]].
  */
opaque type Contribution = NativeConfig

/** Surface and channels-only fold for [[Contribution]]. */
object Contribution:

  private[sbt] def empty: Contribution = NativeConfig.empty

  /** Append `contribution`'s four option channels onto `base`, leaving every scalar untouched. */
  private[sbt] def merge(base: NativeConfig, contribution: Contribution): NativeConfig =
    base
      .withLinkingOptions(base.linkingOptions ++ contribution.linkingOptions)
      .withCompileOptions(base.compileOptions ++ contribution.compileOptions)
      .withCOptions(base.cOptions ++ contribution.cOptions)
      .withCppOptions(base.cppOptions ++ contribution.cppOptions)

  extension (self: Contribution)
    private def update(transform: NativeConfig => NativeConfig): Contribution = transform(self)

    def linkOptions(options: String*): Contribution = self.update(c => c.withLinkingOptions(c.linkingOptions ++ options))

    /** Append options for compiling every native source. */
    def compileOptions(options: String*): Contribution = self.update(c => c.withCompileOptions(c.compileOptions ++ options))

    /** Append options for compiling C sources. */
    def cOptions(options: String*): Contribution = self.update(c => c.withCOptions(c.cOptions ++ options))

    /** Append options for compiling C++ sources. */
    def cppOptions(options: String*): Contribution = self.update(c => c.withCppOptions(c.cppOptions ++ options))

    /** Link the named libraries (`-l<name>`). */
    def library(names: String*): Contribution =
      self.update(c => c.withLinkingOptions(c.linkingOptions ++ names.map("-l" + _)))

    /** Add header search directories (`-I<dir>`). */
    def include(directories: String*): Contribution =
      self.update(c => c.withCompileOptions(c.compileOptions ++ directories.map("-I" + _)))

    /** Add preprocessor definitions (`-D<definition>`). */
    def define(definitions: String*): Contribution =
      self.update(c => c.withCompileOptions(c.compileOptions ++ definitions.map("-D" + _)))
  end extension

end Contribution
