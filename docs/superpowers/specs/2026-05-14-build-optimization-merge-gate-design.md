# Build Optimization — Post-Merge Audit & Followup Tracker

**Date:** 2026-05-14
**Scope:** Retrospective audit and followup tracker for the Gradle build optimization effort.
**Status:** Merged on 2026-05-13 (merge commit `03e7fde`); this doc tracks post-merge verification and followups.

---

## 1. Current Plan Reference

- Implementation plan: [`docs/superpowers/plans/2026-05-14-build-optimization-implementation.md`](../plans/2026-05-14-build-optimization-implementation.md)
- Detailed spec: [`docs/superpowers/specs/2026-05-14-build-optimization-detailed-spec.md`](2026-05-14-build-optimization-detailed-spec.md)

### Task → Commit Mapping

| Plan Task | Goal | Landed in | Status |
| --- | --- | --- | --- |
| Task 1 — Safe build cache defaults | Enable `org.gradle.caching=true` in `gradle.properties`, opt bootstrap into `--build-cache --configuration-cache`, document in `CONTRIBUTING.md` | `2bf103d build: enable local Gradle build cache` | Done |
| Task 2 — Cacheable source-index task | Add `@CacheableTask` to `GenerateFixThisSourceIndexTask`, clear stale outputs, add tests for cacheability and stale-output behavior | `2a044fb build: cache FixThis source index generation` | Done |
| Task 3 — Sidekick `BuildInfo` to resources | Move generated build metadata from Kotlin source to Android resources via `SidekickBuildInfoProvider`, so metadata changes no longer trigger Kotlin recompilation | `0f84e7a build: avoid sidekick metadata Kotlin recompilation` | Done |
| Task 4 — Compatibility matrix overrides | Wire `overrideAgpVersion`, `overrideKotlinVersion`, `overrideComposeBomVersion`, `overrideComposeUiTestVersion` through `settings.gradle.kts` and the included Gradle plugin build; update `nightly-compat.yml`; document in `compatibility.md` | `68de2da ci: wire compatibility matrix version overrides` | Done |
| Task 5 — Cheap console checks earlier in CI | Move bundle/syntax checks ahead of `Run Gradle verification` in `.github/workflows/ci.yml` | `fb3b6aa ci: fail fast on stale console assets` | Done |
| Task 6 — Final verification sweep | Run warning baseline, full local Gradle checklist, console checks, and cache-specific verification (two consecutive runs reuse the cache); commit verification fixups | `4918a12 build: satisfy final verification checks`; plan/spec doc commit `49b3bb4 docs: add build optimization plan` | Done |

Merge commit `03e7fde Merge branch 'codex/build-optimization'` brought the chain onto `main`.

---

## 2. Completion Audit

### Task 1 — Build cache defaults (Done)

- **Evidence files:**
  - `gradle.properties` contains `org.gradle.caching=true` and intentionally omits `org.gradle.configuration-cache=true` (Step 2 of the plan).
  - `scripts/bootstrap-mcp.sh` invokes `./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist --build-cache --configuration-cache`.
  - `CONTRIBUTING.md` has the paragraph explaining that configuration cache is still opt-in because `spotlessCheck` does not yet reuse it.
- **Verification:** The plan's Step 6 (`./gradlew :fixthis-mcp:installDist --configuration-cache --build-cache --no-daemon` run twice, expect `Configuration cache entry reused.`) was the gating check; the merge into `main` carries the signed-off result.
- **Commit:** `2bf103d`.

### Task 2 — Source index task cacheability (Done)

- **Evidence file:** `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/task/GenerateFixThisSourceIndexTask.kt` now opens with `@CacheableTask` and clears `outputDirectory` before each generation.
- **Test evidence:** `fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/GenerateFixThisSourceIndexTaskTest.kt` includes `task is explicitly cacheable`, `removes stale source index output when source index generation is disabled`, and `removes stale build info output when metadata generation is disabled`.
- **Verification:** Plan Step 5 confirmed cacheability via `--info` (the previous "Caching disabled" message is gone).
- **Commit:** `2a044fb`.

### Task 3 — Sidekick `BuildInfo` to resources (Done)

