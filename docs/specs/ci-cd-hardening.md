# Spec — CI/CD Hardening

Status: Draft
Scope: `.github/workflows/`, repo automation
Related plan: [`../plans/ci-cd-hardening.md`](../plans/ci-cd-hardening.md)

## Background

Current CI (`.github/workflows/ci.yml`) runs unit tests, builds the sample
APK + CLI/MCP distributions, checks JavaScript syntax via `node --check`,
and runs `git diff --check` for whitespace. There is no formatting/lint gate,
no static analysis, no dependency scanning, and no automated instrumented
tests. This is acceptable for a pre-release internal tool; it is not
acceptable for a public Apache 2.0 project once external contributions start
arriving.

## Goals

- Lint and format gates that prevent style drift across contributors.
- Static analysis (Kotlin + JS) and supply-chain awareness as automated
  PR checks.
- A path — even if nightly and unattended — toward running Compose UI tests
  on a real or virtual Android device in CI.
- Required PR checks that are fast, deterministic, and meaningful.

## Non-Goals

- Replacing the existing baseline workflow; new jobs are added alongside.
- Self-hosted runners; everything must work on standard GitHub-hosted ones
  for now.
- Coverage gating on a specific percentage — start by publishing the number
  before policing it.

## Items

### CI-1 — Kotlin lint + format (`ktlint` or `spotless`)

**Contract:** the repo adopts one Kotlin formatter, applied to every
`.kt`/`.kts` file under module `src/`. CI enforces that a fresh clone passes
`./gradlew check` (or equivalent task) without re-formatting.

Recommendation: **Spotless** with `ktlint` ruleset, because Spotless also
covers the JS console bundle and Markdown headers later if needed.

**Acceptance:**
1. `./gradlew spotlessCheck` is a CI step.
2. `./gradlew spotlessApply` is documented in `CONTRIBUTING.md`.
3. The first PR that introduces this baseline reformats existing code in
   one commit, isolated from logic changes.

### CI-2 — Static analysis (`detekt`)

**Contract:** Detekt with a curated config (`config/detekt/detekt.yml`)
runs on every PR. Initial config favours signal over noise:
- Complexity: `LongMethod`, `LongParameterList`, `ComplexCondition`,
  `LargeClass`.
- Style: `ReturnCount`, `MagicNumber` (only on production, off in tests).
- Exceptions: `SwallowedException`, `TooGenericExceptionCaught`.
- Performance: `ForEachOnRange`, `SpreadOperator`.

Initial run is informational only (`failOnSeverity: never`); after baseline
file is committed, severity gates the build.

**Acceptance:**
1. `:detekt` aggregates over every module.
2. Baseline (`detekt-baseline.xml`) committed; new violations fail CI.

### CI-3 — Dependency scanning + updates

**Contract:**
- Dependabot enabled for `gradle`, `github-actions`, and `npm`.
- A `gradle-dependency-update` workflow (manually triggered + weekly cron)
  surfaces stale versions in `libs.versions.toml`.
- CodeQL enabled for `java-kotlin` (and `javascript-typescript` for the
  console bundle).

**Acceptance:**
1. `.github/dependabot.yml` present and parseable.
2. CodeQL workflow runs on push to `main` and on PRs; results visible in
   Security tab.

### CI-4 — Instrumented tests path

The sidekick is a Compose-debug tool — connected UI tests are the highest-
signal regression guard. Provision them as nightly first.

**Contract:**
- New workflow `connected-tests.yml` using
  `reactivecircus/android-emulator-runner` on `macos-14` runners.
- Runs `:app:connectedDebugAndroidTest` and
  `:fixthis-compose-sidekick:connectedDebugAndroidTest`.
- Scheduled nightly; manual `workflow_dispatch` for ad-hoc runs.
- Failure does **not** block PRs initially — annotate, do not require.

**Acceptance:**
1. Workflow exists and runs to completion on `main` for one week.
2. Once stable, promoted to required status check on PRs touching
   `:fixthis-compose-sidekick` or `:app`.

### CI-5 — PR checks contract

The set of required PR checks is published in `CONTRIBUTING.md`:

| Required | Job |
|---|---|
| ✓ | Baseline (existing) — unit tests, builds, JS syntax, whitespace |
| ✓ | Spotless / ktlint |
| ✓ | Detekt |
| ✓ | CodeQL — Kotlin |
| — | Connected tests (annotation only until stabilised) |
| — | Dependency updates (informational) |

**Acceptance:** repository branch protection rule reflects the table above.

## Out-of-scope follow-ups

- Coverage gating with Kover / JaCoCo + Codecov.
- A merge queue.
- Self-hosted runners for faster connected tests.
- Release publishing pipeline (Maven Central, Plugin Portal).
