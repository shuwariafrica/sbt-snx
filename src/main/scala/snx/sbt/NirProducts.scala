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

import xsbti.compile.AuxiliaryClassFiles

import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters.*
import scala.util.Using

// Zinc deletes, and on a failed compile restores, a class's associated files with the class. The Native compiler's NIR
// must follow its class through both, or a deleted source's definitions stay in the class directory and keep linking.
private[sbt] object NirProducts extends AuxiliaryClassFiles:

  override def associatedFiles(classFile: Path): Array[Path] =
    val file = classFile.getFileName.nn.toString
    if !file.endsWith(".class") then Array.empty
    else
      val directory = classFile.getParent.nn
      val siblings =
        if !Files.isDirectory(directory) then Nil
        else Using.resource(Files.list(directory).nn)(_.iterator.nn.asScala.map(_.getFileName.nn.toString).toList)
      associated(file.stripSuffix(".class"), siblings).map(directory.resolve).toArray

  // The NIR files belonging to the class named `className`, among the file names in its directory.
  private[sbt] def associated(className: String, siblings: Seq[String]): Seq[String] =
    (className + ".nir") +: siblings.filter(sibling => sibling.startsWith(className) && generated(sibling.substring(className.length).nn))

  // What the Native compiler appends to a class's name for the NIR it writes with no JVM class of its own: a lambda
  // (`$$Lambda$<n>`) or the reflective-instantiation loader.
  private def generated(suffix: String): Boolean =
    suffix == "$scalanative$ReflectivelyInstantiate$.nir" || {
      val counter = suffix.stripPrefix("$$Lambda$").stripSuffix(".nir")
      suffix.startsWith("$$Lambda$") && suffix.endsWith(".nir") && counter.nonEmpty && counter.forall(_.isDigit)
    }
