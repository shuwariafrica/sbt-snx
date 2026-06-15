// Shared by both rows; resolves `Platform` from the row's own platform source directory (scalajvm / scalanative), so
// the build only compiles when the matrix has injected the correct per-row sources.
object Main:
  def main(args: Array[String]): Unit =
    println(s"snx-matrix-${Platform.tag}")
