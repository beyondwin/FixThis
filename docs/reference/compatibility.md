# Compatibility matrix

**Tested** is the pinned toolchain CI builds on every PR. **Minimum that
compiles** is the lower bound a scheduled informational
[workflow](#scheduled-validation) still expects to assemble. Use Tested in
your own project. Treat Minimum as best-effort until that workflow is a
required check.

## Axes

| Axis                       | Tested (pinned)       | Minimum that compiles | Source                                 |
| -------------------------- | --------------------- | --------------------- | -------------------------------------- |
| Android Gradle Plugin      | **9.1.1**             | 9.0.0                 | `gradle/libs.versions.toml` → `agp`    |
| Kotlin Gradle Plugin       | **2.2.21**            | 2.2.0                 | `gradle/libs.versions.toml` → `kotlin` |
| Compose BOM                | **2025.01.01**        | 2025.01.01            | `gradle/libs.versions.toml` → `composeBom` |
| Compose UI test artifacts  | **1.7.8**             | 1.7.8                 | `gradle/libs.versions.toml` → `composeUiTest` |
| JDK toolchain              | **21 (Temurin)**      | 21                    | `.github/workflows/ci.yml`             |
| Android `compileSdk`       | **34**                | 34                    | `sample/build.gradle.kts`, `fixthis-compose-sidekick/build.gradle.kts` |
| Android `minSdk`           | **23**                | 23                    | `sample/build.gradle.kts`              |

The "Tested" column matches what CI builds on every PR and push to `main`
([`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)). The "Minimum
that compiles" column is checked weekly (informational only — see
[Scheduled validation](#scheduled-validation)).

## Rationale per axis

- **AGP 9.1.1 / min 9.0.0.** Plugin uses the AGP 9 `AndroidComponentsExtension`.
  AGP 8.x is out of scope.
- **Kotlin 2.2.21 / min 2.2.0.** Same version across core, CLI, MCP, sidekick,
  and the Gradle plugin. 2.1.x is outside the Compose-compiler / AGP window.
  Nightly also smokes 2.3.20 without changing the pinned PR toolchain.
- **Compose BOM 2025.01.01.** Compose 1.7.x so the sidekick AAR still installs
  into `compileSdk` 34 apps. Newer Compose is the consuming app's resolution.
- **JDK 21.** AGP 9 needs 17+; this repo locks 21.
- **`minSdk` 23.** Newer APIs are runtime-guarded.
- **`compileSdk` 34.** Debug consumers on Android 14 do not have to raise
  compileSdk. Play `targetSdk` rules apply to the app, not this debug library.

## Scheduled validation

A scheduled workflow runs the lower bounds informationally:

- File: [`.github/workflows/nightly-compat.yml`](../../.github/workflows/nightly-compat.yml)
- Schedule: 03:00 UTC Tuesdays plus manual `workflow_dispatch`.
- Each lower-bound axis (AGP, Kotlin, Compose BOM / UI test artifacts) is
  exercised by one `./gradlew :app:assembleDebug` invocation with a property
  override pointing at the axis's lower bound.
- Kotlin also has a forward-smoke job for 2.3.20 because downstream apps may
  move faster than FixThis's pinned CI toolchain.
- The workflow is **informational** — `continue-on-error: true` on each
  step. Failures are surfaced in the job log only; they do not block PRs.
- Promotion of this workflow to a required check is tracked in
  [CHANGELOG.md](../../CHANGELOG.md) under the BR-4 Unreleased entry.

Before advertising a lower-bound compatibility promotion, run:

```bash
npm run checks:observation -- --require-ready nightly-compat
```

The property override mechanism is active. `overrideAgpVersion` and
`overrideKotlinVersion` are applied through plugin resolution and the version
catalog. `overrideComposeBomVersion` and `overrideComposeUiTestVersion` are
applied through the root `libs` catalog before project build scripts resolve
dependencies.

## Related

- [Bridge protocol](bridge-protocol.md) — versioning of the wire protocol
  between sidekick and the MCP server, independent of package / toolchain
  versioning.
- [CHANGELOG.md](../../CHANGELOG.md) — release notes, including
  toolchain-bump entries.
- [Release readiness](../contributing/release-readiness.md) — publishing
  checklist for the first external release.
