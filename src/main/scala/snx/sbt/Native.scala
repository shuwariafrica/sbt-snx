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

import scala.scalanative.build.GC
import scala.scalanative.build.JVMMemoryModelCompliance
import scala.scalanative.build.LTO
import scala.scalanative.build.Mode
import scala.scalanative.build.NativeConfig
import scala.scalanative.build.Sanitizer
import scala.scalanative.build.SourceLevelDebuggingConfig

/** The full native build configuration, a C-conventional surface over the Scala Native `NativeConfig`. See
  * [[Native$ Native]] for the surface; its additive subset is [[Contribution]].
  */
opaque type Native = NativeConfig

/** Surface for [[Native]]: raw option channels, structured link/compile intents, and the typed scalars, each
  * returning a new value. The long tail of `NativeConfig` settings is reached through [[update]].
  */
object Native:

  private[sbt] def apply(config: NativeConfig): Native = config

  extension (self: Native)
    private[sbt] def config: NativeConfig = self

    /** Apply an arbitrary `NativeConfig` transform - the escape hatch for settings without a dedicated verb. */
    def update(transform: NativeConfig => NativeConfig): Native = transform(self)

    def linkOptions(options: String*): Native = self.update(c => c.withLinkingOptions(c.linkingOptions ++ options))

    /** Append options for compiling every native source. */
    def compileOptions(options: String*): Native = self.update(c => c.withCompileOptions(c.compileOptions ++ options))

    /** Append options for compiling C sources. */
    def cOptions(options: String*): Native = self.update(c => c.withCOptions(c.cOptions ++ options))

    /** Append options for compiling C++ sources. */
    def cppOptions(options: String*): Native = self.update(c => c.withCppOptions(c.cppOptions ++ options))

    /** Link the named libraries (`-l<name>`). */
    def library(names: String*): Native = self.update(c => c.withLinkingOptions(c.linkingOptions ++ names.map("-l" + _)))

    /** Add header search directories (`-I<dir>`). */
    def include(directories: String*): Native =
      self.update(c => c.withCompileOptions(c.compileOptions ++ directories.map("-I" + _)))

    /** Add preprocessor definitions (`-D<definition>`). */
    def define(definitions: String*): Native =
      self.update(c => c.withCompileOptions(c.compileOptions ++ definitions.map("-D" + _)))

    def mode(value: Mode): Native = self.update(_.withMode(value))
    def gc(value: GC): Native = self.update(_.withGC(value))
    def lto(value: LTO): Native = self.update(_.withLTO(value))
    def optimize(value: Boolean): Native = self.update(_.withOptimize(value))
    def sanitizer(value: Sanitizer): Native = self.update(_.withSanitizer(value))
    def multithreading(value: Boolean): Native = self.update(_.withMultithreading(value))
    def embedResources(value: Boolean): Native = self.update(_.withEmbedResources(value))

    /** Set the final-field compliance with the Java Memory Model (`None`/`Relaxed`/`Strict`). */
    def finalFields(value: JVMMemoryModelCompliance): Native =
      self.update(config => config.withSemanticsConfig(semantics => semantics.withFinalFields(value)))

    /** Emit source-level debugging information when enabled, or none when disabled. */
    def debugSymbols(value: Boolean): Native =
      val config = if value then SourceLevelDebuggingConfig.enabled else SourceLevelDebuggingConfig.disabled
      self.update(_.withSourceLevelDebuggingConfig(config))

    /** Set a link-time resolved property to a typed value. */
    def linktimeProperty(name: String, value: Boolean): Native = property(name, value)
    def linktimeProperty(name: String, value: Int): Native = property(name, value)
    def linktimeProperty(name: String, value: Long): Native = property(name, value)
    def linktimeProperty(name: String, value: Double): Native = property(name, value)
    def linktimeProperty(name: String, value: String): Native = property(name, value)

    private def property(name: String, value: Boolean | Int | Long | Double | String): Native =
      self.update(config => config.withLinktimeProperties(properties => properties + (name -> value)))
  end extension

end Native
