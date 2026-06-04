# sbt-snx

sbt-snx (sbt-native-extras) is an sbt 2.x plugin for Scala Native projects. It expresses per-platform
native concerns - dependency resolution, linker and compiler options, the native build configuration, and
classified publishing - as build settings keyed on a chosen OS/arch target and the toolchain's resolved C
library or ABI.

## Getting started

```scala
// project/plugins.sbt
addSbtPlugin("africa.shuwari" % "sbt-snx" % "<version>")
```

```scala
// build.sbt
enablePlugins(SNXPlugin) // Will enable `ScalaNativePlugin` via sbt `AutoPlugin` resolution
```

Settings and tasks are namespaced under an `SNX` object; the contributed types (`TargetPlatform`, `NativePlatform`,
`OS`, `Arch`, `NativeDependency`, ...) are auto-imported.

## Concepts

`TargetPlatform` pairs an `OS` (`Linux`/`Osx`/`Windows`) with an `Arch` (`X86_64`/`Aarch64`) - the
setting-time coordinate you choose. `NativePlatform` is the task-time key you match on to condition the
build: it adds the toolchain's C library or ABI, resolved from the native target triple (or the discovered
clang), so each case exposes only the values valid for that OS - `Linux(arch, LinuxLibc)` with
`Glibc`/`Musl`, `Osx(arch)`, and `Windows(arch, WindowsABI)` with `MSVC`/`MinGW`. You set a `TargetPlatform`;
you match on a `NativePlatform`. The resolved value is available as the `SNX.platform` task.

## Targets

`SNX.target` is the OS/arch to resolve and build for; it defaults to the build host. Override it to
cross-target:

```scala
SNX.target := TargetPlatform(OS.Osx, Arch.Aarch64)
```

`SNX.targets` is the set of targets the project declares support for; it defaults to the active `SNX.target`
alone. A build still resolves and builds a single target - pin `SNX.target` per build, for example a CI matrix
row - while `SNX.targets` records the full supported set. A `SNX.target` outside the declared set is allowed (a
cross or development build) and noted at load.

An unsupported operating system, architecture, or toolchain libc/ABI fails the build with
`UnsupportedTargetException`.

## Native dependencies

`SNX.dependencies` injects the `SNX.target` OS/arch classifier into each entry's coordinate and conditions
its native options on the resolved `NativePlatform`:

```scala
SNX.dependencies += "com.example" %% "blas" % "1.2"
```

The bare form is classified; `.plain` leaves it unclassified, for platform-independent NIR. Attach
per-platform options with `options`, matching the resolved platform to a `NativeOptions` bundle - linker flags
(`withLinking`) and compile options (`withCompile` for all native sources, `withC`/`withCpp` for C and C++
only). `NativeOptions()` is the empty bundle; `++` composes two bundles channel by channel:

```scala
SNX.dependencies += ("com.example" %% "uv" % "1.4" options {
  case NativePlatform.Linux(_, _) => NativeOptions().withLinking("-luv")
})

SNX.dependencies += ("com.example" %% "ssl" % "3" options {
  case NativePlatform.Linux(_, LinuxLibc.Glibc) =>
    NativeOptions().withLinking("-lssl") ++ NativeOptions().withCompile("-I/opt/ssl/include")
})
```

Options follow the dependency's configuration: a `Test` dependency contributes only to the test link.
Classifiers use the `os.detected.classifier` spelling (`linux-x86_64`, `osx-aarch_64`, `windows-aarch_64`),
interoperable with native artefacts published under that convention.

## Vendored native libraries

`SNX.vendored` builds native C/C++ libraries from source - or links ones already installed - and folds their archives
and headers into the native build for the resolved platform. It is the source-built counterpart to `SNX.dependencies`:

```scala
SNX.vendored += NativeSource.Local("crypto", NativeBackend.CMake(Seq("crypto")))
```

A `NativeBackend` builds the source; `CMake` configures, builds, installs (`cmake --install`), and collects the
installed archives and `include` directory, so the project must declare `install()` rules. The source is one of:

- `Local(name, backend)` - a local directory, defaulting to `vendor/<name>` (`Local(name, dir, backend)` takes an explicit path).
- `Git(name, uri, ref, backend)` - cloned at `ref` (a tag, commit, or branch). A branch is cloned once then cached and frozen per machine, so pin a tag or commit for a reproducible or updatable build.
- `System(name)` - link-only, for a library already present on the system; nothing is built.

Per-platform linker and compile flags use the same `NativeOptions` bundle as dependencies - for example a system
library the built archive links against:

