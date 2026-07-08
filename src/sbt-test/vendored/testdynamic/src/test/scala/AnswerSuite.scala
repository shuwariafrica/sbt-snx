import scala.scalanative.unsafe.*

// snx_glue lives in src/test/resources/scala-native/glue.c; it links only if the test-scoped, dynamically-linked
// vendored libanswer.so reached the test link (as `-lanswer -L<builtdir>`) and its header reached the test C compile.
@extern object glue:
  def snx_glue(): CInt = extern

class AnswerSuite extends munit.FunSuite:
  test("the test-scoped dynamic vendored library links into and resolves for the test binary"):
    assertEquals(glue.snx_glue(), 42)
