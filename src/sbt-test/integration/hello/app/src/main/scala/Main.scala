object Main:
  def main(args: Array[String]): Unit =
    val version = Greeting.zlibVersion
    println(s"snx hello: zlib $version (${Engine.label})")
    assert(version.nonEmpty, "zlib version was empty")
