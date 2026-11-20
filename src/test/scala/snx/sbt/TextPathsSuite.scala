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

// The text scanner's grammar: every token form a recipe writes, crossed with every spelling of a path on the platform
// whose rules it names, each run through TextPaths.scan over a filesystem holding the path, its parents and the host's
// roots, and required to find exactly the path it names (and, for a PATH list, the list's other entry). Pure over the
// platform's rules, so every row runs on every host.
class TextPathsSuite extends munit.FunSuite:

  final private case class Spelling(name: String, windows: Boolean, written: String, resolved: String)

  private val posixStep = "/w/stage"
  private val windowsStep = "D:/w/stage"

  private val spellings: Seq[Spelling] = Seq(
    Spelling("POSIX absolute", false, "/p/inc/x.h", "/p/inc/x.h"),
    Spelling("POSIX relative, `..`", false, "../src/inc/x.h", "/w/src/inc/x.h"),
    Spelling("POSIX `..` inside", false, "/p/q/../inc/x.h", "/p/inc/x.h"),
    Spelling("POSIX holding a space", false, "/p q/inc/x.h", "/p q/inc/x.h"),
    Spelling("POSIX holding `'`", false, "/p'q/inc/x.h", "/p'q/inc/x.h"),
    Spelling("POSIX holding `=`", false, "/p=q/inc/x.h", "/p=q/inc/x.h"),
    Spelling("POSIX holding `,`", false, "/p,q/inc/x.h", "/p,q/inc/x.h"),
    Spelling("POSIX holding `:`", false, "/p:q/inc/x.h", "/p:q/inc/x.h"),
    Spelling("POSIX holding `;`", false, "/p;q/inc/x.h", "/p;q/inc/x.h"),
    Spelling("POSIX folder holding two spaces", false, "/h/One Drive - Co/inc/x.h", "/h/One Drive - Co/inc/x.h"),
    Spelling("POSIX folder holding a double space", false, "/p/a  b/x.h", "/p/a  b/x.h"),
    Spelling("POSIX file name holding two spaces", false, "/p/inc/my big file.h", "/p/inc/my big file.h"),
    Spelling("POSIX holding `&` and parentheses", false, "/p/R&D (x86)/inc/x.h", "/p/R&D (x86)/inc/x.h"),
    Spelling("POSIX two folders holding spaces", false, "/p/a b/c d/x.h", "/p/a b/c d/x.h"),
    Spelling("POSIX escaped space", false, "/p/my\\ dir/x.h", "/p/my dir/x.h"),
    Spelling("POSIX several escaped spaces", false, "/h/One\\ Drive\\ -\\ Co/inc/x.h", "/h/One Drive - Co/inc/x.h"),
    Spelling("POSIX escaped `'`", false, "/p/o\\'brien/x.h", "/p/o'brien/x.h"),
    Spelling("Windows drive, `\\`", true, "C:\\p\\inc\\x.h", "C:/p/inc/x.h"),
    Spelling("Windows drive, `/`", true, "C:/p/inc/x.h", "C:/p/inc/x.h"),
    Spelling("Windows drive, mixed separators", true, "C:\\p/inc\\x.h", "C:/p/inc/x.h"),
    Spelling("Windows drive, lower case", true, "c:\\p\\inc\\x.h", "C:/p/inc/x.h"),
    Spelling("Windows rooted, no drive", true, "\\proj\\inc\\x.h", "D:/proj/inc/x.h"),
    Spelling("Windows rooted, one-letter segment", true, "\\p\\inc\\x.h", "D:/p/inc/x.h"),
    Spelling("Windows UNC", true, "\\\\srv\\share\\x.h", "//srv/share/x.h"),
    Spelling("msys", true, "/c/p/inc/x.h", "C:/p/inc/x.h"),
    Spelling("Cygwin", true, "/cygdrive/c/p/inc/x.h", "C:/p/inc/x.h"),
    Spelling("Windows relative, `..`", true, "..\\src\\inc\\x.h", "D:/w/src/inc/x.h"),
    Spelling("Windows holding a space", true, "C:\\Program Files\\p\\x.h", "C:/Program Files/p/x.h"),
    Spelling("Windows holding `'`", true, "C:\\o'brien\\x.h", "C:/o'brien/x.h"),
    Spelling("Windows holding `&` and parentheses", true, "C:\\R&D (x86)\\p\\x.h", "C:/R&D (x86)/p/x.h"),
    Spelling(
      "Windows OneDrive for Business",
      true,
      "C:\\Users\\jane\\OneDrive - Contoso\\proj\\inc\\x.h",
      "C:/Users/jane/OneDrive - Contoso/proj/inc/x.h"),
    Spelling(
      "Windows Visual Studio projects",
      true,
      "C:\\Users\\jane\\Documents\\Visual Studio 2022\\Projects\\p\\x.h",
      "C:/Users/jane/Documents/Visual Studio 2022/Projects/p/x.h"
    ),
    Spelling("Windows file name holding two spaces", true, "C:\\p\\inc\\my big file.h", "C:/p/inc/my big file.h"),
    Spelling("Windows `Program Files (x86)`", true, "C:\\Program Files (x86)\\p\\x.h", "C:/Program Files (x86)/p/x.h"),
    Spelling(
      "Windows two folders holding spaces",
      true,
      "C:\\Users\\jane\\OneDrive - Contoso\\My Project\\x.h",
      "C:/Users/jane/OneDrive - Contoso/My Project/x.h"),
    Spelling("msys folder holding two spaces", true, "/c/Users/jane/OneDrive - Contoso/x.h", "C:/Users/jane/OneDrive - Contoso/x.h")
  )

  // The PATH list's other entry, written where the form's NUL stands.
  private val listed = "\u0000"

  // Each form: its name and how it writes a path `p` into one argument.
  private val forms: Seq[(String, String => String)] = Seq(
    "`-I<p>`" -> (p => s"-I$p"),
    "`-include <p>`" -> (p => s"-include $p"),
    "`/FI<p>`" -> (p => s"/FI$p"),
    "`/I<p>`" -> (p => s"/I$p"),
    "`--opt=<p>`" -> (p => s"--with-x=$p"),
    "`@<p>`" -> (p => s"@$p"),
    "`KEY=<p>`" -> (p => s"CFLAGS=$p"),
    "`PATH` list" -> (p => s"$p$listed"),
    "quoted" -> (p => "\"" + p + "\""),
    "option, quoted" -> (p => "-I\"" + p + "\""),
    "escaped quotes" -> (p => "CFLAGS=-DCFG=\\\"" + p + "\\\""),
    "glued `&&`" -> (p => s"make -f $p&&echo"),
    "glued `|`" -> (p => s"cat $p|cc"),
    "`$(...)`" -> (p => "-DV=$(cat " + p + ")"),
    "`-Wl,<p>`" -> (p => s"-Wl,--version-script,$p"),
    "single-quoted" -> (p => s"'$p'"),
    "after a tab" -> (p => s"-O2\t$p"),
    "after a newline" -> (p => s"-O2\n$p"),
    "`-include <p>` before further words" -> (p => s"-include $p -O2 -Wall"),
    "`CC=<p>` before further options" -> (p => s"CC=$p --target=x86_64-pc-windows-msvc -fms-runtime-lib=static")
  )

  // `path` and each directory above it, up to its root or drive.
  private def parents(path: String): Seq[String] =
    val cut = path.lastIndexOf('/')
    if cut <= 0 || (path.length > 2 && path.charAt(1) == ':' && cut == 2) then Seq(path)
    else path +: parents(path.substring(0, cut).nn)

  for
    (form, write) <- forms
    spelling <- spellings
  do
    test(s"grammar $form / ${spelling.name}"):
      val other = if spelling.windows then "C:/tools/bin" else "/tools/bin"
      val list = if spelling.windows then ";C:\\tools\\bin" else ":/tools/bin"
      val text = write(spelling.written).replace(listed, list)
      val isList = form == "`PATH` list"
      // What exists: the path, its parents, and the roots a host always has - `/`, or the path's drive and the step's.
      val roots =
        if spelling.windows then Seq("D:/") ++ (if spelling.resolved.charAt(1) == ':' then Seq(spelling.resolved.take(2) + "/") else Nil)
        else Seq("/")
      val existing = (parents(spelling.resolved) ++ (if isList then parents(other) else Nil) ++ roots).toSet
      val found = TextPaths.scan(text, if spelling.windows then windowsStep else posixStep, spelling.windows, existing.contains)
      val expected = Seq(spelling.resolved) ++ (if isList then Seq(other) else Nil)
      assertEquals(found.sorted, expected.sorted, text)

  test("grammar maximal munch: no path inside a path found"):
    val existing = Set("/usr", "/usr/bin", "/usr/bin/env", "/bin", "/bin/env")
    assertEquals(TextPaths.scan("/usr/bin/env", posixStep, false, existing.contains), Seq("/usr/bin/env"))

  test("on Windows a backslash before a space is a separator, not an escape"):
    val existing = Set("C:/", "D:/", "C:/p", "C:/p/my", "C:/p/my/ dir")
    assertEquals(TextPaths.scan("C:\\p\\my\\ dir", windowsStep, true, existing.contains), Seq("C:/p/my/ dir"))

  // The scan's cost, as existence tests counted over the text: linear in its words, so an argument of hundreds of flags
  // is cheap to key on every build.
  private def tests(text: String, existing: Set[String]): Int =
    val count = new java.util.concurrent.atomic.AtomicInteger(0)
    val _ = TextPaths.scan(
      text,
      posixStep,
      false,
      path =>
        val _ = count.incrementAndGet()
        existing.contains(path))
    count.get

  private val words = (1 to 800).map(index => s"-Wflag$index").mkString(" ")

  // At most `perWord` tests for each of the 800 words, and as many again for the path before them.
  private def linear(count: Int, perWord: Int): Unit =
    assert(count <= perWord * 801, s"$count existence tests for 800 words, over $perWord per word")

  test("the scan tests at most two candidates per word of a text naming no path"):
    linear(tests(words, Set("/")), 2)

  test("a path found before 800 words costs at most four tests per word"):
    linear(tests(s"-include /p/x.h $words", Set("/", "/p", "/p/x.h")), 4)

  test("a missing file in an existing directory before 800 words costs at most six tests per word"):
    linear(tests(s"-include /p/missing.h $words", Set("/", "/p")), 6)
end TextPathsSuite
