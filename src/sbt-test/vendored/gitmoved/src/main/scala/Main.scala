import scala.scalanative.unsafe.*

// snx_glue (src/main/resources/scala-native/glue.c) #includes the built answer.h and calls snx_answer from the
// cloned-and-built libanswer.a, so this links only if the Git source was fetched, built, and folded into the link.
// `run <expected>` asserts the linked answer, so the same binary discriminates the branch's two states.
@extern object glue:
  def snx_glue(): CInt = extern

object Main:
  def main(args: Array[String]): Unit =
    val expected = args(0).toInt
    val answer = glue.snx_glue()
    assert(answer == expected, s"vendored snx_answer returned $answer, expected $expected")
    println(s"snx-vendored-gitmoved answer: $answer")
