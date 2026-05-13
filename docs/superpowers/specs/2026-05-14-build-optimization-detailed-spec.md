# FixThis Build Optimization - Detailed Spec

**Date:** 2026-05-14
**Status:** Ready for implementation planning
**Owners:** build / CI / release-readiness
**Related:**
- `docs/superpowers/plans/2026-05-14-build-optimization-implementation.md` - task-by-task implementation plan
- `CONTRIBUTING.md` - canonical local and CI verification commands
- `.github/workflows/ci.yml` - current PR baseline workflow
- `.github/workflows/nightly-compat.yml` - scheduled compatibility matrix

---

## Purpose

The 2026-05-14 build audit found several concrete optimization opportunities in
the Gradle and CI setup. The project already uses Gradle 9.3.1, AGP 9.1.1,
Kotlin 2.2.21, JDK 21 toolchains, and parallel project execution, but it does
not enable the local build cache by default and one custom generated-assets task
is not cacheable. There is also one compatibility workflow that documents lower
bound testing but does not yet wire its version overrides into the build.

This spec turns those findings into a staged implementation plan. The goal is to
improve local incremental build time and CI signal quality without weakening the
debug-only guarantees that are central to FixThis.

## Audit Findings

### Current Gradle Defaults

`gradle.properties` currently contains:

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.parallel=true
kotlin.code.style=official
```

`org.gradle.caching=true` is absent. Local commands only use the build cache
when callers pass `--build-cache` explicitly. The audit confirmed that
`:app:assembleDebug --configuration-cache --build-cache` drops from a cold
9-second no-op-ish build to a 2-second cached repeat on this machine.

### Configuration Cache Status

The following commands stored and then reused configuration cache entries when
the workspace state was stable:

```bash
./gradlew help --configuration-cache --warning-mode all --no-daemon
./gradlew :app:assembleDebug --configuration-cache --build-cache --no-daemon
./gradlew :fixthis-mcp:installDist --configuration-cache --build-cache --no-daemon
./gradlew detekt --configuration-cache --build-cache --warning-mode all --no-daemon
```

`spotlessCheck` did not reliably reuse the configuration cache. Gradle reported
`configuration cache cannot be reused because an input to unknown location has
changed`. Because Spotless is part of the required PR command, configuration
cache should remain opt-in until that input is understood or a Spotless upgrade
fixes it.

### Custom Source Index Task

`:app:generateDebugFixThisSourceIndex` is implemented by
`GenerateFixThisSourceIndexTask`. The task declares inputs and outputs, but
Gradle reports:

```text
Caching disabled for task ':app:generateDebugFixThisSourceIndex' because:
  Caching has not been enabled for the task
