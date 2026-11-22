import scala.scalanative.unsafe.*

@extern object answer:
  def snx_answer(): CInt = extern

// Prints the answer the linked archive returns, for the build to compare with the one it expects.
object Main:
  def main(args: Array[String]): Unit =
    println(s"snx-answer=${answer.snx_answer()}")
