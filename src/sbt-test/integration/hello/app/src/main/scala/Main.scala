import scala.scalanative.unsafe.*

// snx_glue lives in this module's platform resource directory (resources-linux/scala-native/glue.c); it #includes the
// vendored answer.h and calls snx_answer from the CMake-built libanswer.a, so it links only if SNX.vendored built the
// library and folded it into this executable's link.
@extern object glue:
  def snx_glue(): CInt = extern

object Main:
  def main(args: Array[String]): Unit =
    val version = Greeting.zlibVersion
    val answer = glue.snx_glue()
    println(s"snx hello: zlib $version (${Engine.label}), vendored answer $answer")
    assert(version.nonEmpty, "zlib version was empty")
    assert(answer == 42, s"vendored snx_answer returned $answer, expected 42")
