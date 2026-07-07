import scala.scalanative.unsafe.*

// snx_glue calls snx_answer from the vendored libanswer.so, dynamically linked into this executable and found at
// runtime through the -rpath the plugin adds to the build directory.
@extern object glue:
  def snx_glue(): CInt = extern

object Main:
  def main(args: Array[String]): Unit =
    val answer = glue.snx_glue()
    assert(answer == 42, s"vendored snx_answer returned $answer, expected 42")
    println(s"snx-vendored-dynamic answer: $answer")
