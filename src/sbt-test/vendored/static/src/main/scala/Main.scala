import scala.scalanative.unsafe.*

@extern object glue:
  def snx_glue(): CInt = extern

object Main:
  def main(args: Array[String]): Unit =
    println(s"answer: ${glue.snx_glue()}")
