# Plan — Build & Release Hardening

Status: Draft
Spec: [`../specs/build-release-hardening.md`](../specs/build-release-hardening.md)

## BR-3 — Version catalog cleanup (first; smallest)

**Files**
- `fixthis-compose-sidekick/build.gradle.kts`
- `gradle/libs.versions.toml`
- Any other module-level `build.gradle.kts` flagged by the audit grep.

**Steps**
1. `git grep -nE '"[^"]+:[^"]+:[0-9]+\\.[^"]*"' -- '*build.gradle.kts'` to
   list strays.
2. For each hit:
   - Add a `[versions]` entry and `[libraries]` alias in
     `libs.versions.toml`.
   - Replace the coordinate with `libs.<alias>` in the module build script.
3. Run `./gradlew help` to validate catalog parses.

**Validation**
- Grep above returns empty.
- Full build matrix (`./gradlew build`) passes.

## BR-2 — Consumer ProGuard rules

**Files**
- New: `fixthis-compose-sidekick/consumer-rules.pro`
- `fixthis-compose-sidekick/build.gradle.kts`

**Steps**
1. Author `consumer-rules.pro` keeping:
   - `androidx.compose.ui.platform.AndroidComposeView` and `RootForTest`
     entry points the sidekick reflects on;
   - public sidekick types referenced from the bridge protocol
     (`io.beyondwin.fixthis.compose.sidekick.bridge.**`).
2. Wire them via `defaultConfig { consumerProguardFiles("consumer-rules.pro") }`.
3. Add a smoke test under `fixthis-gradle-plugin/src/functionalTest/` that
   assembles a release variant of a fixture app with minify on and asserts
   the sidekick types survive (or are absent if the variant is non-debug —
   either is fine, depending on BR-1 outcome).

**Validation**
- Functional test passes locally and on CI.
- No new lint warnings on the sidekick module.

## BR-1 — Compile-time release guard

**Files**
- `fixthis-compose-sidekick/src/debug/AndroidManifest.xml` (new — move startup
  entry here)
- `fixthis-compose-sidekick/src/main/AndroidManifest.xml` (remove startup
  entry)
- `fixthis-compose-sidekick/src/main/kotlin/.../FixThisInitializer.kt`
  (keep `FLAG_DEBUGGABLE` early-return as defence-in-depth)
- New: `fixthis-gradle-plugin/src/functionalTest/.../ReleaseGuardTest.kt`

**Steps**
1. Move the `androidx.startup` provider declaration from `main` to a
   `debug` source-set manifest so release variants of consumers never see it.
2. Add a one-shot release-build log line in `FixThisInitializer.kt` so a
   misconfigured app emits a single warning instead of attaching the bridge.
3. New functional test: fixture app with `implementation(project(...))`
   instead of `debugImplementation`. Run `:assembleRelease` with minify on.
   Assert the resulting APK has no `FixThisInitializer` references in
   `classes.dex` — or, if it does, that the runtime guard returns before
   opening sockets.

**Validation**
- Sample app debug build unchanged.
- Functional test green.
- CHANGELOG entry under "Safety / hardening".

## BR-4 — Compatibility matrix

**Files**
- New: `docs/reference/compatibility.md`
- `.github/workflows/nightly-compat.yml` (new)

**Steps**
1. Document supported and tested versions of AGP, Kotlin Gradle Plugin, and
   the Compose BOM. Include "minimum that compiles" vs. "tested in CI".
2. Add a nightly workflow that builds `:app:assembleDebug` against the lower
   bound of one axis at a time (overridden via `-PoverrideAgpVersion=...`).
3. Link the matrix from README ("Compatibility") and from
   `docs/getting-started/add-to-your-app.md`.

**Validation**
- Nightly job runs green for one week before being mentioned in the README.

## BR-5 — Incremental `BuildInfo` task

**Files**
- `fixthis-compose-sidekick/build.gradle.kts`

**Steps**
1. Replace
   `buildEpoch.set(providers.provider { (currentTimeMillis() / 60_000L) * 60_000L })`
   with a value provider that:
   - reads `git log -1 --format=%ct` (UTC seconds × 1000) for clean trees;
   - falls back to `System.currentTimeMillis()` only when
     `git status --porcelain` is non-empty.
2. Same pattern for `gitSha`: when dirty, append `-dirty` instead of
   recomputing on every invocation.

**Validation**
- `./gradlew :fixthis-compose-sidekick:generateBuildInfo` twice on a clean
  tree → second run is `UP-TO-DATE`.
- `ConsoleBundleStalenessConsistencyTest` and the existing sidekick build-info
  tests pass.

## Rollout order

1. BR-3 (catalog)
2. BR-5 (cache-safe BuildInfo) — smallest behavioural change
3. BR-2 (consumer rules) — additive, low risk
4. BR-1 (release guard) — touches manifests; staged behind a functional test
5. BR-4 (matrix doc + nightly) — informational first, gating later
