// The checks each phase runs. A cache hit restores the library's outputs into its staging directory but not the
// object file the build compiles on the way, so removing the staging, or that object, before a phase and looking for
// answer.o after it tells a build from a hit; the variant is read from the linked archive's own bytes.
def stagings(target: File, config: String): Seq[File] =
  Option((target / "snx" / "vendored" / config).listFiles).toSeq.flatten.filter(d => d.isDirectory && d.getName != "clones")

val removeStaging = taskKey[Unit]("delete the main and test staging of the answer library")
removeStaging := Def.uncached(IO.delete(stagings(target.value, "main") ++ stagings(target.value, "test")))

val removeObject = taskKey[Unit]("delete the object file the main build compiles, leaving the rest of its staging")
removeObject := Def.uncached(IO.delete(stagings(target.value, "main").map(_ / "answer.o")))

val expectBuilt = inputKey[Unit]("assert the library was built this run, in the named config")
expectBuilt := Def.uncached {
  val config = complete.DefaultParsers.spaceDelimited("<config>").parsed.headOption.getOrElse("main")
  val objects = stagings(target.value, config).map(_ / "answer.o")
  assert(objects.exists(_.isFile), s"expected a cache miss: the build to have compiled answer.o under ${stagings(target.value, config)}")
}

// The answer archive a resolved configuration links, read from its rendering.
def linkedArchive(config: String): File =
  "[^\\s,()\\[\\]]+(?:libanswer\\.a|answer\\.lib)".r.findFirstIn(config).map(file).getOrElse(sys.error(s"no answer archive in $config"))

// A hit: the archive linked is present, and its staging holds no object compiled this run.
def cached(config: String): Unit = {
  val archive = linkedArchive(config)
  assert(archive.isFile, s"the linked archive $archive is absent")
  assert(!(archive.getParentFile / "answer.o").exists, s"expected a cache hit: no build to have compiled ${archive.getParentFile / "answer.o"}")
}

val expectCached = taskKey[Unit]("assert the main library was served from the cache")
expectCached := Def.uncached(cached((Compile / SNX.config).value.toString))

val expectTestCached = taskKey[Unit]("assert the test library was served from the cache")
expectTestCached := Def.uncached(cached((Test / SNX.config).value.toString))

// The archive the resolved configuration links, and the strings in it: `expectHolds snx-variant-2` passes only when the
// linked archive was built from the recipe that defines it.
val expectHolds = inputKey[Unit]("assert the linked answer archive contains each given string")
expectHolds := Def.uncached {
  val wanted = complete.DefaultParsers.spaceDelimited("<string>").parsed
  val config = (Compile / SNX.config).value.toString
  val archive = linkedArchive(config)
  val text = new String(IO.readBytes(archive), "ISO-8859-1")
  val found = "snx-(?:variant|extra|note|overlay)-[0-9]+".r.findAllIn(text).toList.distinct
  wanted.foreach(w => assert(found.contains(w), s"$archive holds $found, not $w"))
  streams.value.log.info(s"snx vendored/commandcache: $archive holds ${found.mkString(", ")}")
}
