import scala.scalanative.unsafe.*

// snx_glue lives in src/main/resources/scala-native/glue.c and calls snx_answer from the built libanswer.a, so the
// binary links only if the vendored archive reached the native link.
@extern object glue:
  def snx_glue(): CInt = extern

object Main:
  def main(args: Array[String]): Unit =
    println(s"answer: ${glue.snx_glue()}")
