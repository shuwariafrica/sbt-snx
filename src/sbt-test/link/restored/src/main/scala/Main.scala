object Main:
  def main(args: Array[String]): Unit =
    if Greeting.text != args(0) then throw new IllegalStateException(s"expected ${args(0)}, linked ${Greeting.text}")
    println(s"snx-link-restored: ${Greeting.text}")
