# Required PR checks — readiness tracker

This document tracks the observation windows for the workflows listed in the "Required PR checks" table in [`CONTRIBUTING.md`](../../CONTRIBUTING.md). The actual GitHub branch-protection enable is a maintainer admin action; flipping a row from advisory to required is gated on the corresponding observation window completing green.

Maintainers update this doc as observation windows complete. Once a row's "Ready to flip branch protection?" column is `yes`, the next maintainer with admin rights enables the corresponding required-status-check in the GitHub repository settings and records the flip date here.

| Workflow | First green run (date) | 7 consecutive green runs achieved? (yes/no + date) | Ready to flip branch protection? (yes/no) |
|---|---|---|---|
| `.github/workflows/ci.yml` — Build + unit tests (baseline) | pre-existing | yes (pre-existing) | already enforced |
| `./gradlew spotlessCheck` in ci.yml (CI-1) | — | pending | no |
| `./gradlew detekt` in ci.yml (CI-2) | — | pending | no |
| `.github/workflows/codeql.yml` (CI-3) | — | pending | no |
| `.github/workflows/connected-tests.yml` (CI-4, nightly) | — | pending (14 consecutive green required) | no |
| `.github/workflows/nightly-compat.yml` (BR-4, nightly) | — | pending (1 week stable required) | no |

## Notes

- "First green run" should record the first observed all-green run of the workflow on `main` after the workflow was merged.
- "7 consecutive green runs" is the default observation window for PR-time checks (CI-1, CI-2, CI-3). Replace the value with the date the streak was confirmed.
- Nightly workflows (CI-4, BR-4) have longer windows reflecting their lower cadence; do not promote them to PR-required checks without a separate discussion (they currently run on `schedule:` only).
- The CodeQL row is also gated on the first analysis successfully landing in the GitHub Security tab.
