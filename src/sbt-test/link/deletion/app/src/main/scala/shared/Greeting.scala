package shared

import scala.scalanative.reflect.annotation.EnableReflectiveInstantiation

@EnableReflectiveInstantiation
class Probe

object Greeting:
  private val twice: String => String = s => s + s
  def text: String = twice("from-") match
    case "from-from-" => "from-app"
    case other        => other
