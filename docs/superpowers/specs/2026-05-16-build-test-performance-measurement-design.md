# Spec — Build & Test Performance Measurement Infrastructure

Status: Draft
Date: 2026-05-16
Scope: Gradle build tasks (`assemble*`, `installDist`, `:*:test`), JUnit/Robolectric test forks, console JS test runner (`scripts/run-console-tests.mjs`), CI report integration.
Related (unfinished) plans this work is a prerequisite for:
- [`../plans/2026-05-14-build-optimization-implementation.md`](../plans/2026-05-14-build-optimization-implementation.md)
- [`../plans/2026-05-14-test-speed-optimization-implementation.md`](../plans/2026-05-14-test-speed-optimization-implementation.md)
Related implementation plan: [`../plans/2026-05-16-build-test-performance-measurement-implementation.md`](../plans/2026-05-16-build-test-performance-measurement-implementation.md)

## Summary

Two prior optimization tracks (`codex/build-optimization-20260513T193303Z`,
`codex/test-speed-optimization`) authored 1100+ lines of spec/plan in
2026-05-14 but only the lowest-risk pieces landed on `main` (build cache
enable, focused-test docs, fast event log writer, MCP build-info
stabilization). The remaining tasks stalled because there is no way to
**prove** an optimization helped or to **prevent** a future change from
regressing. Reviewers cannot read a config diff and tell whether a 5%
change is signal or hardware noise.

This spec defines a **measurement-first** workstream that delivers three
artifacts:

1. A benchmark **harness** that runs Gradle/Node tasks N times, drops
   outliers, and reports median, p95, and stddev as JSON.
2. A **comparator** that diffs two JSON reports against a configurable
   regression threshold (median delta + stddev band) and exits non-zero
   on regression.
3. A committed **baseline** of current-`main` measurements plus a **CI
   report** that publishes per-PR deltas.

To prove the harness is real and useful, we also execute **one** small,
low-risk Gradle tweak (raise `org.gradle.jvmargs -Xmx` from 2048m to a
value chosen by measurement) and gate the merge on the harness reporting
a statistically significant improvement on at least one tracked
scenario. If the data does not support the change, we revert it — the
plan must work for negative results.

The benchmark numbers themselves are **not** unit tests (they are flaky
on shared hardware). The parser, comparator, and CLI orchestration are
unit-tested with synthetic fixtures.

## Goals

- Add a `scripts/perf/` directory containing:
  - `bench-gradle.mjs` — driver that runs a named Gradle scenario N
    times, captures `--profile` output, emits JSON.
  - `bench-node.mjs` — driver that runs a named `node --test` group N
    times, emits JSON.
  - `parse-gradle-profile.mjs` — parses Gradle's `build/reports/profile/*.html`
    into a structured object.
  - `compare-perf.mjs` — diffs two JSON reports, prints a markdown
    table, exits non-zero on regression beyond threshold.
  - `perf-scenarios.json` — single source of truth for tracked
    scenarios (task name, warm/cold mode, iteration count, threshold).
- Unit-test the parser and comparator with synthetic fixtures
  (`scripts/perf/__fixtures__/`).
- Commit a baseline file at `docs/perf/baseline-2026-05-16.json`
  captured from current `main` HEAD on the contributor's machine.
- Add an `npm run perf:bench` entry that runs all scenarios and an
  `npm run perf:compare -- <baseline> <current>` entry for diff
  reporting.
- Add a GitHub Actions workflow `.github/workflows/perf-report.yml`
  that runs on `workflow_dispatch` (and nightly) and uploads the JSON
  artifact, plus posts a markdown summary to the run.
- Execute one real optimization (Gradle JVM heap tuning) and merge it
  with the harness output attached to the PR as the proof-of-value
  exhibit, or revert it if measurements don't support the claim.
- Document the workflow in `CONTRIBUTING.md` under a new
  "Performance Measurement" section.

## Non-Goals

- No new optimizations beyond the single Gradle JVM tweak used as the
  proof case. Configuration-cache rollout, Android `nonTransitiveRClass`,
  test parallel forks, etc. are deferred to the existing 2026-05-14
  plans and will be re-validated against this harness in later PRs.
- No removal of `--no-daemon` from `verify-ci-local.sh`. That choice is
  a separate decision and depends on data this harness will eventually
  produce.
- No remote build cache (HTTP cache backend). Local cache only.
- No JS bundler benchmarking (`scripts/build-console-assets.mjs`
  already enforces its own size budget; runtime is bounded by it).
- No Android connected-test benchmarking. Connected tests are nightly
  only and emulator startup dominates wall-clock.
