import scala.scalanative.unsafe.*

// snx_glue lives in src/main/resources/scala-native/glue.c, which #includes the vendored answer.h and calls snx_answer
// from libanswer.a - built by the Command backend (clang + archiver), not CMake.
@extern object glue:
  def snx_glue(): CInt = extern

object Main:
  def main(args: Array[String]): Unit =
    val answer = glue.snx_glue()
    assert(answer == 42, s"vendored snx_answer returned $answer, expected 42")
    println(s"snx-vendored-command answer: $answer")
