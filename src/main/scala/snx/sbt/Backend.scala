/****************************************************************
 * Copyright © 2026 Shuwari Africa Ltd.                         *
 *                                                              *
 * This file is licensed to you under the terms of the Apache   *
 * License Version 2.0 (the "License"); you may not use this    *
 * file except in compliance with the License. You may obtain   *
 * a copy of the License at:                                    *
 *                                                              *
 *     https://www.apache.org/licenses/LICENSE-2.0              *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, *
 * either express or implied. See the License for the specific  *
 * language governing permissions and limitations under the     *
 * License.                                                     *
 ****************************************************************/
package snx.sbt

import sbt.io.Hash
import sbt.io.IO
import sbt.io.syntax.*
import sbt.util.Digest
import sbt.util.Logger

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.scalanative.build.Mode
import scala.sys.process.Process
import scala.sys.process.ProcessLogger
import scala.util.Try

import snx.ABI
import snx.NativeRuntime
import snx.NativeRuntime.*
import snx.SNXError

/** What a `command` build's [[Recipe]] is computed from: where the source will be, the staging directory its outputs go
  * under, the resolved [[snx.NativeRuntime NativeRuntime]], the requested [[Linkage]] (a `Dynamic` request builds a
  * shared library, a `Static` one an archive), the Scala Native build [[scala.scalanative.build.Mode Mode]], the C and
  * C++ compilers the Scala Native link uses, and a job count for a parallel build (`arg"-j${context.jobs}"`), the
  * host's processors when the step runs and keyed as `${jobs}`, so hosts with different processor counts share an
  * archive.
  *
  * The recipe is computed on every build and keyed before the source is fetched, so it names paths in the source and
  * the staging directory without reading either; a recipe that differs once the source is present fails the build
  * with [[snx.SNXError$.RecipeChanged RecipeChanged]], on every attempt.
  */
final case class BuildContext(
  source: File,
  staging: File,
  runtime: NativeRuntime,
  linkage: Linkage,
  mode: Mode,
  clang: File,
  clangPP: File,
  jobs: Argument)

// The outputs of a backend build: the library files to link and the header directories to expose (`-I`).
final private[sbt] case class Artefacts(libraries: Seq[File], includes: Seq[File]) derives CanEqual

private[sbt] object Artefacts:
  def empty: Artefacts = Artefacts(Seq.empty, Seq.empty)

// How a Vendored library's source is built into Artefacts; Origin carries the factory methods that select a backend.
sealed private[sbt] trait Backend:

  def build(context: BuildContext, vendoring: Backend.Vendoring, log: Logger): Artefacts

  // The backend's contribution to the per-library cache key for `context` - its resolved configuration, so a config
  // change invalidates the cached archive. Computed on every build, before the source is fetched.
  def cacheKey(context: BuildContext, vendoring: Backend.Vendoring): Seq[String]