```

The task should be made cacheable after confirming that all task inputs which
affect output bytes are declared. The task should also clear its output
directory at execution start so toggling `generateSourceIndex` or
`generateProjectMetadata` cannot leave stale JSON files behind.

### Sidekick BuildInfo Generation

`fixthis-compose-sidekick/build.gradle.kts` generates Kotlin source for
`BuildInfo.kt`. In a dirty workspace it uses `System.currentTimeMillis()` for
`BUILD_EPOCH_MS`. That makes the generated Kotlin source change even when the
sidekick source code did not change, which can force:

```text
:fixthis-compose-sidekick:generateBuildInfo
:fixthis-compose-sidekick:compileDebugKotlin
:app:compileDebugKotlin
```

on repeated debug builds. The timestamp is useful for stale-binary diagnostics,
but it should not require Kotlin recompilation. Moving this metadata to a
generated Android resource keeps the runtime behavior while limiting invalidated
work to resource processing and merge tasks.

### MCP BuildInfo Generation

`fixthis-mcp/build.gradle.kts` also generates `BuildInfo.kt` and filters the
bundled console resource so `BuildInfo` and `console/app.js` share the same SHA
and epoch. The audit verified `:fixthis-mcp:installDist` can reuse the
configuration cache when the workspace state is stable. Because this is a JVM
distribution build and much cheaper than the Android compile path, it is not the
first optimization target.

### Tooling Update Candidates

`./gradlew dependencyUpdates -Drevision=release --warning-mode all --no-daemon`
reported these release candidates:

| Component | Current | Candidate |
| --- | --- | --- |
| Android Gradle Plugin | 9.1.1 | 9.2.1 |
| Kotlin Gradle Plugin | 2.2.21 | 2.3.21 |
| Spotless | 7.0.2 | 8.4.0 |
| ben-manes versions plugin | 0.52.0 | 0.54.0 |
| Gradle plugin publish plugin | 1.3.1 | 2.1.1 |
| detekt | 1.23.7 | 1.23.8 |

`detekt` emitted a Gradle 10 deprecation warning:

```text
The ReportingExtension.file(String) method has been deprecated.
```

The warning is reported under the detekt plugin location, not under an obvious
project-owned custom task. A detekt patch update is a low-risk first attempt;
major toolchain jumps should be kept in a separate compatibility PR.

### Compatibility Matrix Is Not Real Yet

`.github/workflows/nightly-compat.yml` passes:

```bash
-PoverrideAgpVersion=9.0.0
-PoverrideKotlinVersion=2.2.0
-PoverrideComposeBomVersion=2026.01.00
```

but the workflow comment and `docs/reference/compatibility.md` both state that
the override properties are not wired. Today the scheduled workflow assembles
against the pinned versions, not the lower bounds it names.

## Goals

1. Enable the local Gradle build cache by default.
2. Keep configuration cache opt-in until all required PR checks can reuse it
   reliably.
3. Make `GenerateFixThisSourceIndexTask` cacheable and stale-output safe.
4. Move sidekick build metadata out of generated Kotlin source so dirty debug
   builds do not force Kotlin recompilation solely to update metadata.
5. Wire compatibility-matrix version overrides so scheduled lower-bound jobs
   test the versions they claim to test.
6. Improve CI failure locality by moving cheap console checks earlier and, in a
   follow-up phase, splitting independent checks into parallel jobs.

## Non-Goals

- Do not relax debug-only runtime behavior. Release builds remain unsupported.
- Do not rename persisted MCP JSON fields: `items`, `screens`, `itemId`,
  `screenId`, `targetEvidence`, and `sourceCandidates` remain compatibility
  contracts.
- Do not enable configuration cache globally until `spotlessCheck` is proven
  reusable or deliberately exempted.
- Do not update Kotlin, AGP, Gradle, and Compose all in the same PR as cache
  work. Toolchain jumps need their own compatibility verification.
- Do not introduce a remote build cache in this pass. Local cache is enough for
  the immediate developer-experience win and avoids credential / retention
  design.

## Proposed Work Streams

### Stream A - Safe Defaults

Add `org.gradle.caching=true` to root `gradle.properties`. Keep
`org.gradle.configuration-cache=true` out of defaults. Update
`scripts/bootstrap-mcp.sh` to pass `--build-cache` and `--configuration-cache`
for the two distribution install tasks because that command is a focused,
verified local bootstrap path.

### Stream B - Source Index Task Cacheability

Annotate `GenerateFixThisSourceIndexTask` with `@CacheableTask`. At execution
start, delete and recreate the task output directory before writing current JSON
assets. Add unit tests for:

- the task is explicitly annotated as cacheable
- disabling source-index generation removes a stale
  `fixthis-source-index.json`
- disabling project metadata removes a stale `fixthis-build-info.json`

Manual verification must include:

```bash
./gradlew :app:generateDebugFixThisSourceIndex --build-cache --info --no-daemon
./gradlew :app:generateDebugFixThisSourceIndex --build-cache --info --no-daemon
```

The repeated command should be `UP-TO-DATE` or cache-aware without the previous
`Caching has not been enabled for the task` message.

### Stream C - Sidekick BuildInfo Resource

Replace generated `BuildInfo.kt` in `:fixthis-compose-sidekick` with generated
Android resources:

```xml
<resources>
    <string name="fixthis_sidekick_build_epoch_ms" translatable="false">...</string>
    <string name="fixthis_sidekick_git_sha" translatable="false">...</string>