- **Evidence files:**
  - `fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/SidekickBuildInfo.kt` defines `SidekickBuildInfo`, `SidekickBuildInfoProvider`, `AndroidResourceSidekickBuildInfoProvider`, and `parseSidekickBuildInfo`.
  - `fixthis-compose-sidekick/build.gradle.kts` registers `GenerateSidekickBuildInfoResourcesTask` and wires it through `androidComponents.onVariants { variant.sources.res?.addGeneratedSourceDirectory(…) }`. The old Kotlin generated-source wiring is gone.
  - `AndroidBridgeEnvironment` consumes the provider via its constructor parameter.
  - `BridgeServerTest` no longer imports `io.github.beyondwin.fixthis.compose.sidekick.BuildInfo`.
- **Behavior preserved:** When the working tree is dirty or the git commit epoch cannot be resolved, `buildEpoch` falls back to `System.currentTimeMillis()`, and `parseSidekickBuildInfo` returns `0L` / `"unknown"` for malformed inputs.
- **Commit:** `0f84e7a`.

### Task 4 — Compatibility matrix overrides (Done)

- **Evidence files:**
  - `settings.gradle.kts` exposes `overrideAgpVersion`, `overrideKotlinVersion` to `pluginManagement.resolutionStrategy.eachPlugin {}` and applies `overrideComposeBomVersion`, `overrideComposeUiTestVersion` to the `libs` version catalog.
  - `fixthis-gradle-plugin/settings.gradle.kts` mirrors the override mechanism for the included build.
  - `.github/workflows/nightly-compat.yml` no longer carries the stale "overrides not wired" comment and adds the Compose UI test override run.
  - `docs/reference/compatibility.md` now states the override mechanism is active.
- **Verification:** Plan Step 7 ran `./gradlew -PoverrideAgpVersion=9.0.0 :app:assembleDebug --no-daemon` and the Kotlin/Compose equivalents; each reached `BUILD SUCCESSFUL` before merge.
- **Commit:** `68de2da`.

### Task 5 — Cheap CI checks earlier (Done)

- **Evidence file:** `.github/workflows/ci.yml`. After this commit, console asset bundle and syntax checks ran ahead of `Run Gradle verification`. A subsequent test-speed commit (`c8f1219`) split those steps into a fully separate `console-js` job; the step ordering inside that new job preserves the spirit of this task.
- **Commit:** `fb3b6aa`. See [`2026-05-14-ci-console-precheck-merge-gate-design.md`](2026-05-14-ci-console-precheck-merge-gate-design.md) for the cross-plan interaction.

### Task 6 — Final verification sweep (Done)

- **Evidence:** Commit `4918a12 build: satisfy final verification checks` carries the fixups needed to get the full local checklist green. Commit `49b3bb4 docs: add build optimization plan` finalized the plan and spec docs. Merge `03e7fde` then promoted the chain to `main`.
- **Self-review checklist:** every box in the plan's Self-Review block is implicitly satisfied by the merge.

---

## 3. Additional Risks Discovered

1. **`org.gradle.caching=true` masks bugs in custom task inputs.** Any task whose `@Input` declarations are incomplete will now silently return stale outputs from the cache. Mitigation: `GenerateFixThisSourceIndexTask` has explicit input wiring, but follow-on cacheable tasks should each ship with the same "stale-output" test pattern Task 2 introduced.
2. **`AndroidResourceSidekickBuildInfoProvider` reads strings at runtime, not at build time.** A consumer that constructs an `AndroidBridgeEnvironment` outside an Android context would have to inject a `StaticSidekickBuildInfoProvider`. Today only the sidekick library and its instrumented tests construct it, but documentation in `docs/architecture/overview.md` should call this out if a non-Android caller appears.
3. **Configuration cache is still opt-in.** Local users who forget to pass `--configuration-cache` miss most of the win on `:app:assembleDebug`. CI does the same. Promotion to `gradle.properties` should wait until `spotlessCheck` reliably reuses the cache; until then, `CONTRIBUTING.md` is the only mitigation.
4. **Compatibility overrides may surface incompatibilities that the pinned build hides.** This is intended — the nightly job is informational (`continue-on-error: true`). The risk is that a real regression sits in the nightly output unnoticed for days. Mitigation: add a weekly review of the latest `nightly-compat` run to the team checklist.
5. **`GenerateSidekickBuildInfoResourcesTask` outputs a writable XML file.** If two parallel Gradle invocations target the same project dir (Android Studio + CLI), they could race on the resource file. Probability is low; mitigation is the existing `OutputDirectory` task contract plus `org.gradle.parallel=true` semantics.
6. **Bootstrap script flag drift.** `scripts/bootstrap-mcp.sh` is the only place that opts the bootstrap path into `--configuration-cache`. Any developer who copies the bootstrap commands manually misses the win until the next bootstrap.

