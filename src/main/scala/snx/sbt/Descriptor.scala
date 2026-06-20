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

import sjsonnew.BasicJsonProtocol.given
import sjsonnew.Builder
import sjsonnew.JsonFormat
import sjsonnew.Unbuilder
import sjsonnew.deserializationError
import sjsonnew.support.scalajson.unsafe.CompactPrinter
import sjsonnew.support.scalajson.unsafe.Converter
import sjsonnew.support.scalajson.unsafe.Parser

import scala.annotation.targetName

import snx.Arch
import snx.NativeRuntime
import snx.OS
import snx.TargetPlatform

/** The publishing coordinate of a native library, identifying the source of a [[Descriptor]]. */
final private[sbt] case class Module(organization: String, name: String, version: String) derives CanEqual

/** A native library's link requirements for one platform pattern: the toolchain-neutral channels and a multithreading
  * requirement. See [[Usage$ Usage]].
  */
final private[sbt] case class Usage(
  libraries: Seq[String],
  frameworks: Seq[String],
  wholeArchive: Seq[String],
  defines: Seq[String],
  linkFlags: Seq[String],
  requiresMultithreading: Boolean
) derives CanEqual:

  /** Whether this declares no requirement. */
  def isEmpty: Boolean = this == Usage.empty

  /** Combine two requirements. */
  @targetName("combine") def ++(that: Usage): Usage =
    Usage(
      libraries ++ that.libraries,
      frameworks ++ that.frameworks,
      wholeArchive ++ that.wholeArchive,
      defines ++ that.defines,
      linkFlags ++ that.linkFlags,
      requiresMultithreading || that.requiresMultithreading
    )

  /** This requirement with each channel de-duplicated, first occurrence kept. */
  def distinct: Usage =
    Usage(libraries.distinct, frameworks.distinct, wholeArchive.distinct, defines.distinct, linkFlags.distinct, requiresMultithreading)
end Usage

/** Constructors and the empty value for [[Usage]], composed with `++`. */
private[sbt] object Usage:

  /** The empty requirement. */
  def apply(): Usage = empty

  /** The empty requirement. */
  val empty: Usage = Usage(Nil, Nil, Nil, Nil, Nil, false)

  /** System libraries to link (`-l<name>`). */
  def libraries(name: String*): Usage = empty.copy(libraries = name.toSeq)

  /** macOS frameworks to link. */
  def frameworks(name: String*): Usage = empty.copy(frameworks = name.toSeq)

  /** Libraries to whole-archive. */
  def wholeArchive(name: String*): Usage = empty.copy(wholeArchive = name.toSeq)

  /** Preprocessor defines (`-D<name>`) for the consumer's C. */
  def defines(name: String*): Usage = empty.copy(defines = name.toSeq)

  /** Raw linker flags. */
  def linkFlags(flag: String*): Usage = empty.copy(linkFlags = flag.toSeq)

  /** Requires multithreading. */
  val multithreaded: Usage = empty.copy(requiresMultithreading = true)
end Usage

/** A native library's per-platform usage descriptor, keyed by platform pattern (`*`, `<os>`, `<os>-<arch>`, or
  * `<os>-<arch>-<env>`). See [[Descriptor$ Descriptor]].
  */
final private[sbt] case class Descriptor(module: Module, usage: Map[String, Usage]) derives CanEqual

