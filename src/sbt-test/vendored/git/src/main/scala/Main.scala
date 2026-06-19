import scala.scalanative.unsafe.*

// snx_glue (src/main/resources/scala-native/glue.c) #includes the cloned-and-built answer.h and calls snx_answer
// from the built libanswer.a, so this links only if the Git source cloned, built, and folded into the link.
@extern object glue:
  def snx_glue(): CInt = extern

object Main:
  def main(args: Array[String]): Unit =
    val answer = glue.snx_glue()
    assert(answer == 42, s"vendored snx_answer returned $answer, expected 42")
    println(s"snx-vendored-git answer: $answer")
