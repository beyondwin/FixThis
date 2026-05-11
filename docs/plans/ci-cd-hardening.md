# Plan — CI/CD Hardening

Status: Draft
Spec: [`../specs/ci-cd-hardening.md`](../specs/ci-cd-hardening.md)

Ordering: format first (so subsequent diffs don't pull in style changes),
then static analysis, then dependency awareness, then nightly device tests.

## CI-1 — Spotless + ktlint

**Files**
- `build.gradle.kts` (root) — add `id("com.diffplug.spotless") version "..."`
- New: `spotless.gradle.kts` (or inline block) configuring `kotlin { ktlint() }`
  and `kotlinGradle { ktlint() }`
- `gradle/libs.versions.toml` — add plugin version
- `.github/workflows/ci.yml` — add `./gradlew spotlessCheck` step
- `CONTRIBUTING.md` — document `./gradlew spotlessApply`

**Steps**
1. Add Spotless plugin to root build, applied to every subproject via
   `subprojects { apply plugin: ... }`.
2. Configure ktlint with project conventions (4-space indent, no wildcard
   imports). Mirror any existing IDE settings in `.editorconfig`.
3. Run `./gradlew spotlessApply` once; commit the formatting churn as a
   single isolated commit.
4. Add CI step.

**Validation**
- `./gradlew spotlessCheck` passes on a clean clone.
- Diff for the formatting commit contains no semantic changes.

## CI-2 — Detekt

**Files**
- `build.gradle.kts` (root) — apply `io.gitlab.arturbosch.detekt` plugin
- New: `config/detekt/detekt.yml` (start from `--generateConfig` and trim)
- New: `config/detekt/baseline.xml` (generated)
- `gradle/libs.versions.toml` — add plugin version
- `.github/workflows/ci.yml` — add `./gradlew detekt` step

**Steps**
1. Add the plugin and a `detekt {}` block configured with `buildUponDefaultConfig
   = true`, `config = files("config/detekt/detekt.yml")`,
   `baseline = file("config/detekt/baseline.xml")`.
2. Run `./gradlew detektBaseline` to generate the initial baseline.
3. Commit baseline; CI fails on **new** violations only.
4. Aggregate report task (`detektMain`) wired across modules.

**Validation**
- CI step `detekt` runs in ≤ 90 s on the runner cache.
- Introducing a deliberate `LongMethod` violation on a feature branch fails
  CI.

## CI-3 — Dependabot + CodeQL

**Files**
- New: `.github/dependabot.yml`
- New: `.github/workflows/codeql.yml`
- New: `.github/workflows/gradle-version-check.yml`

**`dependabot.yml`** entries:
```yaml
version: 2
updates:
  - package-ecosystem: gradle
    directory: "/"
    schedule: { interval: weekly }
    open-pull-requests-limit: 5
  - package-ecosystem: github-actions
    directory: "/"
    schedule: { interval: weekly }
  - package-ecosystem: npm
    directory: "/"
    schedule: { interval: weekly }
```

**`codeql.yml`** uses `github/codeql-action/init@v3` with `languages:
java-kotlin, javascript-typescript`. Runs on push to `main`, on PRs, and on a
weekly cron.

**`gradle-version-check.yml`** runs `./gradlew dependencyUpdates` (via the
`com.github.ben-manes.versions` plugin) on a weekly cron and opens an issue
with the report when any non-test dependency falls behind by ≥ 1 minor
version.

**Validation**
- Dependabot opens its first PR within 7 days.
- CodeQL results visible in the GitHub Security tab.

## CI-4 — Nightly connected tests

**Files**
- New: `.github/workflows/connected-tests.yml`

**Outline**
```yaml
name: Connected tests
on:
  schedule: [{ cron: "0 7 * * *" }]
  workflow_dispatch:
jobs:
  emulator:
    runs-on: macos-14
    timeout-minutes: 60
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 21 }
      - uses: gradle/actions/setup-gradle@v4
      - uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 34
          target: google_apis
          arch: x86_64
          script: |
            ./gradlew :app:connectedDebugAndroidTest \
                       :fixthis-compose-sidekick:connectedDebugAndroidTest
```

**Steps**
1. Land the workflow disabled-on-PR (schedule + dispatch only).
2. Triage flakes for one week; document any disabled tests in
   `docs/contributing/connected-tests.md`.
3. Promote to a required check on PRs that touch `:fixthis-compose-sidekick`
   or `:app` once green for 14 consecutive runs.

**Validation**
- Workflow runs to completion nightly.
- Failure notifications routed to the existing repo notifications channel.

## CI-5 — PR checks contract

**Files**
- `CONTRIBUTING.md` — append "Required PR checks" table from the spec.
- GitHub branch protection — enable required checks matching the table.

**Steps**
1. Open a tracking issue listing each required check; tick them off as the
   workflows above land.
2. When all required entries are green for 7 days, flip the branch protection
   rule.

**Validation**
- Test PR: failing Spotless on a draft PR blocks merge.
- Test PR: failing Detekt blocks merge.

## Rollout order

1. CI-1 (Spotless) — single reformat PR, isolated commit.
2. CI-2 (Detekt) — baseline committed, info-only for one week, then gating.
3. CI-3 (Dependabot + CodeQL + version check) — additive, no gating.
4. CI-4 (Connected tests nightly) — observation period before gating.
5. CI-5 (branch protection) — flip required-checks once stable.
