import scala.scalanative.unsafe.*

// snx_glue lives in src/test/resources/scala-native/glue.c; it links only if the test-scoped vendored libanswer.a
// reached the test link and its -Iinclude reached the test C compile.
@extern object glue:
  def snx_glue(): CInt = extern

class AnswerSuite extends munit.FunSuite:
  test("the test-scoped vendored library links into the test binary"):
    assertEquals(glue.snx_glue(), 42)