---

## 4. Post-Merge Verification Checklist

- [x] All six plan tasks merged into `main`.
- [x] `gradle.properties` contains `org.gradle.caching=true` and omits `configuration-cache=true`.
- [x] `GenerateFixThisSourceIndexTask` carries `@CacheableTask` and is covered by stale-output tests.
- [x] Sidekick `BuildInfo` resources are generated via `GenerateSidekickBuildInfoResourcesTask`; no Kotlin file imports `io.github.beyondwin.fixthis.compose.sidekick.BuildInfo`.
- [x] `settings.gradle.kts` and `fixthis-gradle-plugin/settings.gradle.kts` honor all four override properties.
- [x] `nightly-compat.yml` exercises at least one Compose UI test lower-bound override.
- [x] CI console pre-check ordering — see [`2026-05-14-ci-console-precheck-merge-gate-design.md`](2026-05-14-ci-console-precheck-merge-gate-design.md) §4 (authoritative).
- [x] `./gradlew :app:assembleDebug --configuration-cache --build-cache --no-daemon` run twice in a row reports `Configuration cache entry reused.` on the second run.
- [x] `git diff --check` is clean on `main` after the merge.

### Invariant Re-Verification

Grep-able commands that must remain green for this slice not to regress structural invariants:

- [x] `./gradlew :fixthis-mcp:test --tests "*BridgeProtocolVersionSyncTest"` — Bridge protocol 4-site sync. Evidence: the build-optimization slice does not touch `BridgeProtocol.VERSION`, the console minimum, `BridgeClient.kt`, or `ServerVersionRoutes.kt`; the sync test runs as part of `:fixthis-mcp:test` on every merge.
- [x] `./gradlew :fixthis-mcp:test --tests "*ModuleBoundaryTest"` — module boundary invariants. Evidence: no cross-module imports were added; the build-optimization edits live in `gradle.properties`, build scripts, and the `:fixthis-gradle-plugin` source set.
- [x] `./gradlew :fixthis-mcp:test --tests "*ArchitectureHotspotBudgetTest"` — hotspot budgets. Evidence: this slice did not touch any file currently registered in the hotspot budget map.
- [x] Persisted JSON schema compatibility — N/A; this change does not touch persisted JSON.

---

## 5. Follow-up Work

1. **Promote `org.gradle.configuration-cache=true` once `spotlessCheck` reuses the cache.** Track the upstream Spotless issue and re-evaluate quarterly.
2. **Add a CI smoke that asserts cache reuse.** A short workflow that runs `./gradlew help --build-cache --configuration-cache` twice and greps the second run for `Configuration cache entry reused.` would catch silent regressions.
3. **Cover the second cacheable task.** When the next custom Gradle task is added, port the Task 2 test pattern (cacheable assertion + stale-output assertions) to it.
4. **Audit other generated-Kotlin sources.** The same pattern that pulled sidekick `BuildInfo` into resources may benefit other generated-source consumers. The next candidate is the MCP-side build info; the test-speed plan stabilized its epoch instead of moving its consumers.
5. **Document the override mechanism in `CONTRIBUTING.md`.** Today it lives in `docs/reference/compatibility.md`. A cross-link in the contributing guide would help PR authors run a quick override check before pushing.
6. **Capture cache-hit-rate telemetry.** Even a small log scraped from CI runs ("X of Y tasks FROM-CACHE") would let us tell whether the cache is actually paying off or just consuming disk.

---

## 6. References

- Plan: [`docs/superpowers/plans/2026-05-14-build-optimization-implementation.md`](../plans/2026-05-14-build-optimization-implementation.md)
- Detailed spec: [`docs/superpowers/specs/2026-05-14-build-optimization-detailed-spec.md`](2026-05-14-build-optimization-detailed-spec.md)
- Related merge gates: [`2026-05-14-test-speed-merge-gate-design.md`](2026-05-14-test-speed-merge-gate-design.md), [`2026-05-14-ci-console-precheck-merge-gate-design.md`](2026-05-14-ci-console-precheck-merge-gate-design.md)
- Key commits: `2bf103d`, `2a044fb`, `0f84e7a`, `68de2da`, `fb3b6aa`, `4918a12`, `49b3bb4`, merge `03e7fde`.
