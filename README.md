# sbt-snx

sbt-snx is an sbt 2.x plugin for Scala Native projects, Scala 3 only. It drives the Scala Native toolchain directly and
models the native target - the OS/arch you build for and the toolchain's resolved C library or ABI - as build settings
and tasks.

> **Status:** pre-release and under active development. The API is not yet stable.

> **Most Scala Native projects do not need this plugin.** Use the official, battle-tested
> [sbt-scala-native](https://scala-native.org) plugin unless you need per-OS/arch classified resolution and publishing,
> automatic propagation of native link requirements (system libraries, frameworks, etc) across the dependency graph,
> direct per-platform control of the native build configuration, or C/C++ libraries built from source and linked into
> the native build.

## Getting started

```scala
// project/plugins.sbt
addSbtPlugin("africa.shuwari" % "sbt-snx" % "<version>")
```

```scala
// build.sbt
enablePlugins(SNXPlugin)
```

Enabling the plugin adds the Scala Native compiler plugin and runtime libraries. Settings and tasks are namespaced under
an `SNX` object; the platform types (`TargetPlatform`, `OS`, `Arch`, `NativeRuntime`, `ABI`) are auto-imported.

## The platform model

`TargetPlatform` pairs an `OS` (`Linux`/`Darwin`/`Windows`) with an `Arch` (`X86_64`/`Aarch64`) - the setting-time
coordinate, defaulting to the build host. `NativeRuntime` is the task-time key matched against: it refines the
target with the toolchain `ABI` - the C library on Linux (`Glibc`/`Musl`), the runtime ABI on Windows (`Msvc`/`MinGw`) -
resolved from the native target triple, so each case exposes only the values valid for its operating system:

- `Linux(arch, abi)`
- `Darwin(arch)`
- `Windows(arch, abi)`

Determining the current configured `TargetPlatform` can be achieved in tasks by maching against a `NativeRuntime`. An
unsupported operating system, architecture, or toolchain ABI fails the build with `SNXError.UnsupportedTarget`.

## Deliverables, linkage, and the build

A project produces one `SNX.deliverable`, and that choice fully describes the artefact: `NIR` (the default - a
platform-independent jar that a downstream Scala Native build resolves and links), `Library.Static` (a `.a`/`.lib`
archive), `Library.Shared` (a `.so`/`.dylib`/`.dll`), or `Executable`.

Own code is always compiled into the binary, so "linkage" is not one deliverable-wide setting but two orthogonal
concerns: how each **C library** binds (`NativeLibrary.linkage`, below), and whether the **C runtime** links
statically. The latter is an explicit opt-in - add the `SNX.staticRuntime` modifier to the final binary you want it on:

```scala
SNX.modifiers      += SNX.staticRuntime // the deliverable (an Executable, or a Library.Shared)
Test / SNX.modifiers += SNX.staticRuntime // the always-application test binary only, leaving the deliverable untouched
```

It renders `-static` on musl (a fully static binary, libc and all) and `/MT` (`-fms-runtime-lib=static`) on MSVC (a
static CRT; the Win32 system DLLs stay dynamic, since Windows has no fully-static form). It fails fast on glibc, macOS,
and MinGW, which cannot link a static C runtime. Per-library static linking (a static C library alongside a dynamic
libc) is a separate `NativeLibrary` concern, below.

A vendored C library must match this CRT choice, or the link mixes runtimes: on MSVC, build it against the same static
CRT by passing `-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded$<$<CONFIG:Debug>:Debug>` (with
`-DCMAKE_POLICY_DEFAULT_CMP0091=NEW`) through the CMake backend's per-platform `flags`; on musl, link it statically (the
`Vendored` default), since a fully static binary cannot load a shared library.

`SNX.link` links the binary for the enclosing configuration. `run` runs it, forwarding arguments and `run / envVars`;
`test` runs the project's test frameworks as a native binary (it requires `Test / fork := false`). `run`, `runMain`,
and `test` override sbt's defaults.

## Native configuration

