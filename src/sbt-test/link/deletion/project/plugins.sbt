sys.props.get("plugin.version") match
  case Some(v) => addSbtPlugin("africa.shuwari" % "sbt-snx" % v)
  case None    => sys.error("'plugin.version' system property not set by scripted")
