import scala.scalanative.unsafe.*

@extern
object answer:
  def snx_perlib_answer(): CLongLong = extern

object Main:
  def main(args: Array[String]): Unit =
    val value = answer.snx_perlib_answer()
    assert(value == 42L, s"the static archive's symbol did not resolve: got $value")
    println(s"snx-perlib: $value")
