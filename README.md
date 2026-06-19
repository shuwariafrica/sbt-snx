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
coordinate you choose, defaulting to the build host. `NativeRuntime` is the task-time key you match on: it refines the
target with the toolchain `ABI` - the C library on Linux (`Glibc`/`Musl`), the runtime ABI on Windows (`Msvc`/`MinGw`)
- resolved from the native target triple, so each case exposes only the values valid for its operating system:
`Linux(arch, abi)`, `Darwin(arch)`, and `Windows(arch, abi)`. You set a `TargetPlatform`; you match on a
`NativeRuntime`. An unsupported operating system, architecture, or toolchain ABI fails the build with
`SNXError.UnsupportedTarget`.

## Deliverables, linkage, and the build

A project produces one `SNX.deliverable`: `NIR` (the default - a platform-independent jar that a downstream Scala
Native build resolves and links), a native `Library` (a `.so`/`.dylib`/`.dll`, or a `.a`/`.lib` when linked
statically), or an `Executable`. `SNX.linkage` selects `Static` or `Dynamic` linking per platform (default `Dynamic`);
a static `Executable` is supported only where the toolchain allows it (musl or MSVC) and fails fast elsewhere.

`SNX.link` links the binary for the enclosing configuration. `run` runs it, forwarding arguments and `run / envVars`;
`test` runs the project's test frameworks as a native binary (it requires `Test / fork := false`). `run`, `runMain`,
and `test` override sbt's defaults.

## Native configuration

`SNX.config` is the resolved Scala Native configuration: the discovered toolchain, then the scalar settings
(`SNX.mode`, `SNX.gc`, `SNX.lto`, `SNX.optimize`, `SNX.sanitizer`, `SNX.multithreading`), then the matched per-platform
`SNX.modifiers`, applied last so a modifier has the final say. A `Modifier[Native]` is a partial function from the
resolved `NativeRuntime` to a `Native` transform, carried with `Modifier.platform`. The `Native` surface offers the raw
option channels (`linkOptions`, `compileOptions`, `cOptions`, `cppOptions`), the structured `library`/`include`/
`define`, the scalars, and `embedResources`/`finalFields`/`debugSymbols`/`linktimeProperty`, with `update` for anything
else. `Modifier.wholeArchive(archive)` force-links a whole static archive in each platform's linker syntax.
`SNX.includeDirs` and `SNX.libDirs` add `-I`/`-L` directories - host-discovered paths are dropped when cross-targeting,
so a cross build is not contaminated by the host toolchain's directories - and `SNX.clang`/`SNX.clangPP` override the
discovered compilers. `.c`, `.cpp`, and `.S` sources under `src/main/resources/scala-native/` are compiled into the
binary at link time.

## Native dependencies

`SNX.dependencies` carries classified or requirement-bearing native dependencies; plain JVM/NIR dependencies may stay in
`libraryDependencies`. `% NativeClassifier` resolves a dependency under the build's OS/arch classifier, and `options`
attaches the per-platform link `Usage` requirements a dependency needs but does not declare itself - useful for an
under-declaring dependency that ships no descriptor of its own:

```scala
SNX.dependencies += "org.acme" %% "blas" % "0.9" % NativeClassifier options {
  case Linux(_, _) => Usage.libraries("m")
}
```

These requirements fold into this project's own link and propagate into its published descriptor, so they reach
downstream consumers too.

## Source-built C libraries

`SNX.vendored` builds a C/C++ library from source and folds it into the native link - the source-built counterpart to
the managed `SNX.dependencies`. It is local to the build and never published: the built archive folds into a link
directly and never travels in a descriptor (a library that ships C publishes that C as source). It is config-scoped, so
a `Test / SNX.vendored` library is built only for the test link.

A library is declared from an origin and a build backend, with optional per-platform link contributions:

```scala
SNX.vendored += Vendored
  .local("vendor/mylib")
  .cmake("mylib")
  .options { case Linux(_, _) => _.library("m") }
```

