# Required Checks Observation Snapshot

Last refreshed: 2026-06-02

Refresh command:

```bash
npm run checks:observation -- --json
```

This snapshot is a maintainer aid. GitHub Actions and
`docs/contributing/required-checks.md` remain the source of truth for branch
protection.

| Workflow | Consecutive green | Required | Ready | Latest run |
| --- | ---: | ---: | --- | --- |
| `.github/workflows/ci.yml` | 0 | 7 | no | refresh pending |
| `.github/workflows/codeql.yml` | 0 | 7 | no | refresh pending |
| `.github/workflows/connected-tests.yml` | 0 | 14 | no | refresh pending |
| `.github/workflows/nightly-compat.yml` | 0 | 1 | no | refresh pending |

Next action: run the refresh command with `gh` authenticated, then copy the
observed streaks into this table and update `docs/contributing/required-checks.md`
only for workflows that satisfy their observation window.
