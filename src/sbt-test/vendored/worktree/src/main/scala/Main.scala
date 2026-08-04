import scala.scalanative.unsafe.*

// snx_glue lives in src/main/resources/scala-native/glue.c, which #includes the vendored answer.h and calls snx_answer
// from libanswer.a - built by a Command backend out of the sources a Git origin staged.
@extern object glue:
  def snx_glue(): CInt = extern

object Main:
  def main(args: Array[String]): Unit =
    val answer = glue.snx_glue()
    assert(answer == 42, s"vendored snx_answer returned $answer, expected 42")
    println(s"snx-vendored-worktree answer: $answer")
