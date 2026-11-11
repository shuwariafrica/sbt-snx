object Main:
  def main(args: Array[String]): Unit =
    val actual = shared.Greeting.text
    if actual != args(0) then throw new IllegalStateException(s"expected ${args(0)}, linked $actual")
    println(s"snx-link-deletion: $actual")