private[sbt] object Backend:

  // A CMake build of `targets` from the project in `directory` (relative to the source root), with per-platform
  // configure `flags`, `BUILD_SHARED_LIBS` set from the requested Linkage (Static forces archives, Dynamic shared
  // libraries), and an optional `moduleOverrides` dir prepended to CMAKE_MODULE_PATH.
  final case class CMake(
    targets: Seq[String],
    flags: PartialFunction[NativeRuntime, Seq[String]],
    directory: String,
    moduleOverrides: Option[File])
      extends Backend:

    def build(context: BuildContext, vendoring: Vendoring, log: Logger): Artefacts =
      try built(context, log)
      catch
        case error: SNXError            => fail(SNXError.forLibrary(vendoring.library, error))
        case error: java.io.IOException =>
          fail(SNXError.CMakeBuildFailed(s"snx: vendored library '${vendoring.library}': the CMake build could not run: $error"))

    private def built(context: BuildContext, log: Logger): Artefacts =
      context.runtime match
        case Windows(_, ABI.MinGw) =>
          val message =
            "snx: source-built C via the CMake backend is not supported on the MinGW toolchain; " +
              "use the MSVC toolchain to build vendored C on Windows."
          fail(SNXError.UnsupportedToolchain(message))
        case Linux(_, _) | Darwin(_) | Windows(_, ABI.Msvc) => ()
      val project = context.source.toPath.nn.resolve(projectDirectory(directory)).nn.toFile.nn
      if !(project / "CMakeLists.txt").isFile then
        fail(
          SNXError.CMakeBuildFailed(
            s"snx: no CMakeLists.txt in ${project.getAbsolutePath}; a project whose top-level CMakeLists.txt sits in a " +
              "subdirectory of the vendored source names it with .directory(\"<path>\")"))
      val shared = context.linkage == Linkage.Dynamic
      val buildType = cmakeBuildType(context.mode)
      val buildDir = context.staging / "build"
      val prefix = context.staging / "prefix"
      val prefixPath = cmakePath(prefix)
      IO.createDirectory(buildDir)
      val overrides = moduleOverrides.toSeq.map(dir => s"-DCMAKE_MODULE_PATH=${cmakePath(dir)}")
      val configureFlags = flags.applyOrElse(context.runtime, (_: NativeRuntime) => Nil)
      // The prefix is set at configure as well as at install: an install() rule's destination resolved at configure
      // (GNUInstallDirs' CMAKE_INSTALL_FULL_<dir>, brotli's man pages) would otherwise point at the system prefix.
      run(
        compilerPin(context.runtime, context.clang, context.clangPP),
        Seq(
          "cmake",
          "-S",
          project.getAbsolutePath,
          "-B",
          buildDir.getAbsolutePath,
          s"-DCMAKE_BUILD_TYPE=$buildType",
          s"-DBUILD_SHARED_LIBS=${if shared then "ON" else "OFF"}",
          s"-DCMAKE_INSTALL_PREFIX=$prefixPath"
        ) ++ overrides ++ configureFlags,
        "configure",
        log
      )
      run(
        Seq.empty,
        Seq("cmake", "--build", buildDir.getAbsolutePath, "--config", buildType, "--parallel", buildJobs.toString)
          ++ targets.flatMap(target => Seq("--target", target)),
        "build",
        log
      )
      val install = Seq("cmake", "--install", buildDir.getAbsolutePath, "--prefix", prefixPath, "--config", buildType)
      if !succeeds(Seq.empty, install, log) then
        fail(
          SNXError.CMakeBuildFailed(
            s"snx: cmake install failed: ${install.mkString(" ")}. cmake --install runs every install() rule of the " +
              "project, so a rule for a target outside `targets`, or one writing to a fixed absolute path the build " +
              "cannot write, fails it; turn such a rule off through the project's own option in `configure`."))
      requireInstalled(buildDir / "install_manifest.txt", prefix)
      val libraries = collected(context.runtime, context.linkage, prefix, prefix.allPaths.get().filter(_.isFile))
      if libraries.isEmpty then
        fail(
          SNXError.CMakeBuildFailed(
            s"snx: cmake produced no ${if shared then "shared" else "static"} library under ${prefix.getAbsolutePath}; " +
              s"the project must define install() rules for its ${if shared then "LIBRARY/RUNTIME" else "ARCHIVE"} artefacts."))
      Artefacts(libraries, Seq(prefix / "include").filter(_.isDirectory))
    end built

    // The project directory enters the key in its normal form and tagged, so equivalent spellings share one key and no
    // configure flag can stand in for it.
    def cacheKey(context: BuildContext, vendoring: Vendoring): Seq[String] =
      val overridesKey = moduleOverrides.filter(_.isDirectory).map(Vendored.contentDigest).toSeq
      Seq("cmake", targets.mkString(","), s"directory=${projectDirectory(directory)}") ++
        flags.applyOrElse(context.runtime, (_: NativeRuntime) => Nil) ++ overridesKey
  end CMake

  // The normal form of a CMake project `directory`, relative to the vendored source root. One with a root component
  // (on Windows also `\x` and the drive-relative `C:x`) or whose normal form climbs out of the root fails: the cache
  // keys on the source root's content, so a project outside it would build from inputs the key does not cover.
  private[sbt] def projectDirectory(directory: String): Path =
    Try(Paths.get(directory).nn).toOption
      .filter(path => Option(path.getRoot).isEmpty)
      .map(_.normalize.nn)
      .filterNot(_.startsWith(".."))
      .getOrElse(
        fail(
          SNXError.DirectoryOutsideSource(
            s"snx: the CMake project directory '$directory' is not a path inside the vendored source; name the directory " +
              "holding the top-level CMakeLists.txt relative to the source root, for example .directory(\"build/cmake\")")))

  // The libraries a CMake install yields, from the files found under its `prefix`: archives for a Static build, shared
  // libraries for a Dynamic one, sorted by their path under the prefix. The sort, not the filesystem's enumeration,
  // decides the link order, and with it which of two archives defining one symbol supplies it.
  private[sbt] def collected(runtime: NativeRuntime, linkage: Linkage, prefix: File, files: Seq[File]): Seq[File] =
    val accept: String => Boolean = linkage match
      case Linkage.Static  => isArchive(runtime)
      case Linkage.Dynamic => isShared(runtime)
    val root = prefix.toPath.nn
    files.filter(file => accept(file.getName.nn)).sortBy(file => root.relativize(file.toPath).toString.replace('\\', '/'))

  // Fail when the CMake install `manifest` (one absolute path per installed file) names a path outside `prefix`. Such a
  // path is an install() rule with a fixed absolute destination that the host let the build write: the write has
  // happened, and this makes it fail the build by name rather than pass unnoticed.
  private[sbt] def requireInstalled(manifest: File, prefix: File): Unit =
    if !manifest.isFile then
      fail(
        SNXError.CMakeBuildFailed(
          s"snx: cmake install wrote no ${manifest.getAbsolutePath}, so what it installed cannot be checked against the " +
            s"staging prefix ${prefix.getAbsolutePath}"))
    val root = prefix.toPath.nn.toAbsolutePath.nn.normalize.nn
    val outside = IO.readLines(manifest).filterNot(line => Paths.get(line).nn.toAbsolutePath.nn.normalize.nn.startsWith(root))
    if outside.nonEmpty then
      fail(
        SNXError.OutputOutsideStaging(
          s"snx: cmake install wrote outside the staging prefix ${prefix.getAbsolutePath}: ${outside.mkString(", ")}. An " +
            "install() rule with a fixed absolute destination wrote there; turn it off through the project's own option " +
            "in `configure`."))
  end requireInstalled

  // A path for CMake. CMake re-parses a -D string value as CMake code, so a Windows backslash path reads as escape
  // sequences (`\U`, ...) and aborts configure; forward slashes are accepted on every platform.
  private def cmakePath(file: File): String = file.getAbsolutePath.nn.replace('\\', '/')

  // A `command` build: the plugin runs the steps of the Recipe `recipe` computes, so the recipe - data, computed on
  // every build - is the cache identity, not the function that computed it.
  final case class Command(recipe: BuildContext => Recipe) extends Backend:
    def build(context: BuildContext, vendoring: Vendoring, log: Logger): Artefacts =
      execute(computed(context, vendoring), context, vendoring, log)
    def cacheKey(context: BuildContext, vendoring: Vendoring): Seq[String] =
      "command" +: recipeKey(computed(context, vendoring), context, vendoring)

    // The user's function, its own failure typed: it is computed before the source is fetched, where reading the
    // source is the likeliest mistake, so the message says so.
    private def computed(context: BuildContext, vendoring: Vendoring): Recipe =
      try recipe(context)
      catch
        case error: SNXError                        => fail(error)
        case scala.util.control.NonFatal(throwable) =>
          fail(
            SNXError.RecipeFailed(
              s"snx: vendored library '${vendoring.library}': its recipe failed while it was computed: $throwable. The " +
                "recipe is computed on every build, before a Git source is fetched and before the staging is prepared"))
  end Command

  // The cache key of `recipe`: every step, argument, working directory, environment value and output, in order. A path
  // under the source or the staging directory is keyed relative to it, so the main and test builds of one library
  // share a key; any other file a File, an Argument part or a Matching names is keyed by its content, and text is keyed
  // as written. The compilers are keyed by the toolchain identity, the job count as `${jobs}`, and the source by its
  // own identity, beside this. Every value is framed by its length, so no two recipes render alike.
  private[sbt] def recipeKey(recipe: Recipe, context: BuildContext, vendoring: Vendoring): Seq[String] =
    recipeKey(recipe, context, vendoring, programDigests, stamp)

  // As above, with the programs' kept digests and the stamp a platform gives, so the rules the memo rests on can be
  // exercised with the fields another platform keeps.
  private[sbt] def recipeKey(
    recipe: Recipe,
    context: BuildContext,
    vendoring: Vendoring,
    programs: ProgramDigests,
    stamps: Path => Stamp): Seq[String] =
    val key = RecipeKey(context, vendoring, programs, stamps)
    recipe.steps.flatMap {
      case run: Step.Run =>
        val directory = run.directory.getOrElse(context.staging)
        // Keyed first, so a directory the step cannot run in is refused before its program is resolved against it.
        val in = run.directory.map(dir => s"in=${key.place(dir)}")
        Seq("run", s"program=${key.program(run, directory)}") ++
          run.arguments.map(argument => s"argument=${key.argument(argument)}") ++
          in ++
          run.environment.map((name, value) => s"env=${framed(name)}${key.argument(value)}")
      case copy: Step.Copy => Seq("copy", s"from=${key.file(copy.from)}", s"to=${key.place(copy.to)}")
    } ++ recipe.libraryFiles.map(output => s"library=${key.output(output)}") ++
      recipe.includeDirectories.map(output => s"include=${key.output(output)}")
  end recipeKey

  private def framed(value: String): String = s"${value.length}:$value"

  final private class RecipeKey(context: BuildContext, vendoring: Vendoring, programs: ProgramDigests, stamps: Path => Stamp):
    private val windows = scala.util.Properties.isWin
    // Longest first, so a root nested in another is replaced before the one enclosing it.
    private val roots: Seq[(String, Path)] =
      Seq("${source}" -> normal(context.source), "${staging}" -> normal(context.staging)).sortBy(-_._2.toString.length)
    private val compilers: Seq[(String, Path)] = Seq("${clang}" -> normal(context.clang), "${clang++}" -> normal(context.clangPP))
    private val tokens: Seq[(String, String)] =
      (roots ++ compilers).map((token, path) => token -> path.toString).sortBy(-_._2.length)
    // Real paths too, so a spelling through a symbolic link is placed where the file is.
    private val tokenRoots: Seq[Path] = roots.flatMap((_, root) => Seq(root) ++ real(root))
    private val build: Seq[Path] = vendoring.build.map(normal).flatMap(root => Seq(root) ++ real(root))
    private val targets: Seq[Path] = targetsOf(vendoring)
    // This library's own staging, as written and through any link above it.
    private val ownStaging: Seq[Path] = (Seq(normal(vendoring.staging)) ++ realised(normal(vendoring.staging))).distinct

    private def refuseMarker(value: String): Unit =
      if value.contains(Argument.marker) then
        fail(
          SNXError.ArgumentInText(
            s"snx: vendored library '${vendoring.library}': the recipe value '${value.replace(Argument.marker, "<Argument>")}' " +
              "holds an Argument interpolated into a String; write the token with arg\"...\" (arg\"-j${context.jobs}\")"))

    // Text keyed as written, with the source, staging and compiler paths replaced by their tokens; what a path in it
    // names is not read.
    private def text(value: String): String =
      refuseMarker(value)
      framed(substitute(value, tokens, windows))

    def argument(argument: String | File | Argument | Matching): String = argument match
      case value: String     => s"text:${text(value)}"
      case path: File        => s"file:${framed(file(path))}"
      case joined: Argument  => joined.parts.map(part).map(framed).mkString("arg:", "", "")
      case pattern: Matching => s"matching:${framed(matching(pattern))}"

    private def part(part: Argument.Part): String = part match
      case Argument.Part.Text(value) => s"text:${text(value)}"
      case Argument.Part.Path(path)  => s"file:${file(path)}"
      case Argument.Part.Jobs        => "${jobs}"

    def file(file: File): String =
      refuseMarker(file.getPath.nn)
      relative(normal(file)).getOrElse:
        val written = normal(file)
        (Seq(written) ++ real(written)).foreach(at =>
          refuseStaging(at, if at.equals(written) then written.toString else s"$written (a link to $at)"))
        s"$written@${content(file)}"

    // A path the recipe writes - a run directory, a copy's destination, an output - keyed by where it is and never read,
    // since the build writes it: one outside the staging fails the build before any step runs (`execute`).
    def place(file: File): String =
      refuseMarker(file.toString)
      relative(normal(file)).getOrElse(normal(file).toString)

    def output(output: File | Matching): String = output match
      case file: File        => s"file:${framed(place(file))}"
      case pattern: Matching =>
        refuseMarker(pattern.pattern)
        s"matching:${framed(s"${place(pattern.directory)}:${framed(pattern.pattern)}")}"

    // A directory in sbt's target that holds this library's own staging - the target, or one inside it above the
    // staging - as written or through a link.
    private def holdsStaging(path: Path): Boolean =
      !within(path, tokenRoots) && within(path, targets) && ownStaging.exists(_.startsWith(path))

    // A directory whose walk would enter this library's own staging is refused: its content is what this build
    // writes, and sbt rewrites its logs there on every run, so no key of it describes the build. A directory above
    // sbt's target leaves the target out of its walk and is not refused. The failure names `passed`, the value as the
    // recipe wrote it, since `path` can be a link's target or a walk's start the recipe never spelt.
    private def refuseStaging(path: Path, passed: String): Unit =
      if holdsStaging(path) then
        fail(
          SNXError.InputHoldsStaging(
            s"snx: vendored library '${vendoring.library}': the recipe passes $passed, which holds this library's own " +
              s"staging ${vendoring.staging.getAbsolutePath}: its content is what this build writes, and sbt rewrites " +
              "its logs under target on every run, so it cannot key the build; pass the directory beneath it that the " +
              "build reads"))

    // A path under a root, or a compiler, as its token; None for any other path.
    private def relative(path: Path): Option[String] =
      roots
        .collectFirst { case (token, root) if path.startsWith(root) => s"$token/${root.relativize(path).toString.replace('\\', '/')}" }
        .orElse(compilers.collectFirst { case (token, compiler) if compiler.toString == path.toString => token })

    private def matching(pattern: Matching): String =
      refuseMarker(pattern.directory.getPath.nn)
      refuseMarker(pattern.pattern)
      val directory = normal(pattern.directory)
      relative(directory) match
        case Some(token) => s"$token:${framed(pattern.pattern)}"
        case None        =>
          val start = walkStart(pattern, directory)
          val passed = s"Matching($directory, \"${pattern.pattern}\")"
          (Seq(start) ++ real(start)).foreach(at => refuseStaging(at, if at.equals(directory) then passed else s"$passed (walking $at)"))
          val matches =
            walked(matched(pattern, directory.toFile.nn, files = true, targets, (path, error) => fail(failure(path, error))), directory)
              .map(file => framed(s"${normal(file)}@${content(file)}"))
          s"$directory:${framed(pattern.pattern)}${matches.mkString}"

    // The program a step runs: the one file kept by stamp, since a tool is hashed often and changes rarely.
    def program(run: Step.Run, directory: File): String = resolve(run, directory) match
      case Some(found) =>
        refuseMarker(found.getPath.nn)
        relative(normal(found)).getOrElse(s"${normal(found)}@${programDigest(found)}")
      case None => s"${argument(run.program)}@unresolved"

    private def content(file: File): String =
      real(normal(file)).fold("absent")(digestOf)

    // A program's digest: hashed on every build where it is a file of the build, as any file the recipe names is;
    // elsewhere - a host tool - kept per real path while its stamp holds (ProgramDigests).
    private def programDigest(file: File): String =
      real(normal(file)).fold("absent")(path =>
        if within(path, build) || Files.isDirectory(path) then digestOf(path)
        else walked(programs.digest(path, stamps(path), hash), path))

    // A walk's failure, typed: an unreadable directory cannot be keyed, so the build cannot know what it would read.
    private def walked[A](walk: => A, directory: Path): A =
      try walk
      catch
        case error: java.io.IOException =>
          fail(
            SNXError.InputUnreadable(
              s"snx: vendored library '${vendoring.library}': the recipe names $directory, which could not be read to key " +
                s"it: $error"))

    // A file's digest, or a directory's: the sorted relative paths and digests of the files beneath it, links followed
    // (each file's digest kept by its real path) and a link cycle not entered twice, leaving out this library's own
    // source and staging, hidden directories beneath it, and sbt's `target` directories beneath it - unless the
    // directory named lies inside one of those, as a generated `src_managed` directory does.
    private def digestOf(path: Path): String =
      if !Files.isDirectory(path) then walked(hash(path), path)
      else
        refuseStaging(path, path.toString)
        val inTargets = within(path, targets)
        val entries = List.newBuilder[String]
        val _ = walked(
          Files.walkFileTree(
            path,
            java.util.EnumSet.of(java.nio.file.FileVisitOption.FOLLOW_LINKS),
            Int.MaxValue,
            new java.nio.file.SimpleFileVisitor[Path]:
              override def preVisitDirectory(
                dir: Path,
                attributes: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult =
                val beneath = !dir.equals(path)
                val hidden = beneath && Option(dir.getFileName).exists(_.toString.startsWith("."))
                // As spelt and as reached, since a link visited is spelt through the link (`out -> target`).
                val spellings = Seq(dir) ++ real(dir)
                val skipped = beneath && spellings.exists(at => within(at, tokenRoots) || (!inTargets && within(at, targets)))
                if hidden || skipped then java.nio.file.FileVisitResult.SKIP_SUBTREE else java.nio.file.FileVisitResult.CONTINUE
              override def visitFile(file: Path, attributes: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult =
                if attributes.isRegularFile then
                  entries += s"${path.relativize(file).toString.replace('\\', '/')}:${hash(file.toRealPath().nn)}"
                java.nio.file.FileVisitResult.CONTINUE
              override def visitFileFailed(file: Path, error: java.io.IOException): java.nio.file.FileVisitResult =
                error match
                  case _: java.nio.file.FileSystemLoopException => java.nio.file.FileVisitResult.SKIP_SUBTREE
                  case _                                        => fail(failure(file, error))
          ),
          path
        )
        Hash.toHex(Hash(entries.result().sorted.mkString("\n")))

    private def failure(file: Path, error: java.io.IOException): SNXError =
      SNXError.InputUnreadable(
        s"snx: vendored library '${vendoring.library}': $file, under a directory the recipe names, could not be read to " +
          s"key it: $error")

  end RecipeKey

  // A file's digest, hashed when asked: every file a recipe names is hashed on every build, wherever it is, since a copy
  // that keeps its size and modification time (`cp -p`, `tar`, robocopy, a generator pinned to SOURCE_DATE_EPOCH) must
  // still be seen on every platform, including one that keeps no change time.
  private def hash(path: Path): String = Digest.sha256Hash(path).hashHexString

  // Identifies a vendored build to the backend: the library's name, for its failures; the build's directories (the
  // build root and the project), whose programs are hashed on every build; sbt's target directories, left out of a
  // walk of a directory above them; and this library's own staging, which no directory the recipe passes may hold
  // within a target.
  final private[sbt] case class Vendoring(library: String, build: Seq[File], targets: Seq[File], staging: File)

  // sbt's target directories, as written and through any link.
  private def targetsOf(vendoring: Vendoring): Seq[Path] = vendoring.targets.map(normal).flatMap(root => Seq(root) ++ real(root))

  private def normal(file: File): Path = file.toPath.nn.toAbsolutePath.nn.normalize.nn

  private def real(path: Path): Option[Path] = Try(path.toRealPath().nn).toOption

  // `path` with the links above it resolved, whether or not it exists yet: its nearest existing ancestor's real path,
  // and the rest as written.
  private def realised(path: Path): Option[Path] =
    real(path).orElse(Option(path.getParent).flatMap(parent => realised(parent.nn).map(_.resolve(path.getFileName.nn).nn)))

  private def within(path: Path, roots: Seq[Path]): Boolean = roots.exists(root => path.startsWith(root))

  // What a file's stamp holds: its size, modification time, and the change time and file key where the platform keeps
  // them - Linux and macOS both, Windows neither (unix `ctime` cannot be set back, and the key changes when a file is
  // replaced); empty where it does not.
  final private[sbt] case class Stamp(size: Long, modified: Long, change: String, key: String) derives CanEqual

  private[sbt] def stamp(path: Path): Stamp =
    val change = Try(Files.getAttribute(path, "unix:ctime").toString).getOrElse("")
    val key = Try(Option(Files.readAttributes(path, classOf[java.nio.file.attribute.BasicFileAttributes]).nn.fileKey).fold("")(_.toString))
      .getOrElse("")
    Stamp(Files.size(path), Files.getLastModifiedTime(path).nn.toMillis, change, key)

  // Whether a digest taken at `hashedAt` may be kept while `stamp` holds: only once the file's modification is more
  // than two seconds older than its hashing (git's racy-timestamp rule), since an edit within a filesystem's timestamp
  // granularity of the hash would leave the stamp equal.
  private[sbt] def trusted(stamp: Stamp, hashedAt: Long): Boolean = hashedAt - stamp.modified > 2000

  // The programs' digests, kept in the sbt process per real path, so a link switched to another file (a Nix profile)
  // is a new entry, and reused while the file's stamp holds and the digest was trusted when taken.
  final private[sbt] class ProgramDigests:
    private val kept = new java.util.concurrent.atomic.AtomicReference[Map[Path, (Stamp, Boolean, String)]](Map.empty)

    def digest(path: Path, current: Stamp, hash: Path => String): String =
      kept.get.nn.get(path) match
        case Some((known, true, digest)) if known == current => digest
        case _                                               =>
          val hashedAt = System.currentTimeMillis
          val fresh = hash(path)
          val _ = kept.updateAndGet(_.updated(path, (current, trusted(current, hashedAt), fresh)))
          fresh

  private val programDigests = new ProgramDigests

  // `value` with each absolute path of `tokens` (longest first) replaced by its token, in its own and its forward-slash
  // spelling, since a recipe passing a path to CMake or a Windows tool may have converted it. An occurrence is a path
  // only where what follows it cannot continue a file name, so `/a/b` is not found in `/a/bc`. What precedes it is not
  // tested: a root glued to an option (`-I<root>`) must be substituted, and syntax cannot tell it from `/mnt<root>`,
  // whose substitution only lets main and test share a key for a host path. On Windows the comparison ignores case.
  private[sbt] def substitute(value: String, tokens: Seq[(String, String)], windows: Boolean): String =
    tokens.foldLeft(value) { case (text, (token, path)) =>
      Seq(path, path.replace('\\', '/')).distinct.foldLeft(text)((text, spelling) => replace(text, spelling, token, windows))
    }

  private def replace(text: String, path: String, token: String, windows: Boolean): String =
    occurrence(text, path, 0, windows) match
      case Some(at) => text.substring(0, at).nn + token + replace(text.substring(at + path.length).nn, path, token, windows)
      case None     => text

  private def occurrence(text: String, path: String, from: Int, windows: Boolean): Option[Int] =
    val at = if windows then text.toLowerCase.nn.indexOf(path.toLowerCase.nn, from) else text.indexOf(path, from)
    if at < 0 || path.isEmpty then None
    else
      val end = at + path.length
      val after = end < text.length && { val next = text.charAt(end); next.isLetterOrDigit || "._-".contains(next) }
      if after then occurrence(text, path, at + 1, windows) else Some(at)

  // The file `run` names, found on the PATH and PATHEXT the step runs with: its own `env` value over the inherited
  // one, the name compared without case on Windows as its environment is. The step runs this file, so the program
  // keyed is the program run.
  private[sbt] def resolve(run: Step.Run, directory: File): Option[File] =
    val windows = scala.util.Properties.isWin
    def variable(name: String): Option[String] =
      run.environment
        .findLast((key, _) => if windows then key.equalsIgnoreCase(name) else key == name)
        .map {
          case (_, value: String)   => value
          case (_, value: File)     => value.getAbsolutePath.nn
          case (_, value: Argument) => render(value)
        }
        .orElse(environment(name))
    resolve(run.program, directory, variable("PATH"), variable("PATHEXT"), windows)

  // As above with the PATH, PATHEXT and operating system given, so the Windows rules are exercised on any host: a File
  // as given; a name holding a directory separator, against the step's `directory`; a bare name, the first executable
  // match along PATH (entries unquoted on Windows), and on Windows, unless the name already ends in a PATHEXT
  // extension, the name with each PATHEXT extension in turn.
  private[sbt] def resolve(
    program: String | File,
    directory: File,
    path: Option[String],
    pathExt: Option[String],
    windows: Boolean): Option[File] = program match
    case file: File                                                   => Some(file)
    case named: String if named.contains('/') || named.contains('\\') =>
      val file = new File(named)
      Some(if file.isAbsolute then file else new File(directory, named))
    case name: String =>
      val extensions = pathExt.getOrElse(".COM;.EXE;.BAT;.CMD").split(';').toSeq.filter(_.nonEmpty)
      val names =
        if windows && !extensions.exists(ext => name.toLowerCase.nn.endsWith(ext.toLowerCase)) then extensions.map(name + _)
        else Seq(name)
      path.toSeq
        .flatMap(_.split(if windows then ';' else ':').toSeq)
        .map(entry => if windows then entry.stripPrefix("\"").stripSuffix("\"") else entry)
        .filter(_.nonEmpty)
        .iterator
        .flatMap(dir => names.map(candidate => new File(dir, candidate)))
        .find(file => file.isFile && file.canExecute)

  // System.getenv, not sys.env: on Windows its lookup ignores case, as the variable names themselves do.
  private def environment(name: String): Option[String] = Option(System.getenv(name)).map(_.nn)

  // Where a Matching's walk starts: `directory` and the pattern's leading segments without a wildcard, the last segment
  // kept back when every segment is literal.
  private def walkStart(pattern: Matching, directory: Path): Path =
    val segments = pattern.pattern.split('/').toSeq
    def wild(segment: String) = segment.exists("*?".contains(_))
    val fixed = segments.takeWhile(!wild(_))
    val literal = if fixed.size == segments.size then fixed.dropRight(1) else fixed
    literal.foldLeft(directory)((path, segment) => path.resolve(segment).nn)

  // The files (or directories) under `directory` whose relative path, with `/` separators, matches `pattern`, sorted.
  // The walk starts below the pattern's literal leading segments, which may name a hidden directory, and enters no
  // hidden directory beneath them, nor any of sbt's `targets` beneath them unless the start lies inside one - the rule
  // a directory's digest keeps, so a Matching over the project neither keys nor passes the build's outputs. Matching is
  // by case on every platform, so one pattern matches alike on every host. Links are followed, a cycle entered once,
  // and a failure to read a directory is handed to `unreadable`.
  private[sbt] def matched(
    pattern: Matching,
    directory: File,
    files: Boolean,
    targets: Seq[Path],
    unreadable: (Path, java.io.IOException) => Nothing): Seq[File] =
    val regex = globRegex(pattern.pattern)
    val root = directory.toPath.nn
    val start = walkStart(pattern, root)
    // Each directory compared as spelt and as reached, so a directory spelt through a link, or a link to target, is
    // placed where it is.
    def placed(dir: Path): Seq[Path] = Seq(dir.toAbsolutePath.nn.normalize.nn) ++ real(dir)
    val inTargets = placed(start).exists(within(_, targets))
    if !Files.isDirectory(start) then Seq.empty
    else
      val found = List.newBuilder[(String, File)]
      val links = java.util.EnumSet.of(java.nio.file.FileVisitOption.FOLLOW_LINKS)
      val _ = Files.walkFileTree(
        start,
        links,
        Int.MaxValue,
        new java.nio.file.SimpleFileVisitor[Path]:
          override def visitFileFailed(file: Path, error: java.io.IOException): java.nio.file.FileVisitResult =
            error match
              case _: java.nio.file.FileSystemLoopException => java.nio.file.FileVisitResult.SKIP_SUBTREE
              case _                                        => unreadable(file, error)
          override def preVisitDirectory(
            dir: Path,
            attributes: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult =
            val beneath = !dir.equals(start)
            val hidden = beneath && Option(dir.getFileName).exists(_.toString.startsWith("."))
            val output = beneath && !inTargets && placed(dir).exists(within(_, targets))
            if output then java.nio.file.FileVisitResult.SKIP_SUBTREE
            else
              if !files && !dir.equals(root) then
                val relative = root.relativize(dir).toString.replace('\\', '/')
                if regex.matches(relative) then found += relative -> dir.toFile.nn
              if hidden then java.nio.file.FileVisitResult.SKIP_SUBTREE else java.nio.file.FileVisitResult.CONTINUE
          override def visitFile(file: Path, attributes: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult =
            if files && attributes.isRegularFile then
              val relative = root.relativize(file).toString.replace('\\', '/')
              if regex.matches(relative) then found += relative -> file.toFile.nn
            java.nio.file.FileVisitResult.CONTINUE
      )
      found.result().sortBy(_._1).map(_._2)
    end if
  end matched

  // A Matching pattern as a regular expression: `**` across segments (with `**/` also matching no segment), `*` within
  // one, `?` one character within one; every other character literal.
  private[sbt] def globRegex(pattern: String): scala.util.matching.Regex =
    def translate(rest: List[Char]): String = rest match
      case '*' :: '*' :: '/' :: tail => "(?:.*/)?" + translate(tail)
      case '*' :: '*' :: tail        => ".*" + translate(tail)
      case '*' :: tail               => "[^/]*" + translate(tail)
      case '?' :: tail               => "[^/]" + translate(tail)
      case char :: tail              => java.util.regex.Pattern.quote(char.toString).nn + translate(tail)
      case Nil                       => ""
    translate(pattern.toList).r

  // Run `recipe` for `context`: every path a step writes and every output is refused unless it lies under the staging
  // directory, before any step runs; the steps run in order, each failing the build on a non-zero exit, a program that
  // cannot be found or started, a directory that cannot be made, or a copy that fails; and a named library or include
  // directory a step did not write, or a Matching that matches nothing, fails the build. Each failure names the library.
  private[sbt] def execute(recipe: Recipe, context: BuildContext, vendoring: Vendoring, log: Logger): Artefacts =
    def failed(message: String): Nothing =
      fail(SNXError.CommandBuildFailed(s"snx: vendored library '${vendoring.library}': $message"))
    def directoryOf(output: File | Matching): File = output match
      case file: File        => file
      case pattern: Matching => pattern.directory
    val writes = recipe.steps.flatMap {
      case run: Step.Run   => run.directory.toSeq
      case copy: Step.Copy => Seq(copy.to)
    }
    SNXPlugin.requireStaged(
      writes ++ (recipe.libraryFiles ++ recipe.includeDirectories).map(directoryOf),
      context.staging,
      vendoring.library)
    IO.createDirectory(context.staging)
    val targets = targetsOf(vendoring)
    def expanded(argument: String | File | Argument | Matching): Seq[String] = argument match
      case value: String     => Seq(value)
      case file: File        => Seq(file.getAbsolutePath.nn)
      case joined: Argument  => Seq(render(joined))
      case pattern: Matching =>
        val found = matched(pattern, pattern.directory, files = true, targets, (path, error) => failed(s"could not read $path: $error"))
        if found.isEmpty then failed(s"'${pattern.pattern}' matched no file under ${pattern.directory.getAbsolutePath}")
        found.map(_.getAbsolutePath.nn)
    recipe.steps.foreach {
      case run: Step.Run =>
        val directory = run.directory.getOrElse(context.staging)
        try IO.createDirectory(directory)
        catch case e: java.io.IOException => failed(s"could not create ${directory.getAbsolutePath}: ${e.getMessage}")
        val program = resolve(run, directory).filter(_.isFile).getOrElse(failed(s"the program '${run.program}' was not found"))
        val command = program.getAbsolutePath.nn +: run.arguments.flatMap(expanded)
        log.info(command.mkString(s"snx ${vendoring.library}: ", " ", ""))
        val environment = compilerPin(context.runtime, context.clang, context.clangPP) ++ run.environment.map {
          case (name, value: String)   => name -> value
          case (name, value: File)     => name -> value.getAbsolutePath.nn
          case (name, value: Argument) => name -> render(value)
        }
        val exit =
          try Process(command, Some(directory), environment*).!(ProcessLogger(line => log.info(line), line => log.warn(line)))
          catch case e: java.io.IOException => failed(s"could not start ${command.mkString(" ")}: ${e.getMessage}")
        if exit != 0 then failed(s"command exited $exit in ${directory.getAbsolutePath}: ${command.mkString(" ")}")
      case copy: Step.Copy =>
        try
          if copy.from.isDirectory then IO.copyDirectory(copy.from, copy.to, overwrite = true)
          else if copy.from.isFile then IO.copyFile(copy.from, copy.to)
          else failed(s"nothing to copy at ${copy.from.getAbsolutePath}")
        catch
          case e: java.io.IOException =>
            failed(s"could not copy ${copy.from.getAbsolutePath} to ${copy.to.getAbsolutePath}: ${e.getMessage}")
          case e: IllegalArgumentException =>
            failed(s"could not copy ${copy.from.getAbsolutePath} to ${copy.to.getAbsolutePath}: ${e.getMessage}")
    }
    def outputs(named: Seq[File | Matching], files: Boolean): Seq[File] = named.flatMap {
      case file: File =>
        if if files then file.isFile else file.isDirectory then Seq(file)
        else failed(s"the recipe names an output its steps did not write: ${file.getAbsolutePath}")
      case pattern: Matching =>
        val found = matched(pattern, pattern.directory, files, targets, (path, error) => failed(s"could not read $path: $error"))
        if found.isEmpty then failed(s"the output '${pattern.pattern}' matched nothing under ${pattern.directory.getAbsolutePath}")
        found
    }
    Artefacts(outputs(recipe.libraryFiles, files = true), outputs(recipe.includeDirectories, files = false))
  end execute

  // An Argument as the step receives it: its parts joined, files as absolute paths, the job count as a number.
  private def render(argument: Argument): String = argument.parts.map {
    case Argument.Part.Text(value) => value
    case Argument.Part.Path(file)  => file.getAbsolutePath.nn
    case Argument.Part.Jobs        => buildJobs.toString
  }.mkString

  // The CMAKE_BUILD_TYPE for a Scala Native build Mode, so a vendored library matches the deliverable's own
  // optimisation: debug (-O0) -> Debug, release-size -> MinSizeRel, the other release modes -> Release. Matched on
  // Mode.name because the Mode cases are private[scalanative] (and carry no CanEqual for strict equality), so it is
  // coupled to Scala Native's mode-name strings; the value is keyed into the vendored build cache.
  private[sbt] def cmakeBuildType(mode: Mode): String = mode.name match
    case "debug"        => "Debug"
    case "release-size" => "MinSizeRel"
    case _              => "Release"

  // Whether `fileName` is a static archive for `runtime`: .lib on MSVC, .a elsewhere. Per-runtime so a Unix build does
  // not accept a stray Windows import .lib, and vice versa.
  private def isArchive(runtime: NativeRuntime)(fileName: String): Boolean = runtime match
    case Windows(_, ABI.Msvc)                            => fileName.endsWith(".lib")
    case Linux(_, _) | Darwin(_) | Windows(_, ABI.MinGw) => fileName.endsWith(".a")

  private def isShared(runtime: NativeRuntime)(fileName: String): Boolean = runtime match
    case Linux(_, _)   => fileName.endsWith(".so")
    case Darwin(_)     => fileName.endsWith(".dylib")
    case Windows(_, _) => fileName.endsWith(".dll")

  // The compiler the CMake build uses, as the configure process's CC and CXX. On Linux and macOS the vendored C is built
  // by the clang/clang++ the Scala Native link uses (context.clang/clangPP, so an SNX.clang override reaches it too), an
  // ABI-compatible compiler rather than CMake's default (which may be cc/gcc). The pair replaces any ambient CC/CXX, so
  // the host environment cannot choose the compiler. CMake reads them only on a project's first configure, which every
  // vendored configure is (a cache miss deletes the staging), and not for a language whose CMAKE_<LANG>_COMPILER is
  // already set, so a -DCMAKE_C_COMPILER in `configure` still wins. Unlike a -D pin, an environment raises no
  // unused-variable warning in a project that never enables C++. On Windows MSVC is CMake's default and is
  // ABI-compatible with the clang-windows-msvc link, so it is left to CMake's own detection - forcing clang there would
  // need a generator/toolset override (MinGW has already failed fast in build).
  private[sbt] def compilerPin(runtime: NativeRuntime, clang: File, clangPP: File): Seq[(String, String)] =
    runtime match
      case Linux(_, _) | Darwin(_) => Seq("CC" -> clang.getAbsolutePath.nn, "CXX" -> clangPP.getAbsolutePath.nn)
      case Windows(_, _)           => Seq.empty

  // The job count for `cmake --build --parallel`, bounded to the host's processor count. A bare --parallel defers to
  // the native tool's default, UNBOUNDED for a Makefiles generator - a fork bomb when several vendored builds run at
  // once; an explicit count bounds each build.
  private def buildJobs: Int = math.max(1, Runtime.getRuntime.nn.availableProcessors)

  private def fail(error: SNXError): Nothing = throw error // scalafix:ok DisableSyntax.throw

  private def run(environment: Seq[(String, String)], command: Seq[String], phase: String, log: Logger): Unit =
    if !succeeds(environment, command, log) then fail(SNXError.CMakeBuildFailed(s"snx: cmake $phase failed: ${command.mkString(" ")}"))

  // CMake writes its warnings to stderr, so stderr is logged at warn; a failure is the exit status, which the caller
  // raises as its own error.
  private def succeeds(environment: Seq[(String, String)], command: Seq[String], log: Logger): Boolean =
    Process(command, None, environment*).!(ProcessLogger(line => log.info(line), line => log.warn(line))) == 0
end Backend
