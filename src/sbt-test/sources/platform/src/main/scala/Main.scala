import scala.scalanative.unsafe.*

@extern object plat:
  def snx_answer(): CInt = extern

// References Plat (from the scala-linux source dir) and snx_answer (from resources-linux/scala-native), so a
// successful compile proves the platform source dir and a successful link proves the platform .c.
object Main:
  def main(args: Array[String]): Unit =
    println(s"${Plat.name}: ${plat.snx_answer()}")
