import scala.scalanative.unsafe.*

@extern object vendored:
  def snx_answer(): CInt = extern
  def snx_dash(): CInt = extern
  def snx_nested(): CInt = extern
  def snx_clones(): CInt = extern

// Links only while every vendored archive is where its resolved configuration says; each returns its own answer.
object Main:
  def main(args: Array[String]): Unit =
    val got = Seq(vendored.snx_answer(), vendored.snx_dash(), vendored.snx_nested(), vendored.snx_clones())
    assert(got == Seq(42, 1, 2, 3), s"the vendored libraries returned $got, expected 42, 1, 2, 3")
    println(s"snx vendored/slugs: ${got.mkString(", ")}")
