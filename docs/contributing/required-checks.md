# Required PR checks — readiness tracker

This document tracks the observation windows for the workflows listed in the "Required PR checks" table in [`CONTRIBUTING.md`](../../CONTRIBUTING.md). The actual GitHub branch-protection enable is a maintainer admin action; flipping a row from advisory to required is gated on the corresponding observation window completing green.

Maintainers update this doc as observation windows complete. Once a row's "Ready to flip branch protection?" column is `yes`, the next maintainer with admin rights enables the corresponding required-status-check in the GitHub repository settings and records the flip date here.

| Workflow | First green run (date) | 7 consecutive green runs achieved? (yes/no + date) | Ready to flip branch protection? (yes/no) |
|---|---|---|---|
| `.github/workflows/ci.yml` — Build + unit tests (baseline) | pre-existing | yes (pre-existing) | already enforced |
| `./gradlew spotlessCheck` in ci.yml (CI-1) | — | pending | no |
| `./gradlew detekt` in ci.yml (CI-2) | — | pending | no |
| `node scripts/build-console-assets.mjs --check` in ci.yml | — | pending | no |
| `npm run console:test:all` in ci.yml | — | pending | no |
| `.github/workflows/codeql.yml` (CI-3) | — | pending | no |
| `.github/workflows/connected-tests.yml` (CI-4, nightly) | — | pending (14 consecutive green required) | no |
| `.github/workflows/nightly-compat.yml` (BR-4, weekly scheduled) | — | pending (1 week stable required) | no |

## How to Update This Table

Run:

```bash
npm run checks:observation
```

The command uses `gh run list`, so it requires GitHub CLI authentication and
repository access. Copy the computed first-green and consecutive-green counts
into the table. Do not promote scheduled workflows to PR-required checks until
their longer observation windows are complete.

## Notes

- "First green run" should record the first observed all-green run of the workflow on `main` after the workflow was merged.
- "7 consecutive green runs" is the default observation window for PR-time checks
  (CI-1, CI-2, console asset check, console JS harnesses, CI-3). Replace the
  value with the date the streak was confirmed.
- Scheduled device and compatibility workflows (CI-4, BR-4) have longer windows reflecting their lower cadence; do not promote them to PR-required checks without a separate discussion (they currently run on `schedule:` only).
- The CodeQL row is also gated on the first analysis successfully landing in the GitHub Security tab.
- Gradle 9.3.1 reports a Gradle 10 deprecation from Detekt 1.23.7 during
  `./gradlew :fixthis-mcp:detekt --warning-mode all --stacktrace --no-daemon`:
  `io.gitlab.arturbosch.detekt.DetektPlugin.apply` calls the deprecated
  `ReportingExtension.file(String)`. Owner: build maintainers; upgrade path:
  re-check on the next Detekt version before Gradle 10 adoption.
- Detekt baseline files are ratcheted by
  `npm run detekt:baseline:check`. Reducing a baseline count is allowed and
  should be followed by lowering `config/detekt/baseline-budget.json`.
  Increasing a budget requires a code-review explanation.