```scala
SNX.vendored += NativeSource.Local("crypto", NativeBackend.CMake(Seq("crypto")))
  .options { case NativePlatform.Linux(_, _) => NativeOptions().withLinking("-lpthread") }
```

Each library builds once and is cached locally, keyed by its source, the resolved platform, and the toolchain, so a
toolchain change rebuilds rather than reusing an incompatible archive.

## Project native configuration

`SNX.config` is a list of per-platform transforms over the Scala Native `NativeConfig`, applied for the
resolved platform and inherited by the Compile and Test links. It is the place for whole-build settings -
linker and compile options, `LTO`, `Mode`, `GC` - that no single dependency owns. Declare them with `:=`:

```scala
SNX.config := Seq(
  { case NativePlatform.Linux(_, LinuxLibc.Musl) => c => c.withLinkingOptions(c.linkingOptions :+ "-static") },
  { case NativePlatform.Osx(_)                   => _.withLTO(LTO.full) }
)
```

For an incremental `+=`, wrap the literal in `nativeTransform` (a bare partial-function literal does not
infer against `+=`):

```scala
SNX.config += nativeTransform {
  case NativePlatform.Windows(_, WindowsABI.MSVC) => _.withGC(GC.none)
}
```

`LTO`, `Mode`, `GC`, `BuildTarget`, and `Sanitizer` are auto-imported for use in transforms. Where several
transforms match a platform, they compose in order, with scalar settings taking the last write.

## Per-platform sources and resources

