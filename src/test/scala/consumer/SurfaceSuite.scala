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
package consumer

import scala.compiletime.testing.typeCheckErrors

import snx.sbt.SNXImports.*

// What a build definition outside the plugin's package can and cannot write against the `command` surface, as a user's
// build file importing SNXImports sees it; each refusal asserted on the compiler's own message.
class SurfaceSuite extends munit.FunSuite:

  private def messages(errors: List[scala.compiletime.testing.Error]): String = errors.map(_.message).mkString(" | ")

  test("a step needs a program"):
    val errors = messages(typeCheckErrors("""Step.run()"""))
    assert(clue(errors).contains("missing argument"))

  test("an argument is a String, a File, an Argument or a Matching"):
    val errors = messages(typeCheckErrors("""Step.run("cc", 1)"""))
    assert(clue(errors).contains("Found:    (1 : Int)"))

  test("a token is no longer accepted"):
    val errors = messages(typeCheckErrors("""Vendored.local("x").command("answer-1")"""))
    assert(clue(errors).contains("Found:    (\"answer-1\" : String)"))

  test("a recipe has no logger to call"):
    val errors = messages(typeCheckErrors("""(c: BuildContext) => c.log"""))
    assert(clue(errors).contains("value log is not a member of"))

  test("Artefacts is not the surface any more"):
    val errors = messages(typeCheckErrors("""snx.sbt.Artefacts(Seq.empty, Seq.empty)"""))
    assert(clue(errors).contains("cannot be accessed"))

  test("an Argument's part is a String, a File or an Argument"):
    val errors = messages(typeCheckErrors("""arg"-j${4}""""))
    assert(clue(errors).contains("Found:    (4 : Int)"))

  test("an environment value is a String, a File or an Argument"):
    val errors = messages(typeCheckErrors("""Step.run("cc").env("N" -> 4)"""))
    assert(clue(errors).contains("Found:"))

  test("a Matching needs its pattern"):
    val errors = messages(typeCheckErrors("""Matching(new java.io.File("x"))"""))
    assert(clue(errors).contains("missing argument"))

  test("the README's recipe compiles as written"):
    // The README's example, its `repository` and `commit` given values.
    val errors = typeCheckErrors("""
      import sbt.io.syntax.*
      val repository = "https://github.com/P-H-C/phc-winner-argon2.git"
      val commit = "62358ba2123abd17fccf2a108a301d4b52c01a7c"
      Vendored.git(repository, commit).command { ctx =>
        val build = ctx.staging / "build"
        Recipe(
          Step.copy(ctx.source, build),
          Step.run("make", "-C", build, "libargon2.a", s"CC=${ctx.clang.getAbsolutePath}", arg"-j${ctx.jobs}")
        ).libraries(build / "libargon2.a").includes(build / "include")
      }
    """)
    assertEquals(messages(errors), "")
end SurfaceSuite
