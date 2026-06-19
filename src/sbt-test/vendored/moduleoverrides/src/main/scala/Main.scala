import scala.scalanative.unsafe.*

// snx_glue calls snx_answer from the vendored libanswer.a, whose CMake build required the moduleOverrides directory on
// CMAKE_MODULE_PATH to configure at all.
@extern object glue:
  def snx_glue(): CInt = extern

object Main:
  def main(args: Array[String]): Unit =
    val answer = glue.snx_glue()
    assert(answer == 42, s"vendored snx_answer returned $answer, expected 42")
    println(s"snx-vendored-moduleoverrides answer: $answer")
