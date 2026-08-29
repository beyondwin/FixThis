# Connected (instrumented) tests

FixThis runs `connectedDebugAndroidTest` nightly, not per PR.

## Why nightly-only?

1. **Cost** — emulator boot plus the instrumented suite is 15–30 minutes.
2. **Flake risk** — emulator boots, ADB, lockscreens, and animation timing
   fail for reasons unrelated to the change.

Workflow: `.github/workflows/connected-tests.yml` at 04:00 UTC and on
`workflow_dispatch`. Not on `pull_request`. The test step is
`continue-on-error: true`.

Promotion to a required PR check needs **14 consecutive green nightly runs**
plus flake triage below.

```bash
npm run checks:observation -- --require-ready connected-tests
```

Do not remove `continue-on-error` or add PR branch protection until that
passes and the latest flakes are triaged.

## Flake triage process

1. **First failure** — note it. Do not act yet.
2. **Second failure within a week** — treat as flaky. Open an issue, add a
   row to **Disabled tests**, disable with `@Ignore("flaky — see #<issue>")`.
3. **Investigate within one sprint.**
4. **Re-enable** after the flake is fixed. Remove `@Ignore` and the table row.

## Disabled tests

| Test | Disabled since | Owner | Re-enable when |
| ---- | -------------- | ----- | -------------- |

_(Empty — no tests are currently disabled for flake.)_
