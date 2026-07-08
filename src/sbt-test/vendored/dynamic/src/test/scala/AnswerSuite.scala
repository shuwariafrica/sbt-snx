// The UNSCOPED vendored `answer` library folds into the TEST link too (it is visible in every configuration), so the
// test binary links libanswer.so dynamically just as the main executable does. The vendored build's cache key omits the
// configuration, so the test link reuses the main build's shared library and its `-rpath`; this test proves the
// TestAdapter-launched binary still resolves libanswer.so at runtime through that rpath. `glue` is the extern object in
// Main.scala (on the test classpath, Test extends Compile).
class AnswerSuite extends munit.FunSuite:
  test("the unscoped dynamic vendored library links into and resolves for the test binary"):
    assertEquals(glue.snx_glue(), 42)
