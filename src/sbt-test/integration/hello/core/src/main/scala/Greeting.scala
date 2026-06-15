import scala.scalanative.unsafe.*

@extern
object bindings:
  def snx_zlib_version(): CString = extern

object Greeting:
  def zlibVersion: String = fromCString(bindings.snx_zlib_version())
