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

import java.nio.file.Files

class NirProductsSuite extends munit.FunSuite:

  private val directory = Seq(
    "Greeting.class",
    "Greeting.nir",
    "Greeting$.nir",
    "Greeting$$Lambda$1.nir",
    "Greeting$$Lambda$12.nir",
    "Greeting$$$Lambda$2.nir",
    "Greeting$scalanative$ReflectivelyInstantiate$.nir",
    "Greeting$$scalanative$ReflectivelyInstantiate$.nir",
    "Greeting$Inner.nir",
    "Greeting$Inner$$Lambda$1.nir",
    "GreetingCard.nir",
    "GreetingCard$$Lambda$1.nir",
    "Greeting$$Lambda$.nir",
    "Greeting$$Lambda$1x.nir",
    "Greeting$$Lambda$1.class"
  )

  test("a class owns its own NIR, its lambdas and its reflective loader, and nothing a sibling class owns"):
    val expected = Map(
      "Greeting" -> Seq(
        "Greeting.nir",
        "Greeting$$Lambda$1.nir",
        "Greeting$$Lambda$12.nir",
        "Greeting$scalanative$ReflectivelyInstantiate$.nir"),
      "Greeting$" -> Seq("Greeting$.nir", "Greeting$$$Lambda$2.nir", "Greeting$$scalanative$ReflectivelyInstantiate$.nir"),
      "Greeting$Inner" -> Seq("Greeting$Inner.nir", "Greeting$Inner$$Lambda$1.nir"),
      "GreetingCard" -> Seq("GreetingCard.nir", "GreetingCard$$Lambda$1.nir")
    )
    expected.foreach((name, files) => assertEquals(NirProducts.associated(name, directory).sorted, files.sorted, name))

  test("every generated NIR file in the directory has exactly one owner among its classes"):
    val classes = Seq("Greeting", "Greeting$", "Greeting$Inner", "GreetingCard")
    val generated = directory
      .filter(file => file.contains("$$Lambda$") || file.contains("ReflectivelyInstantiate"))
      .filterNot(file => file.endsWith(".class") || file == "Greeting$$Lambda$.nir" || file == "Greeting$$Lambda$1x.nir")
    generated.foreach: file =>
      val owners = classes.filter(name => NirProducts.associated(name, directory).contains(file))
      assertEquals(owners.size, 1, s"$file is owned by ${owners.mkString(", ")}")

  test("associatedFiles resolves the class's NIR files beside it, and none for a non-class file"):
    val dir = Files.createTempDirectory("snx-nir").nn
    try
      directory.foreach(name => Files.createFile(dir.resolve(name)))
      val associated = NirProducts.associatedFiles(dir.resolve("Greeting.class").nn).map(_.getFileName.nn.toString).sorted
      assertEquals(associated.toSeq, NirProducts.associated("Greeting", directory).sorted)
      assertEquals(NirProducts.associatedFiles(dir.resolve("Greeting.tasty").nn).toSeq, Seq.empty)
    finally sbt.io.IO.delete(dir.toFile.nn)

  test("a class in a directory that no longer exists still names its own NIR file"):
    val gone = Files.createTempDirectory("snx-nir").nn
    sbt.io.IO.delete(gone.toFile.nn)
    assertEquals(NirProducts.associatedFiles(gone.resolve("Greeting.class").nn).toSeq, Seq(gone.resolve("Greeting.nir")))
end NirProductsSuite
