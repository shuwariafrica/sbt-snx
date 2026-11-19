import scala.scalanative.unsafe.*

// snx_glue lives in src/main/resources/scala-native/glue.c and calls into the vendored archives aa_base, mm_mid and
// zz_top, so this links only if all three reach the native link in a form the platform's linker resolves.
@extern object glue:
  def snx_glue(): CInt = extern

object Main:
  def main(args: Array[String]): Unit =
    val answer = glue.snx_glue()
    assert(answer == 42, s"vendored multi_top returned $answer, expected 42")
    println(s"snx-vendored-cmakeproject answer: $answer")
