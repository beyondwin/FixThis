# Spec — Build & Release Hardening

Status: Largely implemented; retained as a design record. External artifact
publishing remains tracked in `docs/contributing/release-readiness.md`.
Scope: Gradle build, `:fixthis-compose-sidekick`, `:fixthis-gradle-plugin`,
sample app variants
Related plan: [`../plans/build-release-hardening.md`](../plans/build-release-hardening.md)

## Background

The sidekick is meant to ship as `debugImplementation` only. Today that is a
convention enforced by docs and a runtime `FLAG_DEBUGGABLE` check, but the
build does not actively prevent a release build from pulling it in or running
it. The version catalog exists but is not used consistently. There is no
documented compatibility window for AGP / Kotlin / Compose.

## Goals

- Make "sidekick cannot run in release" a build-time invariant, not a
  convention.
- Route every external dependency through the version catalog
  (`gradle/libs.versions.toml`).
- Publish a tested compatibility matrix for AGP, Kotlin, and the Compose BOM.
- Provide consumer ProGuard / R8 rules so a downstream release shrinker
  cannot accidentally strip required Compose-internal references if the lib is
  promoted to `implementation` by mistake.

## Non-Goals

- Publishing to Maven Central / Gradle Plugin Portal (already tracked in
  `docs/contributing/release-readiness.md`).
- Multiplatform support.
- Replacing Compose-internal API usage (covered separately under Compose
  compatibility tracking).

## Items

### BR-1 — Compile-time release guard for the sidekick

The sidekick must be unable to attach in release builds even if a consumer
writes `implementation` instead of `debugImplementation`. Two layers:

1. **Manifest merge guard.** Ship the sidekick's `<application>` initializer
   (`androidx.startup`) entry only in the `debug` source set, so a release
   manifest merge would either drop it or fail loudly.
2. **Runtime defence-in-depth.** Keep the `FLAG_DEBUGGABLE` check as a
   no-op fallback for unusual flavor configurations; log once and exit.

**Acceptance:** assembling the sample in release with the sidekick promoted to
`implementation` either (a) fails the build with a clear message, or (b) ships
an APK whose `FixThis` entry point is a no-op verified by an instrumented
sanity check.

### BR-2 — Consumer ProGuard rules

**Site:** `fixthis-compose-sidekick/build.gradle.kts` — neither
`consumerProguardFiles` nor `proguardFiles` is configured.

**Contract:** add `consumer-rules.pro` covering the Compose-internal entry
points the sidekick reflects on (e.g., `RootForTest`, `SemanticsOwner`), and
keep rules for sidekick public types referenced from the bridge protocol.

**Acceptance:** R8 + minify enabled in a downstream release build does not
strip the symbols the sidekick uses; verified by a Gradle integration test
under `:fixthis-gradle-plugin`'s test fixtures.

### BR-3 — Route all deps through the version catalog

**Sites observed:**
- `fixthis-compose-sidekick/build.gradle.kts` hardcodes
  `"androidx.test:core:1.6.1"` and `"org.robolectric:robolectric:4.16.1"`.
- Spot-check other modules for similar strays.

**Contract:** every coordinate string of the form
`"group:artifact:version"` in a `build.gradle.kts` is replaced by a
`libs.<alias>` reference. New versions land in `libs.versions.toml`.

**Acceptance:**
1. `git grep -nE '"[^"]+:[^"]+:[0-9]+\.' '*build.gradle.kts'` returns zero
   hits in module build scripts (root build / settings excluded).
2. Renovate / Dependabot configuration (see CI/CD spec) picks up
   `libs.versions.toml` updates.

### BR-4 — Documented compatibility matrix

Pin and test the supported window for AGP, Kotlin, and Compose BOM.

**Contract:**
- A matrix in `docs/reference/compatibility.md` listing supported versions
  with rationale ("AGP 9.0+ required because…").
- The Gradle plugin's `compileOnly` declarations relax to the supported
  range where AGP / Kotlin Gradle Plugin allow it.
- CI runs the sample build against the lower bound of each axis on a nightly
  workflow (one combination per axis is fine — full matrix is overkill).

**Acceptance:** matrix doc merged; nightly job exists (can stay informational
for one release before being marked required).

### BR-5 — Sidekick `BuildInfo` task is incremental and cache-safe

**Site:** `GenerateSidekickBuildInfoTask` rewrites `BuildInfo.kt` every minute
(epoch quantised to 60 s). That defeats Gradle's build cache on otherwise
unchanged inputs.

**Contract:** the input epoch is the **commit time** (`git log -1 --format=%ct`)
for clean trees and `currentTimeMillis()` only for dirty trees. The task
declares `@Input` correctly so a re-run with the same git SHA hits the cache.

**Acceptance:**
1. Two consecutive `./gradlew :fixthis-compose-sidekick:assembleDebug`
   invocations on the same SHA: second one reports
   `UP-TO-DATE` for `generateBuildInfo`.
2. `ConsoleBundleStalenessConsistencyTest` still passes.

## Out-of-scope follow-ups

- Maven Central publishing pipeline (Sonatype, signing, javadoc).
- Plugin Portal publishing (already partially wired via
  `gradle-plugin-publish`).
- Multi-flavor sample app for matrix testing.
