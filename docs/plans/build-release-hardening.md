# Plan — Build & Release Hardening

Status: Draft
Spec: [`../specs/build-release-hardening.md`](../specs/build-release-hardening.md)

## Tasks

### Task 0: BR-3 — Version catalog cleanup

**Files:**
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

#### Acceptance Criteria
```bash
./gradlew help
# Stray coordinates only allowed as comments or inside string interpolation contexts (heuristic: no hard-coded coord+version literal)
test -z "$(git grep -nE '"[^"]+:[^"]+:[0-9]+\.[^"]*"' -- '*build.gradle.kts' | grep -v '^Binary')"
./gradlew build -x test -x check --no-daemon
```

### Task 1: BR-5 — Incremental `BuildInfo` task

**Files:**
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

#### Acceptance Criteria
```bash
./gradlew :fixthis-compose-sidekick:generateBuildInfo --no-daemon
# Second invocation should be UP-TO-DATE on a clean tree
./gradlew :fixthis-compose-sidekick:generateBuildInfo --no-daemon | grep -qE 'UP-TO-DATE|NO-SOURCE' || \
  ./gradlew :fixthis-compose-sidekick:generateBuildInfo --no-daemon 2>&1 | tee /tmp/buildinfo.log | grep -q UP-TO-DATE
./gradlew :fixthis-mcp:test --tests '*ConsoleBundleStalenessConsistency*'
```

### Task 2: BR-2 — Consumer ProGuard rules

**Files:**
- `fixthis-compose-sidekick/consumer-rules.pro` (new)
- `fixthis-compose-sidekick/build.gradle.kts`
- `fixthis-gradle-plugin/src/functionalTest/kotlin/.../SidekickConsumerRulesTest.kt` (new — smoke test under functionalTest)

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

#### Acceptance Criteria
```bash
test -f fixthis-compose-sidekick/consumer-rules.pro
grep -q 'consumerProguardFiles' fixthis-compose-sidekick/build.gradle.kts
./gradlew :fixthis-gradle-plugin:functionalTest --no-daemon
```

### Task 3: BR-1 — Compile-time release guard

**Files:**
- `fixthis-compose-sidekick/src/debug/AndroidManifest.xml` (new — move startup entry here)
- `fixthis-compose-sidekick/src/main/AndroidManifest.xml` (remove startup entry)
- `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/FixThisInitializer.kt` (keep `FLAG_DEBUGGABLE` early-return as defence-in-depth)
- `fixthis-gradle-plugin/src/functionalTest/kotlin/.../ReleaseGuardTest.kt` (new)

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

#### Acceptance Criteria
```bash
# Sidekick debug build still includes the startup entry
test -f fixthis-compose-sidekick/src/debug/AndroidManifest.xml
# Main manifest no longer carries the startup provider for production consumers
! grep -q 'androidx.startup' fixthis-compose-sidekick/src/main/AndroidManifest.xml
./gradlew :fixthis-gradle-plugin:functionalTest --no-daemon
./gradlew :app:assembleDebug --no-daemon
```

### Task 4: BR-4 — Compatibility matrix

Scope note: this task lands the documentation and the nightly workflow file.
The 1-week observation / promotion gate from the spec is intentionally
deferred to a follow-up tracked in CHANGELOG.

**Files:**
- `docs/reference/compatibility.md` (new)
- `.github/workflows/nightly-compat.yml` (new)
- `README.md` (add "Compatibility" link)
- `docs/getting-started/add-to-your-app.md` (link to the matrix)
- `CHANGELOG.md` (note: nightly compat matrix shipped, promotion to required check is a follow-up)

**Steps**
1. Document supported and tested versions of AGP, Kotlin Gradle Plugin, and
   the Compose BOM. Include "minimum that compiles" vs. "tested in CI".
2. Add a nightly workflow that builds `:app:assembleDebug` against the lower
   bound of one axis at a time (overridden via `-PoverrideAgpVersion=...`).
3. Link the matrix from README ("Compatibility") and from
   `docs/getting-started/add-to-your-app.md`.
4. Add a CHANGELOG entry noting that promotion of the nightly check to a
   required gate is a follow-up.

#### Acceptance Criteria
```bash
test -f docs/reference/compatibility.md
test -f .github/workflows/nightly-compat.yml
grep -q 'Compatibility' README.md
grep -q 'compatibility' docs/getting-started/add-to-your-app.md
grep -q 'nightly-compat\|compatibility matrix' CHANGELOG.md
```

## Rollout order

1. Task 0: BR-3 (catalog)
2. Task 1: BR-5 (cache-safe BuildInfo) — smallest behavioural change
3. Task 2: BR-2 (consumer rules) — additive, low risk
4. Task 3: BR-1 (release guard) — touches manifests; staged behind a functional test
5. Task 4: BR-4 (matrix doc + nightly) — informational; observation/promotion is follow-up
