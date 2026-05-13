# Plan — CI/CD Hardening

Status: Largely implemented; retained as a design record. Required-check
promotion still depends on the observation windows documented below.
Spec: [`../specs/ci-cd-hardening.md`](../specs/ci-cd-hardening.md)

Ordering: format first (so subsequent diffs don't pull in style changes),
then static analysis, then dependency awareness, then nightly device tests.

Note: tasks BR-4/CI-3/CI-4/CI-5 involve observation periods (one week, 14
runs, etc.) that cannot be verified inside a single change. Those gates are
explicitly deferred to follow-ups tracked in CHANGELOG and the rollout
section below — each task lands its workflow files and config, but does
NOT flip required-check / branch-protection state.

## Tasks

### Task 0: CI-1 — Spotless + ktlint

**Files:**
- `build.gradle.kts` (root) — add Spotless plugin
- `gradle/libs.versions.toml` — add plugin version alias
- `.editorconfig` (new or update — ktlint conventions)
- `.git-blame-ignore-revs` (new — record the bulk reformatting commit so `git blame` skips it)
- `.github/workflows/ci.yml` — add `./gradlew spotlessCheck` step
- `CONTRIBUTING.md` — document `./gradlew spotlessApply`
- Any number of `.kt` / `.kts` files reformatted by `./gradlew spotlessApply`

**Steps**
1. Add Spotless plugin to root build, applied to every subproject via
   `subprojects { apply plugin: ... }`.
2. Configure ktlint with project conventions (4-space indent, no wildcard
   imports). Mirror any existing IDE settings in `.editorconfig`.
3. Run `./gradlew spotlessApply` once; commit the formatting churn as a
   single isolated commit. Record that commit's SHA in `.git-blame-ignore-revs`.
4. Add CI step calling `./gradlew spotlessCheck`.
5. Document `./gradlew spotlessApply` in `CONTRIBUTING.md`.

#### Acceptance Criteria
```bash
./gradlew spotlessCheck --no-daemon
test -f .git-blame-ignore-revs
test -f .editorconfig
grep -q 'spotlessApply\|spotlessCheck' CONTRIBUTING.md
grep -q 'spotlessCheck' .github/workflows/ci.yml
```

### Task 1: CI-2 — Detekt

**Files:**
- `build.gradle.kts` (root) — apply `io.gitlab.arturbosch.detekt` plugin
- `config/detekt/detekt.yml` (new — start from `--generateConfig` and trim)
- `config/detekt/baseline.xml` (new — generated)
- `gradle/libs.versions.toml` — add plugin version
- `.github/workflows/ci.yml` — add `./gradlew detekt` step

**Steps**
1. Add the plugin and a `detekt {}` block configured with
   `buildUponDefaultConfig = true`,
   `config.setFrom(files("config/detekt/detekt.yml"))`,
   `baseline = file("config/detekt/baseline.xml")`.
2. Run `./gradlew detektBaseline` to generate the initial baseline.
3. Commit baseline; CI fails on **new** violations only.
4. Aggregate report task wired across modules.

#### Acceptance Criteria
```bash
test -f config/detekt/detekt.yml
test -f config/detekt/baseline.xml
grep -q 'detekt' .github/workflows/ci.yml
./gradlew detekt --no-daemon
```

### Task 2: CI-3 — Dependabot + CodeQL + version check

Scope note: this task lands the workflow files only. "Dependabot opens its
first PR within 7 days" and "CodeQL results in the GitHub Security tab" are
external observations that cannot be checked from inside the repo — they
are deferred to a follow-up tracked in CHANGELOG.

**Files:**
- `.github/dependabot.yml` (new)
- `.github/workflows/codeql.yml` (new)
- `.github/workflows/gradle-version-check.yml` (new)
- `CHANGELOG.md` (note: workflows shipped, external verification is a follow-up)

**Steps**
1. Add `.github/dependabot.yml` with gradle, github-actions, npm ecosystems.
2. Add `.github/workflows/codeql.yml` using
   `github/codeql-action/init@v3` with languages `java-kotlin, javascript-typescript`.
   Trigger on push to `main`, on PRs, and on a weekly cron.
3. Add `.github/workflows/gradle-version-check.yml` running
   `./gradlew dependencyUpdates` (via `com.github.ben-manes.versions`) on a
   weekly cron and opening an issue with the report when any non-test
   dependency falls behind by ≥ 1 minor version.
4. Note in `CHANGELOG.md` that observation of Dependabot PRs and CodeQL
   results is a follow-up.

#### Acceptance Criteria
```bash
test -f .github/dependabot.yml
test -f .github/workflows/codeql.yml
test -f .github/workflows/gradle-version-check.yml
# Workflows must parse as valid YAML
python3 -c "import yaml; yaml.safe_load(open('.github/dependabot.yml'))"
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/codeql.yml'))"
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/gradle-version-check.yml'))"
grep -qE 'dependabot|codeql|version[ -]check' CHANGELOG.md
```

### Task 3: CI-4 — Nightly connected tests

Scope note: this task lands the workflow file disabled-on-PR (schedule +
dispatch only). The 1-week flake triage and 14-consecutive-runs promotion
gate are explicitly deferred — they require observation over wall-clock
time that no single change can fulfil.

**Files:**
- `.github/workflows/connected-tests.yml` (new)
- `docs/contributing/connected-tests.md` (new — flake triage process, empty
  table of disabled tests to fill in as observations arrive)
- `CHANGELOG.md` (note: required-check promotion is a follow-up)

**Steps**
1. Land the workflow disabled-on-PR (schedule + dispatch only).
2. Create `docs/contributing/connected-tests.md` documenting the flake
   triage process and the (initially empty) list of disabled tests.
3. Add CHANGELOG entry noting promotion to required check is a follow-up.

#### Acceptance Criteria
```bash
test -f .github/workflows/connected-tests.yml
test -f docs/contributing/connected-tests.md
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/connected-tests.yml'))"
# Must not run on pull_request — schedule + workflow_dispatch only
! grep -qE '^[[:space:]]*pull_request' .github/workflows/connected-tests.yml
grep -q 'schedule' .github/workflows/connected-tests.yml
grep -q 'workflow_dispatch' .github/workflows/connected-tests.yml
grep -qE 'connected[ -]tests|nightly' CHANGELOG.md
```

### Task 4: CI-5 — PR checks contract

Scope note: this task documents the required-check table in
`CONTRIBUTING.md` and opens a tracking checklist. The actual branch
protection flip (which requires GitHub repo admin permissions and 7 days of
stable green checks) is deferred to a follow-up.

**Files:**
- `CONTRIBUTING.md` (append "Required PR checks" table from the spec)
- `docs/contributing/required-checks.md` (new — tracking table with one row
  per required workflow and a column for "green for 7 days?")
- `CHANGELOG.md` (note: branch protection flip is a follow-up)

**Steps**
1. Append a "Required PR checks" table to `CONTRIBUTING.md` based on the
   spec.
2. Create `docs/contributing/required-checks.md` with one row per workflow
   from CI-1 / CI-2 / CI-3 / CI-4, each row tracking "first green run", "7
   consecutive green runs", "ready to flip branch protection".
3. Note in CHANGELOG that the branch-protection flip is a follow-up.

#### Acceptance Criteria
```bash
grep -qE 'Required PR checks|required-check' CONTRIBUTING.md
test -f docs/contributing/required-checks.md
grep -qE 'branch protection|required[ -]check' CHANGELOG.md
```

## Rollout order

1. Task 0: CI-1 (Spotless) — single reformat PR, isolated commit, blame-ignore-revs.
2. Task 1: CI-2 (Detekt) — baseline committed, info-only.
3. Task 2: CI-3 (Dependabot + CodeQL + version check) — additive workflow files.
4. Task 3: CI-4 (Connected tests nightly) — workflow landed disabled-on-PR.
5. Task 4: CI-5 (required-checks doc) — informational; branch-protection flip deferred.
