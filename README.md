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
per-platform options by matching the resolved platform - `linking` for linker flags, or `options` for the
full additive bundle (`linking`, `compile`, `c`, `cpp`):

```scala
SNX.dependencies += ("com.example" %% "uv" % "1.4" linking {
  case NativePlatform.Linux(_, _) => Seq("-luv")
})

SNX.dependencies += ("com.example" %% "ssl" % "3" options {
  case NativePlatform.Linux(_, LinuxLibc.Glibc) =>
    NativeDependency.Options.empty.withLinking("-lssl").withCompile("-I/opt/ssl/include")
})
```

Options follow the dependency's configuration: a `Test` dependency contributes only to the test link.
Classifiers use the `os.detected.classifier` spelling (`linux-x86_64`, `osx-aarch_64`, `windows-aarch_64`),
interoperable with native artefacts published under that convention.

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
target's, so platform code paths never co-compile. Each existing source **and** resource directory gains
`-<os>` and `-<os>-<arch>` siblings, derived from whatever the build resolved; absent directories are ignored.

Sources and resources are handled identically. A plain Scala Native project (where `scala`/`resources` are
already the native directories) therefore yields:

```text
src/main/scala-linux            src/main/scala-linux-x86_64
src/main/scala-3-linux          src/main/scala-3-linux-x86_64
src/main/resources-linux        src/main/resources-linux-x86_64
```

In a native project matrix the same suffixing applies to the matrix's `scalanative`/`resources-scalanative`
directories (`scalanative-linux`, `resources-scalanative-linux`, and so on) - nothing is hardcoded.

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

On sbt 2.0.0-RC14 the Ivy publish backend drops the Scala Native platform suffix from published artifact
filenames, which Maven rejects (sbt/sbt#9117); the ivyless backend names them correctly. Unsigned `publish`
can use the ivyless backend (`useIvy := false`, with a maven-style resolver); `publishSigned` (sbt-pgp) always
uses the Ivy backend, so for signed releases bake the suffix into `moduleName` and disable further suffixing
(`projectID / crossVersion := Disabled()`). The fix is tracked in sbt/sbt#9293.

## Settings

| Setting                   | Type                    | Default                                      |
|---------------------------|-------------------------|----------------------------------------------|
| `SNX.target`              | `TargetPlatform`        | the build host                               |
| `SNX.targets`             | `Seq[TargetPlatform]`   | the active `SNX.target` alone                |
| `SNX.platform`            | `NativePlatform` (task) | resolved from `SNX.target` and the toolchain |
| `SNX.dependencies`        | `Seq[NativeDependency]` | empty                                        |
| `SNX.config`              | `Seq[NativeTransform]`  | empty                                        |
| `SNX.Native / crossPaths` | `Boolean`               | `false`                                      |

## License

Apache License 2.0. Copyright Shuwari Africa Ltd.
