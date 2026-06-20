import scala.scalanative.unsafe.*

// snx_glue lives in src/main/resources/scala-native/glue.c, which #includes the vendored answer.h (so this links
// only if the vendored -Iinclude reached the Scala Native C compile) and calls snx_answer from the built
// libanswer.a (so this links only if the built archive reached the native link).
@extern object glue:
  def snx_glue(): CInt = extern

object Main:
  def main(args: Array[String]): Unit =
    val answer = glue.snx_glue()
    assert(answer == 42, s"vendored snx_answer returned $answer, expected 42")
    println(s"snx-vendored answer: $answer")
