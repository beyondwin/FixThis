# CI Console Precheck — Merge Gate Design

**Date:** 2026-05-14
**Scope:** Audit the cross-plan slice that moves cheap console checks ahead of the long Gradle baseline and splits them into a separate CI job.
**Status:** Merged across two parent plans.

---

## 1. Current Plan Reference

This effort does **not** have a standalone plan. It is a slice that the two larger optimization plans both touch:

- [`docs/superpowers/plans/2026-05-14-test-speed-optimization-implementation.md`](../plans/2026-05-14-test-speed-optimization-implementation.md) — **Task 5: Split Fast Console CI From Gradle Verification.**
- [`docs/superpowers/plans/2026-05-14-build-optimization-implementation.md`](../plans/2026-05-14-build-optimization-implementation.md) — **Task 5: Move Cheap Console Checks Earlier in CI.**

### Task → Commit Mapping

| Source Plan | Task | Concrete change | Landed in |
| --- | --- | --- | --- |
| Test speed optimization | Task 5 | Replace single `Baseline verification` job in `.github/workflows/ci.yml` with parallel `console-js` and `gradle-verification` jobs plus an aggregate `baseline` job that keeps the old check name | `c8f1219 ci: split console checks from gradle verification` |
| Build optimization | Task 5 | Within the (then-single) baseline job, lift `node scripts/build-console-assets.mjs --check` and `node --check fixthis-mcp/src/main/resources/console/app.js` ahead of `Run Gradle verification` so stale-bundle PRs fail in seconds, not minutes | `fb3b6aa ci: fail fast on stale console assets` |

The two commits are complementary. The build-optimization commit reordered steps inside the legacy single job; the test-speed commit split that job in two so the console steps actually run on a separate runner.

---

## 2. Completion Audit

### Build-opt Task 5 — Reorder cheap checks first (Done)

- **Evidence:** Commit `fb3b6aa` predates `c8f1219`. After this commit, the legacy `Baseline verification` job ran the two console steps before `Run Gradle verification`. The reorder was a no-op once the next commit moved the steps into a separate job, but it captured the intent and the contract.
- **Local verification:** `node scripts/build-console-assets.mjs --check` and `node --check fixthis-mcp/src/main/resources/console/app.js` exit 0 on `main`.

### Test-speed Task 5 — Split into independent CI job (Done)

- **Evidence file:** `.github/workflows/ci.yml`. Jobs after the merge:
  - `console-js` — runs `actions/checkout@v4`, the bundle check, the syntax check, and `node --test` over the seven console JS test files. Timeout 10 minutes.
  - `gradle-verification` — checkout, JDK 21, Gradle setup, Android SDK, `./gradlew … --no-daemon`, then the `git diff --check` whitespace step. Timeout 45 minutes.
  - `baseline` — `needs: [console-js, gradle-verification]`, `if: always()`, exits non-zero if either dependency was not `success`. Job name `Baseline verification` is preserved so branch protection rules carry over.
- **Pull-request behavior:** A PR that breaks the console bundle now fails the `console-js` job in well under two minutes, instead of waiting on the 10–20 minute Gradle matrix.
- **Branch behavior on `main`:** Both jobs run; the aggregate `baseline` job is what `concurrency` cancels stack on.

### Cross-plan interaction

- **Both Task 5s share the same file.** The merge order was: `fb3b6aa` (build-opt) first, then `c8f1219` (test-speed). The latter rewrote the workflow body wholesale, so the reorder from `fb3b6aa` survived as the step ordering inside the new `console-js` job.
- **Branch-protection contract:** The aggregate `baseline` job name is the load-bearing string. Anything that renames it must be coordinated with the GitHub repo settings.

---

## 3. Additional Risks Discovered

1. **Duplicate `actions/checkout@v4` runs.** The split means both jobs now check out the repo. On large repos this is measurable; on this repo it is sub-second. No action.
2. **`console-js` does not run Spotless/detekt on JS-adjacent Kotlin.** A change that touches `fixthis-mcp/src/main/resources/console/app.js` together with the Kotlin route that serves it will still wait on `gradle-verification` for the Kotlin half. Acceptable: the console-js job is intentionally narrow.
3. **`if: always()` on the aggregate job hides intermediate cancellations.** If `concurrency.cancel-in-progress` cancels both upstream jobs, the aggregate job runs and produces a clean failure message, which is the intended UX. Documented here so the next maintainer does not "fix" it.
4. **No flake-quarantine for the seven `node --test` files.** A single flaky console test now gates merges directly without the Gradle suite's masking effect. Mitigation: keep the console JS tests deterministic — they currently all use fake clocks and in-memory state.
5. **`baseline` job is the only signal branch protection sees.** If the workflow YAML breaks before `baseline` runs (e.g. invalid YAML), the protected job never appears and PRs cannot merge. Mitigation: a local `ruby -e 'require "yaml"; YAML.load_file(".github/workflows/ci.yml")'` step is recommended in `CONTRIBUTING.md`.

---

## 4. Merge Gate Checklist

- [x] `.github/workflows/ci.yml` parses as valid YAML.
- [x] `console-js` job runs `build-console-assets.mjs --check`, `node --check`, and the seven `node --test` files.
- [x] `gradle-verification` job runs the full Gradle matrix and ends with the whitespace check.
- [x] `baseline` job aggregates results from both and preserves the `Baseline verification` display name.
- [x] Branch protection rules in the repo still pass on `main` (verified by latest green build on `main`).
- [x] `concurrency.cancel-in-progress: true` still cancels old runs on push.
- [x] No `Run console JavaScript tests` step remains inside `gradle-verification`.
- [x] No step inside `console-js` requires Java, the Android SDK, or Gradle.
- [x] PR description (`codex/test-speed-optimization`) documents the timing improvement.

---

## 5. Follow-up Work

1. **Capture a before/after CI timing report.** The expected win is double-digit minutes on PRs that only touch the console JS. Three weeks of `actions` timings would substantiate this and turn it into a runbook anecdote.
2. **Add a CI lint that asserts the `baseline` aggregate name is unchanged.** A unit test under `:fixthis-mcp:test` that loads `.github/workflows/ci.yml` and asserts the aggregate job name would prevent accidental drift.
3. **Consider extracting a third job for `gradle :app:assembleDebug` alone.** Today the long Gradle step contains both unit tests and a debug APK assemble. Splitting them would let the unit-test signal beat the APK assemble on PRs that do not touch app code. Out of scope for this gate.
4. **Promote whitespace check to its own job.** `git diff --check` is the cheapest possible verifier. It currently rides in `gradle-verification` only because it needs `fetch-depth: 0`. A dedicated job would surface whitespace failures within seconds.
5. **Wire `:detekt` into `console-js` when it changes JS-relevant Kotlin.** Skipped intentionally for the first iteration.

---

## 6. References

- Workflow: `.github/workflows/ci.yml`
- Key commits: `fb3b6aa`, `c8f1219`, merges `2ce4f1b` and `03e7fde`.
- Sibling merge gates: [`2026-05-14-test-speed-merge-gate-design.md`](2026-05-14-test-speed-merge-gate-design.md), [`2026-05-14-build-optimization-merge-gate-design.md`](2026-05-14-build-optimization-merge-gate-design.md).
- Console harness automation: [`2026-05-14-console-harness-automation-design.md`](2026-05-14-console-harness-automation-design.md) — adds a nightly Playwright job alongside the existing `console-js` job.
