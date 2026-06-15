import scala.scalanative.unsafe.*

@extern
object capi:
  def snx_plat_value(): CLongLong = extern

object Main:
  def main(args: Array[String]): Unit =
    assert(Plat.name.nonEmpty, "per-platform Scala source (scalanative-<os>/Plat.scala) was not on the source path")
    assert(capi.snx_plat_value() == 1L, "per-platform native source (resources-<os>/scala-native/plat.c) was not compiled")
    println(s"snx-sources-${Plat.name}")
