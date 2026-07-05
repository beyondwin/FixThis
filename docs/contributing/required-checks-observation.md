# Required Checks Observation Snapshot

Last refreshed: 2026-07-05

Refresh command:

```bash
npm run checks:observation -- --json
```

This snapshot is a maintainer aid. GitHub Actions and
`docs/contributing/required-checks.md` remain the source of truth for branch
protection. The script observes workflow-level success streaks; it does not
prove that every individual job or sub-check inside a workflow has a separate
branch-protection status name.

| Workflow | First green in sample | Consecutive green | Required | Ready | Latest run |
| --- | --- | ---: | ---: | --- | --- |
| `.github/workflows/ci.yml` | 2026-05-19T04:01:48Z | 12 | 7 | yes | https://github.com/beyondwin/FixThis/actions/runs/28699201895 |
| `.github/workflows/codeql.yml` | 2026-05-25T09:53:31Z | 17 | 7 | yes | https://github.com/beyondwin/FixThis/actions/runs/28699201898 |
| `.github/workflows/connected-tests.yml` | 2026-06-15T09:52:57Z | 20 | 14 | yes | https://github.com/beyondwin/FixThis/actions/runs/28698007108 |
| `.github/workflows/nightly-compat.yml` | 2026-05-12T06:08:10Z | 8 | 1 | yes | https://github.com/beyondwin/FixThis/actions/runs/28425957564 |

Next action: update `docs/contributing/required-checks.md` only where the
workflow-level observation is enough for the branch-protection decision. For
sub-checks inside `ci.yml`, confirm the exact GitHub status-check names before
marking individual rows ready to require.
