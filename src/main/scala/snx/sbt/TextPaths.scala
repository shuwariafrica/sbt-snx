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

// The paths a recipe's text names: where a path may begin and end in a token, and how a written path resolves on a
// platform. Pure over the platform's rules and a test of what exists, so its grammar runs as unit cases on any host;
// the key resolves what `scan` returns through the real filesystem.
private[sbt] object TextPaths:

  // Characters that end a candidate unless the path continues past them (see `scan`): whitespace, a quote and the
  // separators of lists and assignments; and the shell's operators and grouping, so a path glued to `&&`, `|`, `>` or
  // `)` ends there.
  private val delimiters: String = "\"';,=:"
  private val operators: String = "&|()<>`"
  private def break(char: Char): Boolean = char.isWhitespace || delimiters.contains(char) || operators.contains(char)

  // Whether the `\` at `at` escapes the character after it: on POSIX any break, as a shell reads `One\ Drive` or
  // `o\'brien`; on Windows, where `\` is a separator, only `"`.
  private def escapes(text: String, at: Int, windows: Boolean): Boolean =
    text.charAt(at) == '\\' && at + 1 < text.length && {
      val next = text.charAt(at + 1)
      if windows then next == '"' else break(next)
    }

  // `candidate` as the path it spells: on POSIX each escaping `\` dropped, the character it escapes kept.
  private def unescaped(candidate: String, windows: Boolean): String =
    if windows then candidate
    else candidate.indices.filterNot(at => escapes(candidate, at, windows)).map(candidate.charAt).mkString

  private def separator(char: Char, windows: Boolean): Boolean = char == '/' || (windows && char == '\\')

  // A drive at `at`, on Windows: a letter, `:`, and a separator; anywhere, so one glued to an option (`-IC:\x`,
  // `/FIC:\x`) counts. On POSIX `x:/` is no drive, and its `:` is the list separator it reads as.
  private def drive(text: String, at: Int): Boolean =
    at + 2 < text.length && text.charAt(at).isLetter && text.charAt(at + 1) == ':' &&
      (text.charAt(at + 2) == '/' || text.charAt(at + 2) == '\\')

  // The existing paths `text` names, resolved, in order of their first appearance. A candidate begins at the start of
  // the text; after a break, or `@`; at each separator; at a drive; and at `..`. It ends at a break, or at a `\` that
  // escapes the character after it (`\"`, and on POSIX `\ ` or `\'`), and continues across breaks to the longest text
  // that exists - an escaped character held in it without its `\` - crossing a word that holds a separator only where
  // the directory up to that separator exists, and a word without one only while the directory holding its last segment
  // exists. So a path holding any number of spaces, `'`, `=`, `,`, `:`, `;`, `&` or parentheses, in any segment, is
  // found whole. The existence tests counted per word: about two in a text naming no path, four after a path found, and
  // six after a missing file in a directory that exists. Starts inside a path already found are not tried, so its inner
  // suffixes (`/bin/env` within `/usr/bin/env`) are not keyed as paths of their own.
  def scan(text: String, directory: String, windows: Boolean, exists: String => Boolean): Seq[String] =
    val length = text.length
    def resolved(candidate: String): Option[String] =
      resolve(unescaped(candidate, windows), directory, windows).find(exists)
    // An escaping `\` breaks; the character it escapes does not, so a candidate continues across it only as the
    // continuation does, through the longer text existing.
    def breaksAt(at: Int): Boolean =
      escapes(text, at, windows) || (break(text.charAt(at)) && !(!windows && at > 0 && escapes(text, at - 1, windows)))
    def nextBreak(from: Int, start: Int): Int =
      (from until length)
        .find(at => breaksAt(at) && !(windows && text.charAt(at) == ':' && drive(text, at - 1) && at - 1 >= start))
        .getOrElse(length)
    // The first separator at or after each position, and the last one before it.
    val nextSeparator: IndexedSeq[Int] =
      (0 until length).scanRight(length)((at, after) => if separator(text.charAt(at), windows) then at else after)
    val lastSeparator: IndexedSeq[Int] =
      (0 until length).scanLeft(-1)((before, at) => if separator(text.charAt(at), windows) then at else before)
    def startsAt(at: Int): Boolean =
      val char = text.charAt(at)
      !breaksAt(at) && (at == 0 || breaksAt(at - 1) || text.charAt(at - 1) == '@' || separator(char, windows) ||
        (windows && drive(text, at)) || (char == '.' && at + 1 < length && text.charAt(at + 1) == '.'))
    // The longest existing candidate from `start`, as (end, resolved path).
    def longest(start: Int): Option[(Int, String)] =
      // Whether the directory holding the candidate's last segment exists, the segment ending at `end`.
      def holding(end: Int): Boolean =
        val at = lastSeparator(end)
        at >= start && resolved(text.substring(start, at + 1).nn).isDefined
      @scala.annotation.tailrec
      def extend(end: Int, best: Option[(Int, String)]): Option[(Int, String)] =
        if end >= length then best
        else
          val further = nextBreak(end + 1, start)
          val whole = resolved(text.substring(start, further).nn)
          if whole.isDefined then extend(further, whole.map(further -> _))
          else
            val crossing = nextSeparator(end + 1)
            val continues =
              if crossing < further then resolved(text.substring(start, crossing).nn).isDefined else holding(end)
            if continues then extend(further, best) else best
      val end = nextBreak(start, start)
      extend(end, resolved(text.substring(start, end).nn).map(end -> _))
    // Carried: the end of the last path found, so starts inside it are skipped, and the paths found so far.
    val (_, found) = (0 until length).foldLeft((0, Vector.empty[String])):
      case ((covered, found), at) =>
        if at >= covered && startsAt(at) then longest(at).fold((covered, found))((end, path) => (end, found :+ path))
        else (covered, found)
    found.distinct
  end scan

  // The absolute paths a written path may denote, in the order tried. POSIX: an absolute path as written; any other
  // against `directory`. Windows: a drive path (`C:\x`, `C:/x`) or a UNC path (`\\host\share`) as written; an msys or
  // Cygwin spelling (`/c/x`, `/cygdrive/c/x`) as its drive path, and then as rooted; a rooted path (`\x`) on the drive of
  // `directory`; any other against `directory`. An msys spelling is one written with `/`; one written with `\` is rooted.
  // Each is normalised lexically, `.` and `..` folded and, on Windows, its
  // separators written `/`.
  def resolve(path: String, directory: String, windows: Boolean): Seq[String] =
    if !windows then Seq(normalise(if path.startsWith("/") then path else s"$directory/$path", windows))
    else
      val slashed = path.replace('\\', '/')
      val msys = """^/(?:cygdrive/)?([A-Za-z])(/.*)?$""".r
      val directoryDrive =
        if directory.length >= 2 && directory.charAt(0).isLetter && directory.charAt(1) == ':' then directory.substring(0, 2).nn else ""
      val drivePath = slashed.length >= 2 && slashed.charAt(0).isLetter && slashed.charAt(1) == ':' &&
        (slashed.length == 2 || slashed.charAt(2) == '/')
      if drivePath then Seq(normalise(slashed, windows))
      else if slashed.startsWith("//") then Seq(normalise(slashed, windows))
      else
        // msys and Cygwin write `/` only, so a path written with `\` is rooted, never theirs.
        path match
          case msys(letter, rest) =>
            Seq(
              normalise(s"${letter.nn.toUpperCase}:${Option(rest).getOrElse("/")}", windows),
              normalise(directoryDrive + slashed, windows))
          case _ if slashed.startsWith("/") => Seq(normalise(directoryDrive + slashed, windows))
          case _                            => Seq(normalise(s"${directory.replace('\\', '/')}/$slashed", windows))
  end resolve

  // `.` and `..` folded lexically, repeated separators collapsed (a UNC path's leading two kept), and on Windows a drive
  // letter upper-cased.
  def normalise(path: String, windows: Boolean): String =
    val unc = windows && path.startsWith("//")
    val (head, rest) =
      if windows && path.length >= 2 && path.charAt(1) == ':' then (path.substring(0, 2).nn.toUpperCase.nn, path.substring(2).nn)
      else if unc then ("/", path.substring(1).nn)
      else ("", path)
    val absolute = rest.startsWith("/")
    val parts = rest.split('/').toList.filter(part => part.nonEmpty && part != ".")
    val folded = parts.foldLeft(List.empty[String]): (kept, part) =>
      if part == ".." then if kept.nonEmpty && kept.head != ".." then kept.tail else if absolute then kept else part :: kept
      else part :: kept
    head + (if absolute then "/" else "") + folded.reverse.mkString("/")
end TextPaths
