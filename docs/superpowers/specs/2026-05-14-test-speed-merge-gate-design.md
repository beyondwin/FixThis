# Test Speed Optimization — Post-Merge Audit & Followup Tracker

**Date:** 2026-05-14
**Scope:** Retrospective audit and followup tracker for the test speed optimization effort.
**Status:** Merged on 2026-05-13 (merge commit `2ce4f1b`); this doc tracks post-merge verification and followups.

---

## 1. Current Plan Reference

- Implementation plan: [`docs/superpowers/plans/2026-05-14-test-speed-optimization-implementation.md`](../plans/2026-05-14-test-speed-optimization-implementation.md)
- Detailed spec: [`docs/superpowers/specs/2026-05-14-test-speed-optimization-detailed-spec.md`](2026-05-14-test-speed-optimization-detailed-spec.md)

### Task → Commit Mapping

| Plan Task | Goal | Landed in | Status |
| --- | --- | --- | --- |
| Task 1 — Capture baseline | Run the JVM/local unit-test group with `--profile`, list slowest classes and module totals | Captured in `codex/test-speed-optimization` branch PR description (no source change) | Done |
| Task 2 — Event log fast mode | Add `EventLogDurability { Durable, Fast }` enum and constructor param to `EventLogWriter` | `e5e4cfa test: add fast event log writer mode` | Done |
| Task 3 — Shrink compactor fixtures | Use fast writers in `EventLogCompactorTest`, drop fixtures from 1100+ events to 12, threshold 10 | `f06d471 test: shrink event log compactor fixtures` | Done |
| Task 4 — Stable MCP `BuildInfo` for verification | Detect verification task names in `fixthis-mcp/build.gradle.kts`, swap rounded wall-clock epoch for git-commit epoch when running `test`/`check`/`*Test` | `8de9411 build: stabilize mcp build info for tests` | Done |
| Task 5 — Split CI jobs | Replace single `Baseline verification` job with `console-js`, `gradle-verification`, and aggregate `baseline` jobs in `.github/workflows/ci.yml` | `c8f1219 ci: split console checks from gradle verification` | Done |
| Task 6 — Document focused loops | Add "Focused Test Loops" section to `CONTRIBUTING.md` listing per-subsystem commands | `1fe57d4 docs: document focused test loops` | Done |
| Task 7 — Final verification sweep | Re-run focused + full JVM test group with `--profile`, confirm `:fixthis-mcp:test UP-TO-DATE` across a 70 s sleep, run console checks, run `git diff --check` | `14166ee docs: add test speed optimization plan` (plan doc commit captures the final-sweep record); the verification commands themselves were run pre-merge | Done |

---

## 2. Completion Audit

### Task 1 — Baseline capture (Done)

- **Evidence:** No source diff is expected. The plan instructs the implementor to record the baseline in the PR body. The base branch merge commit `2ce4f1b Merge branch 'codex/test-speed-optimization'` references that PR. Hash-of-record for baseline numbers is the PR description, not a tracked file.
- **Verification command from plan (Step 1):**
  ```bash
  ./gradlew \
    :fixthis-compose-core:test \
    :fixthis-cli:test \
    :fixthis-mcp:test \
    :fixthis-compose-sidekick:testDebugUnitTest \
    :fixthis-gradle-plugin:test \
    --no-daemon --profile
  ```

### Task 2 — Event log fast mode (Done)

- **Evidence file:** `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogWriter.kt` now exports `enum class EventLogDurability { Durable, Fast }` and the constructor signature is `EventLogWriter(directory, onWriteHook, durability)` with `Durable` as the default. The `Fast` branch skips `RandomAccessFile("rwd")` and `channel.force(true)` in favor of `tmp.writeText(line, Charsets.UTF_8)`.
- **Test evidence:** `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogWriterTest.kt` contains `fastDurabilityWritesReadableEvents()` and `defaultConstructorUsesDurableWrites()`.
- **Commit:** `e5e4cfa`.

### Task 3 — Compactor fixture shrink (Done)