`local(directory)` builds a directory under the project (resolved against the project, then the build root);
`git(uri, ref)` clones a Git repository at a pinned ref (a tag, commit, or branch). `cmake`
configures, builds, installs, and collects the static archives and headers, forcing static libraries
(`-DBUILD_SHARED_LIBS=OFF`); per-platform configure flags pass as `cmake(targets, flags)`. `options` adds the
per-platform link requirements the consuming link needs but a static archive cannot carry itself - distinct from the
CMake configure `flags`. The build runs in a normal toolchain environment, so a CMakeLists using `find_package` or a
toolchain file behaves as it does standalone (pass any extra `-D...` through the configure flags). Builds are cached
locally and rerun only when the sources, configuration, or toolchain change.

The CMake backend builds with CMake's default toolchain, which matches the Scala Native link on Linux, macOS, and the
MSVC Windows toolchain. It is not supported on Windows MinGW - MSVC is the supported Windows toolchain - so a vendored
CMake library there fails the build with a clear error rather than producing an unlinkable archive.

For a build CMake does not cover - Make, Autotools, a hand-rolled script - `command(token) { ctx => ... }` is the
escape hatch: the function builds from `ctx.source` into `ctx.staging` and returns the archives and header directories
to fold in (an `Artefacts`, whose paths must lie under `ctx.staging` so they are cached); `token` keys the build cache.
Because the build is yours to drive, `command` works on any toolchain - MinGW included.

## Exported requirements and propagation

A native library that bundles C may require its consumers to link extra system libraries, frameworks, or whole
archives - things Scala Native does not propagate on its own (the library name of a `@link`-annotated binding already
does). `SNX.usage` declares these per platform, as toolchain-neutral tokens. They render into the library's own link
and travel in a descriptor inside the library's jar; a consumer resolving the library folds the descriptor for its own
runtime and renders each token into its link. So the requirements arrive, correct, however deep the library sits in the
dependency graph - a consuming application never restates them. `Usage` composes per channel with `++`: `libraries`
(`-l`), `frameworks` (macOS `-framework`), `wholeArchive`, `defines` (a `-D` a consumer's own C must match), `linkFlags`
(a raw escape), and `multithreaded` (require the consumer to link with multithreading).

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
| `SNX.linkage`        | `PartialFunction[NativeRuntime, Linkage]`     | `Dynamic`                         |
| `SNX.mode` `.gc` `.lto` `.optimize` `.sanitizer` `.multithreading` | scalars             | Scala Native's defaults           |
| `SNX.clang` `.clangPP` | `Option[File]`                              | discovered `clang` / `clang++`    |
| `SNX.includeDirs` `.libDirs` | `Seq[File]`                           | empty (host paths cross-stripped) |
| `SNX.modifiers`      | `Seq[Modifier[Native]]`                       | empty                             |
| `SNX.dependencies`   | `Seq[NativeDependency]`                        | empty                             |
| `SNX.usage`          | `PartialFunction[NativeRuntime, Usage]`       | empty (exported link requirements)|
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

// A per-platform NIR library: its bundled C needs a system library, which it declares once via `SNX.usage`. Published
// per OS/arch as a classified jar that carries the descriptor.
val core = project
  .enablePlugins(SNXPlugin)
  .settings(
    SNX.classified := true,
    SNX.usage := {
      case Linux(_, _)   => Usage.libraries("z")
      case Darwin(_)     => Usage.frameworks("Security")
      case Windows(_, _) => Usage.libraries("zlib")
    }
  )

// A native library (.so/.dylib/.dll), linked statically where the toolchain supports it.
val engine = project
  .enablePlugins(SNXPlugin)
  .dependsOn(core)
  .settings(
    SNX.deliverable := Library,
    SNX.linkage     := { case p if p.supportsStaticLinking => Static; case _ => Dynamic }
  )

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