When `SNX.Native / crossPaths` is `true` (see [Platform-specific projects](#platform-specific-projects)),
sbt-snx registers per-platform source and resource directories for the active `SNX.target` - only the active
target's, so platform code paths never co-compile. Absent directories are ignored.

In a plain Scala Native project the `scala`/`resources` directories are themselves native, so each gains `-<os>`
and `-<os>-<arch>` siblings (the `crossPaths` version dimension is carried):

```text
src/main/scala-linux            src/main/scala-linux-x86_64
src/main/scala-3-linux          src/main/scala-3-linux-x86_64
src/main/resources-linux        src/main/resources-linux-x86_64
```

In a native project matrix the platform-agnostic `scala`/`resources` are left untouched. The native
`scalanative` source directory (which the matrix provides) gains the suffixes; for resources - where sbt has no
native equivalent - sbt-snx registers a `resources-scalanative` directory (always, in a matrix) and suffixes it:

```text
src/main/scalanative-linux              src/main/scalanative-linux-x86_64
src/main/resources-scalanative-linux    src/main/resources-scalanative-linux-x86_64
src/main/resources-scalanative          (the native common dir, registered whenever the project is a matrix)
```

Native `.c`/`.cpp`/`.S` placed under a resource directory's `scala-native/` subdir (for example
`src/main/resources-linux/scala-native/`) are compiled into the link by the Scala Native toolchain;
platform-agnostic native code uses the standard `src/main/resources/scala-native/`.

## Platform-specific projects

A platform-specific project - one whose sources, resources, or published artifact differ by OS/arch - is
marked with `SNX.Native / crossPaths := true`:

```scala
SNX.Native / crossPaths := true
```

This single switch enables the per-platform source and resource directories above and the classified publishing
below. It defaults to `false`; a plain, platform-independent NIR library needs neither.

## Publishing

With `SNX.Native / crossPaths := true`, this project publishes its built native content under the `SNX.target`
OS/arch classifier, leaving the unclassified main artifact a placeholder. A per-platform NIR library is built
once per target - each build pins `SNX.target` - and publishes the classified jar to the shared coordinate;
consumers select the matching module through `SNX.dependencies`. Sources, javadoc, and the POM are published
as usual.

On sbt 2.0.0-RC14 the Ivy publish backend - which `publishSigned` (sbt-pgp) always uses - drops the Scala Native
platform suffix from published artifact filenames, which Maven rejects (sbt/sbt#9117). Apply
`SNX.platformPublishSettings` to bake the suffix into the published coordinate so the filenames are correct:

```scala
lazy val mylib = project.enablePlugins(SNXPlugin).settings(SNX.platformPublishSettings)
```

These settings are a temporary workaround; remove them once the upstream fix (sbt/sbt#9293) ships.

## Licence compliance

Statically linking a third-party C/C++ library into a Scala Native binary carries its licence obligations to whoever
distributes the binary. sbt-snx lets a library or application declare those licences, publishes them into its artifact
as an SPDX 2.3 document, and aggregates them at the final binary into one SPDX 2.3 document - so the published licence
data is readable by any SPDX/SBOM tool, not only sbt-snx. It is strict opt-in - nothing is published unless declared.

Declare a licence on a `NativeDependency` or a `NativeSource`. A single listed licence with its text is one line:

```scala
SNX.vendored += NativeSource.Local("zlib", NativeBackend.CMake(Seq("z")))
  .licensed("Zlib", file("LICENSE"))
```

The first argument is an SPDX licence expression, so compound and non-listed cases are expressible; a non-listed
licence ships its text under a `LicenseRef-` identifier, and other obligations attach fluently:

```scala
SNX.vendored += NativeSource.Local("crypto", NativeBackend.CMake(Seq("crypto")))
  .licensed("Apache-2.0", file("LICENSE")).notice(file("NOTICE"))           // an attribution notice to reproduce
  .source(uri("https://example.com/crypto-1.0.tar.gz"))                     // where the source is available
  .copyright("Copyright (c) the crypto authors")
  .identity("pkg:generic/crypto@1.0")                                       // a Package URL, for deduplication

("io.netty" % "netty-tcnative" % "2.0.65.Final").native
  .licensed("MIT OR Apache-2.0", LicenseText("MIT", file("MIT")), LicenseText("Apache-2.0", file("APACHE")))
```

A managed dependency derives its identity from its coordinate automatically. `relationship(...)` overrides how a
library links (`StaticLink`/`DynamicLink`/`Contains`/`DependsOn`); by default a built or compiled-in library links
statically and a `System` library dynamically. A licence file is resolved relative to its library's source - a
`Local` source's directory, or a `Git` source's clone - so the upstream `LICENSE` is referenced in place; resolution is
network-free, so a `Git` source's clone must already exist (build it first) or the text be vendored into the project.

A C library often vendors its own third-party libraries (libgit2 ships copies of zlib, llhttp, pcre2, and more), each
under its own licence. Declare them as contained components - each becomes its own package, contained by the wrapper:

```scala
SNX.vendored += NativeSource.Git("libgit2", "https://github.com/libgit2/libgit2.git", "v1.8.1", NativeBackend.CMake(Seq("git2")))
  .licensed("GPL-2.0-only WITH GCC-exception-2.0", file("COPYING"))
  .identity("pkg:github/libgit2/libgit2@v1.8.1")
  .bundles(
    Component("zlib", "Zlib", file("deps/zlib/LICENSE")).identity("pkg:generic/zlib@1.3.1"),
    Component("llhttp", "MIT", file("deps/llhttp/LICENSE-MIT")).identity("pkg:github/nodejs/llhttp@9.2.1"),
    Component("pcre2", "BSD-3-Clause", file("deps/pcre2/LICENCE"))
  )
```

Each artefact publishes its declarations as `META-INF/native-licenses/native-licenses.spdx.json` plus the texts. At the
final binary these are aggregated into a single SPDX 2.3 document and the accompanying texts, written beside the build
output. Producing a linked deliverable (`Compile / nativeLink`) does this automatically whenever the classpath declares
any native licences, so an application or a native library gets its aggregate without an extra step; a project that
declares none produces no report. To run it on demand - or for a test binary - use `SNX.licenseReport`, which is
config-scoped (`Compile` for an application, `Test` for a test binary):

```text
sbt Compile/snxLicenseReport
```

Libraries reaching the binary by several paths are deduplicated by identity (keeping every relationship edge), and only
those linked into it contribute - a build-only `DependsOn` is omitted from the binary's notices.

## Settings

| Setting                   | Type                          | Default                                      |
|---------------------------|-------------------------------|----------------------------------------------|
| `SNX.target`              | `TargetPlatform`              | the build host                               |
| `SNX.targets`             | `Seq[TargetPlatform]`         | the active `SNX.target` alone                |
| `SNX.platform`            | `NativePlatform` (task)       | resolved from `SNX.target` and the toolchain |
| `SNX.dependencies`        | `Seq[NativeDependency]`       | empty                                        |
| `SNX.vendored`            | `Seq[NativeSource]`           | empty                                        |
| `SNX.vendoredArtefacts`   | `Seq[NativeArtefacts]` (task) | built from `SNX.vendored`                    |
| `SNX.config`              | `Seq[NativeTransform]`        | empty                                        |
| `SNX.licenseReport`       | `File` (task)                 | aggregated SPDX document beside the output   |
| `SNX.Native / crossPaths` | `Boolean`                     | `false`                                      |

## License

Apache License 2.0. Copyright Shuwari Africa Ltd.
