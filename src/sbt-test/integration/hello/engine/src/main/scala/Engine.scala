object Engine:
  // Reaches core's zlib-backed binding, so this native library's own link also needs -lz - via core's descriptor.
  def label: String = s"engine/${Greeting.zlibVersion}"
