# Compatibility matrix

FixThis is built and exercised against a single, pinned set of toolchain
versions (the "Tested" axis below). The "Minimum that compiles" axis records
the lower bound that the project still expects to assemble against — those
lower bounds are validated by an informational [scheduled workflow](#scheduled-validation)
and are not (yet) gated by required CI.

If you consume FixThis from your own project, the **Tested** column is the
safe choice. The **Minimum that compiles** column documents intent for
downstream users on older toolchains; treat it as best-effort until the
scheduled workflow is promoted to a required check (see
[CHANGELOG.md](../../CHANGELOG.md) for the promotion follow-up).

## Axes

| Axis                       | Tested (pinned)       | Minimum that compiles | Source                                 |
| -------------------------- | --------------------- | --------------------- | -------------------------------------- |
| Android Gradle Plugin      | **9.1.1**             | 9.0.0                 | `gradle/libs.versions.toml` → `agp`    |
| Kotlin Gradle Plugin       | **2.2.21**            | 2.2.0                 | `gradle/libs.versions.toml` → `kotlin` |
| Compose BOM                | **2025.01.01**        | 2025.01.01            | `gradle/libs.versions.toml` → `composeBom` |
| Compose UI test artifacts  | **1.7.8**             | 1.7.8                 | `gradle/libs.versions.toml` → `composeUiTest` |
| JDK toolchain              | **21 (Temurin)**      | 21                    | `.github/workflows/ci.yml`             |
| Android `compileSdk`       | **34**                | 34                    | `sample/build.gradle.kts`, `fixthis-compose-sidekick/build.gradle.kts` |
| Android `minSdk`           | **24**                | 24                    | `sample/build.gradle.kts`              |

The "Tested" column matches what CI builds on every PR and push to `main`
([`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)). The "Minimum
that compiles" column is checked weekly (informational only — see
[Scheduled validation](#scheduled-validation)).

## Rationale per axis

### Android Gradle Plugin

- **Tested: 9.1.1.** AGP 9.x is the first major series with the new
  Kotlin-multiplatform-friendly variant API surface that the sidekick build
  scripts rely on. The Gradle plugin (`:fixthis-gradle-plugin`) targets the
  `AndroidComponentsExtension` shape stabilised in AGP 9.
- **Minimum 9.0.0.** AGP 9.0.x is the intended lower bound for today's
  sources. The scheduled job is what will catch regressions in that claim once
  the version-override plumbing is wired. AGP 8.x is explicitly out of scope
  because variant API and namespace handling differ enough that the sidekick's
  auto-wiring no longer applies.

### Kotlin Gradle Plugin

- **Tested: 2.2.21.** Project sources compile with the K2 frontend and use
  language features pinned to Kotlin 2.2. The composite-build setup applies
  the same Kotlin version across `:fixthis-compose-core`, `:fixthis-cli`,
  `:fixthis-mcp`, `:fixthis-compose-sidekick`, and `:fixthis-gradle-plugin`.
- **Minimum 2.2.0.** Kotlin 2.2 is the intended lower bound for Compose
  compiler 1.10+ wiring with AGP 9. Older 2.1.x toolchains run into
  compose-compiler / AGP matching constraints and are not part of the current
  support window.
- **Forward smoke: 2.3.20.** The scheduled compatibility workflow includes a
  non-gating Kotlin 2.3.20 sample assemble. This records current Kotlin 2.3.x
  coverage without changing the pinned PR-tested toolchain.

### Compose BOM

- **Tested: 2025.01.01.** Matches the BOM the sample app and `fixthis-compose-core`
  resolve at build time.
- **Minimum 2025.01.01.** This keeps FixThis on the Compose 1.7.x line so the
  sidekick AAR can be consumed by apps still compiling with Android 14
  (`compileSdk` 34). A lower `compileSdk` 33 floor was investigated, but
  Compose 1.5.x pulls `androidx.emoji2:emoji2:1.4.0`, whose published AAR
  metadata already requires `compileSdk` 34. Newer Compose releases are
  supported through dependency resolution in the consuming app.

### JDK toolchain

- Both "Tested" and "Minimum" pin to JDK 21 (Temurin). Lower JDKs are
  unsupported because AGP 9 itself requires JDK 17+ and the project's
  toolchain configuration locks 21. The CI workflow uses
  [`actions/setup-java@v5`](../../.github/workflows/ci.yml) with
  `distribution: temurin` and `java-version: "21"`.

### Android `minSdk`

- `minSdk` 24 is fixed. Lower SDKs are not validated; the sidekick uses
  `androidx.startup` (debug-only) which requires API 21+, but the sample app
  and tests assume 24.

### Android `compileSdk`

- **Tested and minimum: 34.** The sidekick AAR is intentionally built with
  `compileSdk` 34 so debug consumers already pinned to Android 14 do not have
  to raise their project-wide `compileSdk` just to install FixThis. `targetSdk`
  remains the consuming app's decision; FixThis does not require release builds
  and is wired only through debug variants.
- Google Play target API requirements apply to the consuming app's
  `targetSdk`, not to FixThis's debug-only library `compileSdk`. Apps submitted
  to Play should keep their own `targetSdk` at the currently required Play
  level while consuming FixThis only in debug variants.

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
