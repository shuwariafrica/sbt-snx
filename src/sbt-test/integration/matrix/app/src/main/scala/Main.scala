// Shared by both rows; resolves `Platform` from the row's own platform source directory (scalajvm / scalanative), so
// the build only compiles when the matrix has injected the correct per-row sources, and `Label` from the matching row
// of the `lib` matrix.
object Main:
  def main(args: Array[String]): Unit =
    println(s"${Label.prefix}-${Platform.tag}")
