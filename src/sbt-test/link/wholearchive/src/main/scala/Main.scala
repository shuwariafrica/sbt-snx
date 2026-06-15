object Main:
  // Deliberately references nothing from the force-linked archive: only whole-archive linking pulls it in.
  def main(args: Array[String]): Unit =
    println("snx-wholearchive-ok")