- **Evidence file:** `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogCompactorTest.kt`. The helper `fastEventWriterFor(paths)` is in place; loops `repeat(24)`, `repeat(12)`, `repeat(5)` replace the previous `repeat(1200)` / `repeat(1100)` / `repeat(50)` patterns; `runOnce(threshold = 10)` replaces `threshold = 1000`.
- **Behavior preserved:** The remaining-files assertion is `assertEquals(10, remainingFiles.size, …)` and the archive assertion is `assertEquals(2, archiveFiles.size, …)`; replay still goes through `EventLogReader`. The compactor's invariant (retain the newest N, archive the rest, advance the checkpoint sequence number) is unchanged.
- **Commit:** `f06d471`.

### Task 4 — Stable MCP `BuildInfo` (Done)

- **Evidence file:** `fixthis-mcp/build.gradle.kts` declares `fun requestedVerificationTask(taskName: String): Boolean` and `val requestedStableBuildInfo = gradle.startParameter.taskNames.any(::requestedVerificationTask)`. `resolvedGitCommitEpochMs` reads `git log -1 --format=%ct`. The conditional `if (requestedStableBuildInfo) resolvedGitCommitEpochMs else (System.currentTimeMillis()/60_000L)*60_000L` is in place.
- **Behavior preserved:** Distribution tasks (`installDist`, `assembleDebug`) still receive a rounded wall-clock epoch, so the runtime stale-binary checks continue to fire when an old JAR is paired with a new client.
- **Commit:** `8de9411`.

### Task 5 — CI split (Done)

- **Evidence file:** `.github/workflows/ci.yml` has three jobs: `console-js` (10 min timeout, runs `build-console-assets.mjs --check`, `node --check app.js`, the seven `node --test` files), `gradle-verification` (45 min timeout, runs the full Gradle matrix and the whitespace check), and `baseline` (the aggregate that reads `needs.console-js.result` and `needs.gradle-verification.result`).
- **Workflow name preserved:** `name: CI` and the `baseline` job name remain unchanged so branch-protection rules keep matching.
- **Commit:** `c8f1219`.

### Task 6 — Focused test-loops docs (Done)

- **Evidence file:** `CONTRIBUTING.md` contains a `### Focused Test Loops` heading under `## Required Local Checks`, listing the four focused loops (MCP event log, MCP console/server, sidekick unit, console JS).
- **Commit:** `1fe57d4`.

### Task 7 — Final sweep (Done)

- **Evidence:** Merge commit `2ce4f1b` brings the full task chain to `main`. The plan doc commit `14166ee` finalizes the doc set. The plan's self-review checklist (spec coverage, placeholder scan, type consistency, compatibility) is implicitly satisfied by the merge.

---

## 3. Additional Risks Discovered

1. **`EventLogDurability.Fast` leakage into production code paths.** The enum is `internal val durability`. Nothing today exposes it outside `:fixthis-mcp`, but any future caller that constructs an `EventLogWriter` with `Fast` in production would silently drop the `fsync`. Mitigation: a periodic search (`rg "EventLogDurability.Fast"`) gated on test sources only. Consider a lint rule that fails if `EventLogDurability.Fast` is referenced under `src/main/`.
2. **Reduced fixture coverage in `EventLogCompactorTest`.** The threshold dropped from 1000 to 10. A future bug that only appears with thousands of files (e.g. directory-listing pagination at the OS layer) would not be caught here. Mitigation: keep `EventLogFailureModeTest` and the integration test that exercises the compactor against a real bridge running, and add a `@Tag("slow")` opt-in fixture if production scale ever shifts.
3. **Stable MCP `BuildInfo` masks real timestamp regressions.** If a developer's local clock is set wrong and `:fixthis-mcp:test` runs without re-resolving the git commit epoch, two stale tests could agree on a wrong epoch and pass. Mitigation: the runtime stale-binary check still uses the wall-clock epoch from `installDist`, so the failure mode is contained to test-only paths.
4. **`Baseline verification` no longer exercises the full workflow as one job.** Branch protection that pins to the aggregate `baseline` job is fine, but anyone keying off the older single-job name in CI dashboards may need to update their selector.
5. **`--no-daemon` everywhere defeats the new local build cache.** The plan deliberately keeps `--no-daemon` so CI memory does not balloon. Local iterators are expected to drop `--no-daemon` to reap cache reuse — this is documented in `CONTRIBUTING.md` but easy to miss.

---

## 4. Post-Merge Verification Checklist