- No replacement of the existing Gradle build scan / `--scan`
  workflow. The harness consumes `--profile` (always available, no
  account required) for offline reproducibility.
- No statistical-significance computation beyond mean / median / p95 /
  stddev. We intentionally avoid t-tests etc. because N is small
  (≤ 7 per scenario by default) and contributors' hardware varies; a
  simple "median delta exceeds threshold AND falls outside 2σ band of
  baseline" rule is sufficient for the gate.

## Current State

### Gradle configuration (`gradle.properties`)

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
kotlin.code.style=official
```

Configuration cache is off (`spotlessCheck` reuse issue, see
CONTRIBUTING.md). Android-specific flags (`nonTransitiveRClass`,
`enableR8.fullMode`) are not set. Kotlin daemon JVM args are not
tuned. `org.gradle.workers.max` is unset (defaults to CPU count).

### Test surface

- `:fixthis-mcp` — 74 test files (largest module, includes
  architecture guardrails, console route tests, event-log tests).
- `:fixthis-compose-sidekick` — 25 test files (`testDebugUnitTest`,
  Robolectric 4.16.1 available).
- `:fixthis-compose-core` — 24 test files.
- `:fixthis-cli` — 17 test files.
- `:fixthis-gradle-plugin` — 7 test files (TestKit-driven, slow).
- `:app` (sample) — 2 test files.
- Console JS — 45+ files orchestrated via
  `scripts/run-console-tests.mjs` calling `node --test <files...>`.
  Node 25 runs files in parallel by default.

No test task in `build.gradle.kts` sets `maxParallelForks`,
`maxHeapSize`, or `forkEvery`. JUnit/Robolectric inherit Gradle daemon
defaults.

### CI shape

`ci.yml` runs the full Gradle + console + doc gates. There is no
performance signal in CI — only a pass/fail. The `connected-tests.yml`
nightly is the only timing-aware workflow and it only watches for
timeouts, not regressions.

### Prior unfinished plans (do not duplicate)

The 2026-05-14 build-optimization plan covers: build cache (landed),
configuration-cache for focused loops (docs landed; rollout deferred),
`:fixthis-gradle-plugin` `GenerateFixThisSourceIndexTask` caching
(deferred), sidekick build-info moved to resources (deferred),
compatibility-matrix override plumbing (deferred), CI reordering
(deferred).

The 2026-05-14 test-speed-optimization plan covers: fast event-log
writer (landed), compactor fixture shrink (landed), CI split for
console vs Gradle (partially landed), and several test fork tuning
proposals (deferred).

This spec deliberately does **not** repeat those tasks. Instead it
gives them the missing prerequisite: a way to prove they help.

## Measurement Methodology

### Scenarios (initial set)

Defined in `scripts/perf/perf-scenarios.json`. Each scenario captures
one realistic developer workflow:

| Key | Gradle / Node command | Mode | N | Threshold |
| --- | --- | --- | --- | --- |
| `cold-mcp-test` | `:fixthis-mcp:test` after `clean` | cold | 3 | regress > 10% |
| `warm-mcp-test` | `:fixthis-mcp:test` (cache warm) | warm | 5 | regress > 8% |
| `cold-assemble` | `:app:assembleDebug` after `clean` | cold | 3 | regress > 10% |
| `warm-assemble` | `:app:assembleDebug` (cache warm) | warm | 5 | regress > 8% |
| `installdist-mcp` | `:fixthis-mcp:installDist` (warm) | warm | 5 | regress > 8% |
| `console-test-fast` | `npm run console:test:fast` | warm | 5 | regress > 10% |
| `console-test-all` | `npm run console:test:all` | warm | 5 | regress > 10% |

Cold = preceding `./gradlew clean` plus deletion of
`~/.gradle/caches/build-cache-1/` keyed by scenario hash (we restore
afterwards). Warm = no clean; first iteration is warm-up and discarded.

### Aggregation

For each scenario:

1. Drop the first iteration (warm-up).
2. If N - 1 ≥ 4, drop the min and max.
3. Compute mean, median, p95 (linear interpolation), stddev (sample,
   Bessel's correction).
4. Emit `{ scenario, n, samples_ms[], median_ms, p95_ms, stddev_ms,
   mean_ms, dropped: [reasons] }`.

Top-level JSON includes: ISO-8601 timestamp, git SHA, JDK version,
Node version, OS, CPU model, total physical RAM, scenario results.

### Regression rule

Given baseline B and current C for the same scenario:

- Let `delta_pct = (C.median - B.median) / B.median * 100`.
- Let `noise_band_ms = max(2 * B.stddev, 0.02 * B.median)` (2σ
  or 2% of median, whichever is larger — protects against zero-stddev
  artifacts).
- **Regression** iff `C.median - B.median > noise_band_ms` AND
  `delta_pct > scenario.threshold_pct`.
- **Improvement** iff `B.median - C.median > noise_band_ms` AND
  `-delta_pct >= scenario.improvement_threshold_pct` (default 5%).
- Else: **neutral** (within noise).

The comparator exits 1 on any regression, 0 otherwise. Improvements
are highlighted in the markdown summary but do not block merge.

### Daemon mode

The Gradle harness runs with `--no-daemon` so it measures the same
process startup + first-task cost contributors hit in
`scripts/verify-ci-local.sh` and the pre-push hook. A daemon-on
scenario set may be added later as a separate measurement target
(the IDE inner-loop experience), but the proof-of-value
optimization in this plan is gated against the real contributor
loop, not the optimistic warm-daemon path.

### Hardware variance

Contributors will run on different machines (M-series Mac, x86 Linux
CI, etc.). The harness records environment fingerprint. The baseline
file commits a fingerprint and the comparator **warns but does not
fail** when fingerprints differ — instead it suggests re-baselining
locally. CI runs always compare against a CI-recorded baseline stored
in the workflow run artifacts of `main`, not the committed baseline.

## Proof-of-Value Optimization

To validate that the harness produces useful signal, the same plan
includes one minimal Gradle change: raise `org.gradle.jvmargs -Xmx`
from `2048m`. The exact target is chosen by measurement (the plan
walks through trying `4096m` and `6144m`, picking the smaller value
that produces ≥ 5% median improvement on `warm-mcp-test` without
regressing any other scenario). If neither value produces a
statistically significant improvement, the change is reverted and the
PR documents the negative result.

Either outcome ships the harness with empirical proof of what it
measures.

## Acceptance Criteria

1. `npm run perf:bench -- --scenario warm-mcp-test --iterations 3`
   completes and emits a JSON file under `output/perf/` with the
   schema above. Verified by running on the contributor's machine.
2. `node scripts/perf/compare-perf.mjs <baseline> <current>` prints a
   markdown table with one row per scenario and a per-scenario verdict
   (`OK` / `REGRESS` / `IMPROVE`).
3. Unit tests in `scripts/perf/__fixtures__/` cover:
   - Parser produces expected structure from a captured
     `profile-2026-05-16-baseline.html` fixture.
   - Comparator detects regression when median delta exceeds
     threshold and band.
   - Comparator detects improvement and prints highlight row.
   - Comparator stays neutral when delta within noise band.
   - Aggregator drops min/max correctly with N=5; does not drop when
     N=3.
4. `docs/perf/baseline-2026-05-16.json` is committed and includes all
   scenarios with at least 3 successful samples each.
5. `CONTRIBUTING.md` has a "Performance Measurement" section linking
   to `scripts/perf/README.md`.
6. `.github/workflows/perf-report.yml` exists with `workflow_dispatch`
   trigger, runs the harness, uploads JSON artifact, and posts a
   markdown summary to the run via `$GITHUB_STEP_SUMMARY`.
7. The Gradle JVM tweak PR contains a comment with the harness output
   showing either ≥ 5% improvement on at least one scenario (merge)
   or no significant change (revert).

## Risks

| Risk | Mitigation |
| --- | --- |
| Harness itself is slow and discourages use. | Default N small (3-5), opt-in via npm script not pre-push. Document a "quick mode" with N=2 for spot checks. |
| Different contributor hardware produces incomparable JSON. | Fingerprint in JSON; comparator warns on mismatch; CI uses its own baseline. |
| Gradle daemon state between iterations distorts measurements. | "Cold" scenarios explicitly clean. "Warm" scenarios drop iteration 1. The harness reuses one daemon across iterations to match real loop. |
| `--profile` HTML format changes across Gradle versions. | Parser is small (regex-based) and tested with a committed fixture; if Gradle 9 ever changes format, the parser test fails fast. |
| Single proof-of-value change is too small to validate gate. | Acceptable — the harness is the deliverable; a negative result is a valid PR outcome and still proves the gate works. |
| Contributors don't know to run the harness. | `CONTRIBUTING.md` change documents it; no enforced pre-push gate (too slow). |
| Hardware fingerprint leaks PII (CPU model, RAM). | Same info `nproc` and `uname -a` already expose; nothing user-identifying. Document this in the perf README. |

## Open Questions

None blocking. Future plans (config-cache, fork tuning, etc.) will
use this harness; their numeric thresholds will be calibrated against
the committed baseline at that time.