`SNX.config` is the resolved Scala Native configuration: the discovered toolchain, then the scalar settings
(`SNX.mode`, `SNX.gc`, `SNX.lto`, `SNX.optimize`, `SNX.sanitizer`, `SNX.multithreading`), then the propagated link
requirements (from `SNX.libraries`, `SNX.flags`, and the classpath's descriptors), then the matched per-platform
`SNX.modifiers`, applied last so a modifier has the final say. A `Modifier[Native]` is a partial function from the
resolved `NativeRuntime` to a `Native` transform, carried with `Modifier.platform`. The `Native` surface offers the raw
option channels (`linkOptions`, `compileOptions`, `cOptions`, `cppOptions`), the structured `library`/`include`/
`define`, the scalar settings above, `embedResources`/`debugSymbols`/`finalFields`/`linktimeProperty`, and `update` for
anything else. `Modifier.wholeArchive(archive)` force-links a whole static archive in each platform's linker syntax.

### Build modes

`SNX.mode` selects Scala Native's build mode, which sets the optimisation level: `Mode.debug` (the default) is `-O0`,
for fast, debuggable development builds; `Mode.releaseFast` is `-O2`, `Mode.releaseFull` is `-O3`, and `Mode.releaseSize`
optimises for size. A release build is one line:

```scala
SNX.mode := Mode.releaseFast
```

`SNX.lto := LTO.thin` (or `LTO.full`) adds link-time optimisation across the project's own and bundled C (`-flto`); it is
off by default and thin LTO is unsupported when targeting macOS. A `NIR` deliverable is mode-independent - it ships raw
NIR and the consumer's own mode optimises at their link - so only `Executable` and `Library` deliverables need a mode set
here.

A vendored library follows `SNX.mode`: its CMake `CMAKE_BUILD_TYPE` is `Debug` for `Mode.debug`, `MinSizeRel` for
`Mode.releaseSize`, and `Release` otherwise, and the vendored build cache keys on it (a dev `Debug` build and a release
build are cached separately). LTO does not extend into a vendored build (it is a separate compilation) - pass `-flto`
through the backend's own `cmake(targets, flags)` if you need it there.

`SNX.includeDirs` and `SNX.libDirs` add `-I`/`-L` directories - host-discovered paths are dropped when cross-targeting,
so a cross build is not contaminated by the host toolchain's directories - and `SNX.clang`/`SNX.clangPP` override the
discovered compilers. `.c`, `.cpp`, and `.S` sources under `src/main/resources/scala-native/` are compiled into the
binary at link time.

## Native dependencies

`SNX.dependencies` carries managed native dependencies - those resolved per OS/arch under a classifier. `% NativeClassifier`
resolves a dependency under the build's OS/arch classifier; plain JVM or NIR dependencies may stay in `libraryDependencies`:

```scala
SNX.dependencies += "org.acme" %% "fastmath" % "1.2" % NativeClassifier
```

A managed native dependency is a pure coordinate. Its link requirements arrive through its own published descriptor,
recursively, so a consumer that resolves it gets them automatically - it never restates them.

## Native libraries

`SNX.libraries` declares the native C-world libraries a link requires, per platform. A `NativeLibrary` is one declared
unit: a linker `name`, a link mode (a plain `-l`, a macOS framework, or a whole-archive force-load), and a provisioning -
how this project supplies the library's symbols and headers:

- **System** (the default): provided by the operating system, linked as `-l<name>`.
- **Vendored**: built from source and folded into the link (below).
- **Unmanaged**: compiled in from `.c`/`.cpp`/`.S` sources under `src/main/resources/scala-native/`.

```scala
SNX.libraries := {
  case Linux(_, _)   => Seq(NativeLibrary("z"), NativeLibrary("ssl"))
  case Darwin(_)     => Seq(NativeLibrary.framework("Security"))
  case Windows(_, _) => Seq(NativeLibrary("zlib"))
}
```

One declaration drives both this project's own link and - for the name and link mode alone, never the provisioning - the
published descriptor. So a published NIR library whose bundled C needs `-lz` declares it once; a consumer resolving the
library folds the descriptor for its own runtime and links `-lz` automatically, however deep the library sits in the
dependency graph. A consuming application never restates it. (Scala Native already propagates the name of a
`@link`-annotated binding; `SNX.libraries` is for the libraries the bundled C needs that it does not.)

Publisher and consumer declare a library the same way, and a matching name **rebinds**: a consumer that provisions a
library the descriptor named - say, vendoring from source a library an upstream linked from the system - declares the
same `NativeLibrary` name with its own provisioning, and the link realises it from that provisioning instead of the
default `-l<name>`. When a project provisions libraries locally, the link reports how each requirement resolved -
rebound to a vendored or unmanaged provisioning, or left to its default `-l<name>` - so a mistyped provisioning is
visible.

`.wholeArchive` force-loads every member of a library (for example to keep `__attribute__((constructor))`
registrations); `NativeLibrary.framework(name)` is a macOS framework, contributing nothing elsewhere. On macOS a
whole-archive is by archive path (`-force_load`), not by name, so a whole-archive **System** library fails fast there -
provision it `Vendored` (its built archive is force-loaded by path) or force-load a path with `Modifier.wholeArchive(file)`.
A library declared `.noSystemDefault` that no provisioning supplies fails the build with a directed message rather than an
unresolved `-l<name>` at link time.

`.linkage` sets how a library is linked, per platform - a decision independent of its provisioning. Unset, a library
follows its provisioning's default (System dynamic, Vendored static); a bare `Static`/`Dynamic` lifts to a constant. A
static system library links via the GNU and MinGW bracket (`-Wl,-Bstatic -l<name> -Wl,-Bdynamic`, keeping libc dynamic) -
so a dynamic deliverable with libraries linked `Static` is a maximal-static build (own code and each library static, libc
dynamic) that works on glibc, where a fully static executable does not. It needs the library's static archive present
(installed, or provisioned `Vendored`, which builds one).

```scala
SNX.libraries += NativeLibrary("z").linkage { case Linux(_, _) | Windows(_, MinGw) => Static } // MSVC and macOS fall through
```

Forcing a static link the platform cannot honour fails fast: MSVC has no `-Bstatic` (static selection is by lib name, so
name the static `.lib` or provision `Vendored`), macOS cannot force a system `-l` static (provision it `Vendored` for a
static archive), and a framework cannot be linked statically at all. A `Vendored` library links static or dynamic exactly
as a `System` one: the provisioning only builds the artefact the linkage needs - a static archive, or a shared library the
dynamic link references (`-l<name> -L<builtdir>`) and the target supplies at runtime. A whole-archive library cannot be
linked dynamically (whole-archive is a static-archive operation), and a dynamically-linked vendored library on Windows
(DLL redistribution) is a follow-on.

A library carries the configurations it applies to in its own definition, like a managed dependency: `NativeLibrary("z")
% Test` scopes it to the test link (it folds into the test binary's link and does not export), while a library with no
configuration applies to every link and exports.

### Source-built C libraries

A `Vendored` provisioning builds a C/C++ library from source and folds it into the link. It is local to the build and
never published - the built archive folds into a link directly, and a library that ships C publishes that C as source,
so a vendored provisioning for a NIR library is scoped `% Test`, for the binding tests' own link (below).

```scala
SNX.libraries += NativeLibrary(
  "mylib",
  Vendored.local("vendor/mylib").cmake("mylib").options { case Linux(_, _) => Flags.libraries("m") })
```

`local(directory)` builds a directory under the project (resolved against the project, then the build root);
`git(uri, ref)` clones a Git repository at a `ref` (a tag, commit, or branch), resolving the ref to its current commit
and caching on that commit, so moving a branch or force-moving a tag rebuilds - a full commit SHA needs no resolution,
pin one for a network-free, fully reproducible build. `cmake` configures, builds, installs, and collects **every** library and
header under the install prefix - not only the `targets` you name, but whatever the project's `install()` rules emit, so
a multi-library project (aws-lc installs both `libssl` and `libcrypto`) contributes them all; scope the project's install
(or its `targets`) to what you need. It builds them static or shared per the library's `.linkage` (`BUILD_SHARED_LIBS` is
derived - a static archive by default, a shared library under `.linkage(Dynamic)`), and per-platform configure flags pass
as `cmake(targets, flags)`. `options` adds the library's per-platform link closure -
the transitive `-l`/flags/defines a static archive cannot carry itself - applied at this provisioning site and never
published (distinct from the CMake configure `flags`). The build runs in a normal toolchain environment, so a
CMakeLists using `find_package` or a toolchain file behaves as it does standalone. Builds are cached locally, keyed on
the source (a `local` directory's content hash, or a `git` origin's resolved commit - the ref is resolved via
`git ls-remote`, so a moving branch or force-moved tag rebuilds; when the remote is unreachable the cache falls back to
the ref), the configuration, and the resolved toolchain.

On Linux and macOS the CMake backend builds with the same clang the Scala Native link uses (honouring an `SNX.clang`
override); on Windows it uses CMake's default MSVC toolchain, which is ABI-compatible with the Scala Native link. It is
not supported on Windows MinGW - MSVC is the supported Windows toolchain - so a vendored CMake library there fails the
build with a clear error rather than producing an unlinkable archive.

For a build CMake does not cover - Make, Autotools, a hand-rolled script - `command(token) { ctx => ... }` is the
escape hatch: the function builds from `ctx.source` into `ctx.staging` and returns the archives and header directories
to fold in (an `Artefacts`, whose paths must lie under `ctx.staging` so they are cached). `command` allows use of any
toolchain - MinGW included.

`token` is the build's cache identity, and keeping it correct is your responsibility. The function is opaque to the
plugin, so the cache keys on `token` alone - the source content, the toolchain, and the resolved runtime/linkage/mode
are tracked for you, but a change to the function's own logic is invisible. Change `token` on every change to the build
- a flag, a target, the recipe - or a warm cache (a persisted CI store, a local rebuild) silently reuses the previous
archive and a green build links a stale one. Derive it from the build's inputs so editing them updates it:

```scala
val cflags = Seq("-fms-runtime-lib=static")
Vendored.local("vendor/mylib").command(s"mylib-${cflags.mkString("_")}") { ctx => /* build using cflags */ }
```

### Caching vendored builds in CI

A vendored build is cached locally only (`CacheLevelTag.Local`) - a compiled archive is not portable across toolchains,
so it is never shared through a remote cache. A fresh CI runner therefore rebuilds each vendored library from source
unless you persist the sbt local cache store (the `SBT_LOCAL_CACHE` directory, for example `~/.cache/sbt`) between runs.
Doing so turns an expensive source build - an aws-lc or similar - from minutes per matrix cell into seconds, and it is
safe: the cache key includes the resolved toolchain (the compilers and their versions, `cmake`, and the ambient
`CFLAGS`/`CXXFLAGS`/`CPPFLAGS`), so a runner-image or compiler change misses and rebuilds rather than reusing a stale
archive. For a `command` build the toolchain is tracked the same way, but the build logic is keyed only by its `token`
(above) - so a persisted cache is only as correct as your token hygiene.

## Other link requirements

`SNX.flags` carries the non-library requirements per platform: preprocessor `defines` (`-D`) a consumer's own C must set
to match a library's headers (a NIR `@define` already propagates on its own), raw `linkFlags`, and a `multithreaded`
requirement (a force-on, not an option - the consumer's resolved configuration must satisfy it). Like `SNX.libraries`,
these apply to this project's own link and travel in its published descriptor, so they reach downstream consumers.

```scala
SNX.flags := { case Windows(_, _) => Flags.defines("WIN32") }
```

## Publishing

A plain NIR library publishes a single, platform-independent jar that carries its descriptor. A per-platform NIR
library - one whose compiled NIR genuinely differs per target - sets `SNX.classified := true` and is built once per
target (each pinning `SNX.target`); each build publishes its content and descriptor under the build's OS/arch
classifier (`name_native0.5_3-version-os-arch.jar`), leaving a manifest-only placeholder on the unclassified main
coordinate. A consumer resolves such a dependency with `% NativeClassifier`. Either way, propagation is automatic.

## Settings and tasks

| Key                  | Type                                          | Default                           |
|----------------------|-----------------------------------------------|-----------------------------------|
| `SNX.target`         | `TargetPlatform`                              | the build host                    |
| `SNX.runtime`        | `NativeRuntime` (task)                        | resolved from target + toolchain  |
| `SNX.deliverable`    | `Deliverable`                                 | `NIR`                             |
| `SNX.mode` `.gc` `.lto` `.optimize` `.sanitizer` `.multithreading` | scalars             | Scala Native's defaults           |
| `SNX.clang` `.clangPP` | `Option[File]`                              | discovered `clang` / `clang++`    |
| `SNX.includeDirs` `.libDirs` | `Seq[File]`                           | empty (host paths cross-stripped) |
| `SNX.modifiers`      | `Seq[Modifier[Native]]`                       | empty                             |
| `SNX.dependencies`   | `Seq[NativeDependency]`                       | empty                             |
| `SNX.libraries`      | `PartialFunction[NativeRuntime, Seq[NativeLibrary]]` | empty (named native libraries) |
| `SNX.flags`          | `PartialFunction[NativeRuntime, Flags]`       | empty (defines/linkFlags/MT)      |
| `SNX.config`         | `Native` (task)                               | resolved configuration            |
| `SNX.link`           | `File` (task)                                 | links the binary                  |
| `SNX.classified`     | `Boolean`                                     | `false` (publish per OS/arch)     |

`SNX.host` is the build host's `TargetPlatform`. `run`, `runMain`, and `test` are overridden for Scala Native.

## Example

A multi-module Scala Native project with all three deliverables: a per-platform NIR library, a native library, and an
application that consumes both.

```scala
// build.sbt
scalaVersion := "3.8.4"
organization := "com.example"
version      := "0.1.0"

// A per-platform NIR library: its bundled C needs a system library, which it declares once via `SNX.libraries`.
// Published per OS/arch as a classified jar that carries the descriptor.
val core = project
  .enablePlugins(SNXPlugin)
  .settings(
    SNX.classified := true,
    SNX.libraries := {
      case Linux(_, _)   => Seq(NativeLibrary("z"))
      case Darwin(_)     => Seq(NativeLibrary.framework("Security"))
      case Windows(_, _) => Seq(NativeLibrary("zlib"))
    }
  )

// A native library, emitted as a shared object (.so/.dylib/.dll); use Library.Static for a .a/.lib archive instead.
val engine = project
  .enablePlugins(SNXPlugin)
  .dependsOn(core)
  .settings(SNX.deliverable := Library.Shared)

// The application, with a per-platform tweak. It consumes both modules and never restates core's link requirements:
// core's descriptor propagates them into this link automatically.
val app = project
  .enablePlugins(SNXPlugin)
  .dependsOn(core, engine)
  .settings(
    SNX.deliverable := Executable,
    SNX.modifiers   += Modifier.platform { case Linux(_, Glibc) => _.lto(LTO.thin) }
  )
```

When `app` links, it folds the descriptors on its classpath, so `core`'s per-platform requirements - `-lz` on Linux,
`-framework Security` on macOS, `-lzlib` on Windows - are applied to the link automatically. Neither `app` nor `engine`
declares them.

## Cross-building with a project matrix

sbt 2.x's built-in project matrix cross-builds one source set across platforms. Its `nativePlatform` row is bound to the
official Scala Native plugin, so sbt-snx adds `snxPlatform`, enabling sbt-snx on a Scala Native row instead:

```scala
val core = projectMatrix
  .jvmPlatform(scalaVersions = Seq("3.8.4"))
  .snxPlatform(scalaVersions = Seq("3.8.4"), settings = Seq(SNX.deliverable := Executable))
```

This expands into a JVM subproject (`core`) and a Scala Native subproject (`coreNative`), each compiling its shared
`scala` sources plus its own platform source directory (`scalajvm` / `scalanative`). `snxPlatform` mirrors
`nativePlatform`'s overloads, also taking `axisValues` and either `settings` or a `configure: Project => Project` for
per-row configuration, so a build can migrate from the official plugin by replacing `nativePlatform` with `snxPlatform`.

## License

Apache License 2.0. Copyright Shuwari Africa Ltd.