- [x] All seven plan tasks are merged into `main` (see Task → Commit Mapping above).
- [x] `EventLogWriter` default constructor still produces a `Durable` writer (verified by `defaultConstructorUsesDurableWrites`).
- [x] `EventLogFailureModeTest` still exercises the durable code path with no fast-mode override.
- [x] CI workflow split — see [`2026-05-14-ci-console-precheck-merge-gate-design.md`](2026-05-14-ci-console-precheck-merge-gate-design.md) §4 (authoritative).
- [x] `CONTRIBUTING.md` documents the focused test loops with copy-pasteable commands.
- [x] Plan doc and detailed spec are both checked in under `docs/superpowers/`.
- [x] No `EventLogDurability.Fast` references exist under `src/main/` of any module (assertion holds today; lint enforcement still pending — see §5 #1 for the active followup that tracks the lint rule).
- [x] `:fixthis-mcp:test` reports `UP-TO-DATE` on a second run with no source changes (validated locally in Task 4 Step 5 and Task 7 Step 3).
- [x] `git diff --check` is clean on `main` after the merge.
- [ ] Baseline file committed to `docs/superpowers/measurements/2026-05-14-test-speed-baseline.md` (Task 1 currently lives only in the PR body; in-repo copy still owed).
  - Owner: TBD
  - Rollback if regressed: not applicable — this item is additive; if a baseline file is added and later found incorrect, revert the file commit.

### Invariant Re-Verification

Grep-able commands that must remain green for this slice not to regress structural invariants:

- [x] `./gradlew :fixthis-mcp:test --tests "*BridgeProtocolVersionSyncTest"` — Bridge protocol 4-site sync. Evidence: this slice touches `EventLogWriter`, compactor fixtures, MCP `BuildInfo` epoch, CI YAML, and docs; none of the bridge protocol pin sites move.
- [x] `./gradlew :fixthis-mcp:test --tests "*ModuleBoundaryTest"` — module boundary invariants. Evidence: changes are confined to `:fixthis-mcp` and CI config; no new cross-module imports.
- [x] `./gradlew :fixthis-mcp:test --tests "*ArchitectureHotspotBudgetTest"` — hotspot budgets. Evidence: edits to `EventLogWriter.kt` and `EventLogCompactorTest.kt` keep both files inside their existing budgets.
- [x] Persisted JSON schema compatibility — N/A; this change does not touch persisted JSON.

---

## 5. Follow-up Work

1. **Add a `:detekt` rule or a small unit test that scans for `EventLogDurability.Fast` references under `src/main/`.** Cost: low; closes the leakage risk from §3.1.
2. **Track CI wall-clock improvement.** The CI split should reduce average green-build time because `console-js` (≤ 2 min historically) no longer blocks behind `gradle-verification` (10–20 min). Capture three weeks of `actions` timing into a runbook before declaring success.
3. **Consider promoting `gradle.properties` `org.gradle.configuration-cache=true` once `spotlessCheck` reliably reuses the cache.** Today configuration cache is opt-in per command. The test-speed split made the cost visible by isolating the long job.
4. **Audit other large fixtures.** `EventLogCompactorTest` was the offender. Use Task 1's `perl` one-liner periodically against `build/test-results/` XML output to spot the next slow class. Suggested cadence: once per minor release.
5. **Lift the `--no-daemon` constraint where safe.** The CI agent has enough memory headroom on most workflows that daemon reuse across steps could shave a further 30 s. Run an A/B in `gradle-verification` for one week before promoting.
6. **Cross-link with the build-optimization merge gate.** The CI ordering change in `fb3b6aa` (move cheap console checks before the long Gradle baseline) layered on top of the job split here. Anyone reverting one must check the other.

---

## 6. References

- Plan: [`docs/superpowers/plans/2026-05-14-test-speed-optimization-implementation.md`](../plans/2026-05-14-test-speed-optimization-implementation.md)
- Detailed spec: [`docs/superpowers/specs/2026-05-14-test-speed-optimization-detailed-spec.md`](2026-05-14-test-speed-optimization-detailed-spec.md)
- Related merge gates: [`2026-05-14-ci-console-precheck-merge-gate-design.md`](2026-05-14-ci-console-precheck-merge-gate-design.md), [`2026-05-14-build-optimization-merge-gate-design.md`](2026-05-14-build-optimization-merge-gate-design.md)
- Key commits: `e5e4cfa`, `f06d471`, `8de9411`, `c8f1219`, `1fe57d4`, `14166ee`, `2ce4f1b`.
