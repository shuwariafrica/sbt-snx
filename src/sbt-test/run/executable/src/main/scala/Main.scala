import scala.scalanative.unsafe.*

@extern
object probe:
  def snx_probe(): CLongLong = extern

object Main:
  def main(args: Array[String]): Unit =
    val environment = sys.env.getOrElse("SNX_RUN_PROBE", "")
    assert(environment == "ok", s"environment not forwarded: SNX_RUN_PROBE='$environment'")
    assert(args.sameElements(Array("alpha", "beta")), s"arguments not forwarded: ${args.mkString("[", ", ", "]")}")
    assert(probe.snx_probe() == 7L, "unmanaged native source (probe.c) was not compiled and linked")
    println("snx-run-ok")
