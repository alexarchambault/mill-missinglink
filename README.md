# mill-missinglink

[![CI](https://github.com/alexarchambault/mill-missinglink/actions/workflows/ci.yml/badge.svg)](https://github.com/alexarchambault/mill-missinglink/actions/workflows/ci.yml)

A [Mill](https://mill-build.org) plugin for detecting classpath linkage errors using
[Spotify's missinglink](https://github.com/spotify/missinglink) tool.

Classpath linkage errors occur when a class or method that code was compiled against is absent (or
incompatible) at runtime — resulting in `NoClassDefFoundError`, `NoSuchMethodError`, or similar
`LinkageError`s that are otherwise only discovered at runtime.

Inspired by [sbt-missinglink](https://github.com/scalacenter/sbt-missinglink).

## Usage

### 1. Add the plugin to your build

In your `build.mill` header, declare the dependency:

```scala
//| mvnDeps:
//|   - io.github.alexarchambault::mill-missinglink::0.1.0
```

### 2. Mix `MissingLink` into your modules

```scala
import mill.*
import mill.scalalib.*
import io.github.alexarchambault.millmissinglink.*

object myProject extends ScalaModule with MissingLink {
  def scalaVersion = "3.3.5"
}
```

The trait works with any `JavaModule` — `ScalaModule`, `JavaModule`, etc.

### 3. Run the check

```
./mill myProject.missinglinkCheck
```

If no linkage errors are found, the task succeeds and prints:

```
No conflicts found
```

If conflicts are detected, they are printed grouped by category, artifact, and class, then the
build fails (by default).

## Configuration

All settings have sensible defaults and are optional.

| Task | Type | Default | Description |
|------|------|---------|-------------|
| `missinglinkFailOnConflicts` | `T[Boolean]` | `true` | Fail the build when conflicts are found. Set to `false` to report conflicts without failing. |
| `missinglinkIgnoreSourcePackages` | `T[Seq[String]]` | `Seq.empty` | Suppress conflicts where the *calling* class is in one of these packages (or a sub-package). Mutually exclusive with `missinglinkTargetSourcePackages`. |
| `missinglinkTargetSourcePackages` | `T[Seq[String]]` | `Seq.empty` | Only report conflicts where the *calling* class is in one of these packages. Mutually exclusive with `missinglinkIgnoreSourcePackages`. |
| `missinglinkIgnoreDestinationPackages` | `T[Seq[String]]` | `Seq.empty` | Suppress conflicts where the *called* class is in one of these packages. Mutually exclusive with `missinglinkTargetDestinationPackages`. |
| `missinglinkTargetDestinationPackages` | `T[Seq[String]]` | `Seq.empty` | Only report conflicts where the *called* class is in one of these packages. Mutually exclusive with `missinglinkIgnoreDestinationPackages`. |

### Examples

**Report conflicts without failing the build:**

```scala
object myProject extends ScalaModule with MissingLink {
  def scalaVersion = "3.3.5"
  def missinglinkFailOnConflicts = false
}
```

**Ignore conflicts from a specific package:**

```scala
object myProject extends ScalaModule with MissingLink {
  def scalaVersion = "3.3.5"
  // Ignore conflicts where the calling code is in "com.example.legacy" or any sub-package
  def missinglinkIgnoreSourcePackages = Seq("com.example.legacy")
}
```

**Only check conflicts involving a specific destination package:**

```scala
object myProject extends ScalaModule with MissingLink {
  def scalaVersion = "3.3.5"
  def missinglinkTargetDestinationPackages = Seq("com.criticallib")
}
```

## How it works

`missinglinkCheck` compiles your module, then uses Spotify's missinglink library to:

1. Build a representation of your compiled classes (the "project artifact").
2. Collect all runtime classpath JARs as "dependency artifacts".
3. Load JDK bootstrap classes (so standard library references are not false-positives).
4. Run `ConflictChecker.check(projectArtifact, runtimeArtifacts, allArtifacts)` to find any
   references in your compiled code that cannot be resolved against the runtime classpath.
5. Apply package filters (ignore/target) and report the remaining conflicts.

## Contributing

```
# Run all integration tests
./mill integration.test

# Compile the plugin
./mill plugin.compile
```

## License

Apache 2.0 — see [LICENSE](LICENSE).
