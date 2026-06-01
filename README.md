# sbt-snx

sbt-snx (sbt-native-extras) is an sbt 2.x plugin for Scala Native projects. It expresses per-platform
native concerns - dependency resolution, linker and compiler options, and the native build configuration -
as build settings keyed on a chosen OS/arch target and the toolchain's resolved C library or ABI.

## Getting started

```scala
// project/plugins.sbt
addSbtPlugin("africa.shuwari" % "sbt-snx" % "<version>")
```

```scala
// build.sbt
enablePlugins(ScalaNativePlugin, SnxPlugin)
```

`SnxPlugin` requires `ScalaNativePlugin` and does not trigger on its own.

## Concepts

`TargetPlatform` pairs an `Os` (`Linux`/`Osx`/`Windows`) with an `Arch` (`X86_64`/`Aarch64`) - the
setting-time coordinate you choose. `NativePlatform` is the task-time key you match on to condition the
build: it adds the toolchain's C library or ABI, resolved from the native target triple (or the discovered
clang), so each case exposes only the values valid for that OS - `Linux(arch, LinuxLibc)` with
`Glibc`/`Musl`, `Osx(arch)`, and `Windows(arch, WindowsAbi)` with `Msvc`/`Mingw`. You set a
`TargetPlatform`; you match on a `NativePlatform`.

## Targets

`snxTarget` is the OS/arch to resolve and build for; it defaults to the build host. Override it to
cross-target:

```scala
snxTarget := TargetPlatform(Os.Osx, Arch.Aarch64)
```

An unsupported operating system or architecture fails the build with `UnsupportedTargetException`.

## Native dependencies

`platformDependencies` injects the `snxTarget` OS/arch classifier into each entry's coordinate and
conditions its native options on the resolved `NativePlatform`:

```scala
platformDependencies += "com.example" %% "blas" % "1.2"
```

The bare form is classified; `.plain` leaves it unclassified, for platform-independent NIR. Attach
per-platform options by matching the resolved platform - `linking` for linker flags, or `options` for the
full additive bundle (`linking`, `compile`, `c`, `cpp`):

```scala
platformDependencies += ("com.example" %% "uv" % "1.4" linking {
  case NativePlatform.Linux(_, _) => Seq("-luv")
})

platformDependencies += ("com.example" %% "ssl" % "3" options {
  case NativePlatform.Linux(_, LinuxLibc.Glibc) =>
    NativeDependency.Options.empty.withLinking("-lssl").withCompile("-I/opt/ssl/include")
})
```

Options follow the dependency's configuration: a `Test` dependency contributes only to the test link.
Classifiers use the `os.detected.classifier` spelling (`linux-x86_64`, `osx-aarch_64`, `windows-aarch_64`),
interoperable with native artefacts published under that convention.

## Project native configuration

`snxNative` is a list of per-platform transforms over the Scala Native `NativeConfig`, applied for the
resolved platform and inherited by the Compile and Test links. It is the place for whole-build settings -
linker and compile options, `LTO`, `Mode`, `GC` - that no single dependency owns. Declare them with `:=`:

```scala
snxNative := Seq(
  { case NativePlatform.Linux(_, LinuxLibc.Musl) => c => c.withLinkingOptions(c.linkingOptions :+ "-static") },
  { case NativePlatform.Osx(_)                   => _.withLTO(LTO.full) }
)
```

To add a transform incrementally, wrap the literal in `nativeTransform` so it types under `+=`; a bare
`+=`/`++=` literal cannot infer its type, whereas `:=` can and covers declaring several platforms at once,
so `++=` is never needed:

```scala
snxNative += nativeTransform {
  case NativePlatform.Windows(_, WindowsAbi.Msvc) => _.withGC(GC.none)
}
```

`LTO`, `Mode`, `GC`, `BuildTarget`, and `Sanitizer` are auto-imported for use in transforms. Where several
transforms match a platform, they compose in order, with scalar settings taking the last write.

## Settings

| Setting | Type | Default |
| --- | --- | --- |
| `snxTarget` | `TargetPlatform` | the build host |
| `platformDependencies` | `Seq[NativeDependency]` | empty |
| `snxNative` | `Seq[NativeTransform]` | empty |

## License

Apache License 2.0. Copyright Shuwari Africa Ltd.
