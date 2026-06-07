import scala.scalanative.unsafe.*

// snx_glue is in src/main/resources/scala-native/glue.c, which includes the answer.h and calls snx_answer from the
// archive built out of the cloned git repository - so this links only if the Git source was fetched, built, and wired.
@extern object glue:
  def snx_glue(): CInt = extern

object Main:
  def main(args: Array[String]): Unit =
    println(s"answer: ${glue.snx_glue()}")