</resources>
```

Read the values through a small provider used by `AndroidBridgeEnvironment`.
This keeps `BridgeStatus.sidekickBuildEpochMs` behavior intact without changing
the bridge protocol. The generated resource may still update during dirty
builds, but it should not invalidate Kotlin compilation.

### Stream D - Compatibility Matrix Overrides

Wire `overrideAgpVersion`, `overrideKotlinVersion`, `overrideComposeBomVersion`,
and `overrideComposeUiTestVersion` in both root settings and the included
`fixthis-gradle-plugin` build settings. The root build needs plugin resolution
overrides for AGP/Kotlin plugin aliases and version-catalog overrides for
Compose BOM / UI test artifacts. The included Gradle plugin build needs the
same AGP/Kotlin catalog overrides because it compiles against AGP APIs through
`compileOnly`.

### Stream E - CI Shape

Move cheap console checks before the Gradle baseline in `.github/workflows/ci.yml`:

```bash
node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
```

Then split CI into independent jobs only after Stream A through D are green:

- `console-js`
- `format-and-static-analysis`
- `unit-and-assemble`

This should reduce wall-clock time on PRs and make failures easier to scan.

## Acceptance Criteria

The implementation is complete when these checks pass:

```bash
./gradlew help --warning-mode all --no-daemon
./gradlew :app:assembleDebug --configuration-cache --build-cache --no-daemon
./gradlew :app:assembleDebug --configuration-cache --build-cache --no-daemon
./gradlew :fixthis-mcp:installDist --configuration-cache --build-cache --no-daemon
./gradlew :fixthis-mcp:installDist --configuration-cache --build-cache --no-daemon
./gradlew :fixthis-gradle-plugin:test --no-daemon
./gradlew :fixthis-compose-sidekick:testDebugUnitTest --no-daemon
./gradlew -PoverrideAgpVersion=9.0.0 :app:assembleDebug --no-daemon
./gradlew -PoverrideKotlinVersion=2.2.0 :app:assembleDebug --no-daemon
./gradlew -PoverrideComposeBomVersion=2026.01.00 :app:assembleDebug --no-daemon
node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
git diff --check
```

For the second `:app:assembleDebug` and second `:fixthis-mcp:installDist`, the
expected configuration-cache line is:

```text
Configuration cache entry reused.
```

For the source-index task, the expected `--info` output no longer contains:

```text
Caching disabled for task ':app:generateDebugFixThisSourceIndex' because:
  Caching has not been enabled for the task
```

## Risks and Mitigations

| Risk | Mitigation |
| --- | --- |
| Build cache surfaces an undeclared input in a custom task. | Start by cache-enabling only the source-index task after adding stale-output tests and `--info` verification. |
| Generated Android resource name collides with app resources. | Prefix names with `fixthis_sidekick_`; they live in the sidekick library namespace. |
| Compatibility override wiring affects normal pinned builds. | Only read override properties when present; run normal `:app:assembleDebug` before lower-bound checks. |
| Spotless still blocks global configuration cache. | Keep config cache opt-in and document the reason. Upgrade Spotless in a separate, measured task if needed. |
| Toolchain updates hide cache regressions. | Keep toolchain bumps out of the main cache PR except for a small detekt patch if it removes the Gradle 10 warning. |

## Decision Log

| Date | Decision | Rationale |
| --- | --- | --- |
| 2026-05-14 | Enable local build cache before global configuration cache | Build cache is low-risk and immediately useful; configuration cache needs Spotless follow-up. |
| 2026-05-14 | Optimize sidekick BuildInfo before MCP BuildInfo | Sidekick metadata can trigger Android/Kotlin recompilation; MCP distribution builds are cheaper and already cache-reusable when clean. |
| 2026-05-14 | Wire compatibility overrides before promoting nightly matrix | A matrix that ignores override properties gives false confidence. |