/** Codecs, byte-stable rendering, and the producer/consumer logic for [[Descriptor]]. */
private[sbt] object Descriptor:

  /** The descriptor schema version. */
  inline val schemaVersion = 1

  /** The descriptor resource path within a published jar. */
  final val resourcePath = "META-INF/scala-native/native.json"

  private given JsonFormat[Module] = new JsonFormat[Module]:
    def write[J](module: Module, builder: Builder[J]): Unit =
      builder.beginObject()
      builder.addField("organization", module.organization)
      builder.addField("name", module.name)
      builder.addField("version", module.version)
      builder.endObject()
    def read[J](js: Option[J], unbuilder: Unbuilder[J]): Module = js match
      case Some(value) =>
        val _ = unbuilder.beginObject(value)
        val module =
          Module(unbuilder.readField[String]("organization"), unbuilder.readField[String]("name"), unbuilder.readField[String]("version"))
        unbuilder.endObject()
        module
      case None => deserializationError("expected a module object")

  // Empty channels and a false multithreading requirement are omitted, so a minimal usage stays compact and the
  // rendered bytes are stable.
  private given JsonFormat[Usage] = new JsonFormat[Usage]:
    def write[J](usage: Usage, builder: Builder[J]): Unit =
      builder.beginObject()
      if usage.libraries.nonEmpty then builder.addField("libraries", usage.libraries)
      if usage.frameworks.nonEmpty then builder.addField("frameworks", usage.frameworks)
      if usage.wholeArchive.nonEmpty then builder.addField("wholeArchive", usage.wholeArchive)
      if usage.defines.nonEmpty then builder.addField("defines", usage.defines)
      if usage.linkFlags.nonEmpty then builder.addField("linkFlags", usage.linkFlags)
      if usage.requiresMultithreading then builder.addField("requiresMultithreading", usage.requiresMultithreading)
      builder.endObject()
    def read[J](js: Option[J], unbuilder: Unbuilder[J]): Usage = js match
      case Some(value) =>
        val _ = unbuilder.beginObject(value)
        def channel(name: String): Seq[String] = unbuilder.readField[Option[Seq[String]]](name).getOrElse(Nil)
        val usage = Usage(
          channel("libraries"),
          channel("frameworks"),
          channel("wholeArchive"),
          channel("defines"),
          channel("linkFlags"),
          unbuilder.readField[Option[Boolean]]("requiresMultithreading").getOrElse(false)
        )
        unbuilder.endObject()
        usage
      case None => deserializationError("expected a usage object")

  private given JsonFormat[Descriptor] = new JsonFormat[Descriptor]:
    def write[J](descriptor: Descriptor, builder: Builder[J]): Unit =
      builder.beginObject()
      builder.addField("schemaVersion", schemaVersion)
      builder.addField("module", descriptor.module)
      builder.addFieldName("usage")
      builder.beginObject()
      descriptor.usage.toVector.sortBy(_._1).foreach((pattern, usage) => builder.addField(pattern, usage))
      builder.endObject()
      builder.endObject()
    def read[J](js: Option[J], unbuilder: Unbuilder[J]): Descriptor = js match
      case Some(value) =>
        val _ = unbuilder.beginObject(value)
        val module = unbuilder.readField[Module]("module")
        val usage = unbuilder.readField[Option[Map[String, Usage]]]("usage").getOrElse(Map.empty)
        unbuilder.endObject()
        Descriptor(module, usage)
      case None => deserializationError("expected a descriptor object")

  /** Render `descriptor` to byte-stable JSON: fixed field order, patterns sorted, empty channels omitted. */
  def render(descriptor: Descriptor): String = CompactPrinter(Converter.toJsonUnsafe(descriptor))

  /** Parse a descriptor from its JSON text. */
  def parse(text: String): Descriptor = Converter.fromJsonUnsafe[Descriptor](Parser.parseUnsafe(text))

  /** Build the descriptor a library publishes: evaluate `requirements` over the platforms the jar serves (the
    * `target`'s [[snx.ABI ABI]] variants when `classified`, else every platform), de-duplicate, then collapse to the
    * broadest pattern per shared requirement.
    */
  def build(module: Module, classified: Boolean, target: TargetPlatform, requirements: PartialFunction[NativeRuntime, Usage]): Descriptor =
    val runtimes = if classified then NativeRuntime.variants(target) else TargetPlatform.all.flatMap(NativeRuntime.variants)
    val declared = runtimes.flatMap(runtime => requirements.lift(runtime).map(_.distinct).filterNot(_.isEmpty).map(runtime -> _)).toMap
    Descriptor(module, collapse(declared))

  /** Fold the descriptors on a consumer's classpath into the requirement for `runtime`: each resolves
    * most-specific-per-field, combined in dependency order, channels de-duplicated.
    */
  def fold(descriptors: Seq[Descriptor], runtime: NativeRuntime): Usage =
    descriptors.map(descriptor => resolve(descriptor.usage, runtime)).foldLeft(Usage.empty)(_ ++ _).distinct

  // Reduce a per-runtime requirement to the minimal pattern map: a broader pattern (`<os>-<arch>`, `<os>`, or `*`)
  // replaces its children when every child is present with an identical requirement; otherwise children stay at their
  // own pattern. A classified jar holds only one os/arch, so it naturally collapses no further than `<os>-<arch>`.
  private def collapse(usages: Map[NativeRuntime, Usage]): Map[String, Usage] =
    def uniform(values: Seq[Option[Usage]]): Option[Usage] =
      if values.forall(_.isDefined) && values.flatten.distinct.sizeIs == 1 then values.head else None
    val byTarget: Map[TargetPlatform, Option[Usage]] =
      TargetPlatform.all.map(target => target -> uniform(NativeRuntime.variants(target).map(usages.get))).toMap
    val byOS: Map[OS, Option[Usage]] =
      OS.values.toSeq.map(os => os -> uniform(Arch.values.toSeq.map(arch => byTarget(TargetPlatform(os, arch))))).toMap
    uniform(OS.values.toSeq.map(byOS)) match
      case Some(usage) => Map("*" -> usage)
      case None        =>
        OS.values.toSeq.flatMap { os =>
          byOS(os) match
            case Some(usage) => Seq(os.token -> usage)
            case None        =>
              Arch.values.toSeq.flatMap { arch =>
                val target = TargetPlatform(os, arch)
                byTarget(target) match
                  case Some(usage) => Seq(target.classifier -> usage)
                  case None        => NativeRuntime.variants(target).flatMap(runtime => usages.get(runtime).map(runtime.pattern -> _))
              }
        }.toMap

  // The most-specific-per-field requirement a single descriptor's patterns define for `runtime`.
  private def resolve(usage: Map[String, Usage], runtime: NativeRuntime): Usage =
    val matching = usage.toSeq.filter((key, _) => matches(key, runtime)).sortBy((key, _) => -specificity(key))
    def channel(select: Usage => Seq[String]): Seq[String] = matching.map((_, u) => select(u)).find(_.nonEmpty).getOrElse(Nil)
    Usage(
      channel(_.libraries),
      channel(_.frameworks),
      channel(_.wholeArchive),
      channel(_.defines),
      channel(_.linkFlags),
      matching.exists((_, u) => u.requiresMultithreading)
    )

  // A pattern matches a runtime when it is `*` or a component-prefix of the runtime's pattern.
  private def matches(key: String, runtime: NativeRuntime): Boolean =
    key == "*" || runtime.pattern == key || runtime.pattern.startsWith(s"$key-")

  // Component count - higher is more specific; `*` is the least specific.
  private def specificity(key: String): Int = if key == "*" then 0 else key.count(_ == '-') + 1
end Descriptor
