import scala.scalanative.meta.LinktimeInfo

class LinkSuite extends munit.FunSuite:
  test("binary is linked in debug mode") {
    assertEquals(LinktimeInfo.debugMode, true)
  }
