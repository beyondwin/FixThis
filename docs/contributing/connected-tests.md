# Connected (instrumented) tests

FixThis runs Android instrumented tests (`connectedDebugAndroidTest`) on a
nightly schedule rather than per-PR. This page explains why, how to triage
flakes, and the current list of temporarily disabled tests.

## Why nightly-only?

Connected tests require an emulator (or a physical device) and historically
exhibit two failure modes that are unsuitable for a per-PR signal:

1. **Cost** — booting an emulator and running the full instrumented suite
   takes ~15-30 minutes on a CI runner. Multiplied across every PR push, the
   wall-clock and minute budget impact is large.
2. **Flake risk** — emulator boots, ADB handshakes, Compose hierarchy
   discovery on locked or partially-initialised devices, and timing-sensitive
   animations all introduce intermittent failures that are unrelated to the
   change under review.

The nightly workflow (`.github/workflows/connected-tests.yml`) runs at 04:00
UTC and on manual `workflow_dispatch`. It does **not** trigger on
`pull_request`. The test step is marked `continue-on-error: true` so a red
run does not block other workflows — failure is informational today.

Promotion to a **required PR check** is gated on **14 consecutive green
nightly runs** plus the flake-triage process below stabilising the suite.
That promotion is tracked as a follow-up in `CHANGELOG.md`.

Before promoting connected tests, run:

```bash
npm run checks:observation -- --require-ready connected-tests
```

Do not remove `continue-on-error` or add PR branch protection until this command
passes and the latest failing/flaky artifacts have been triaged.

## Flake triage process

When a connected test fails:

1. **First failure** — note it. CI artefact uploads (`connected-test-reports`)
   preserve the HTML report and `androidTest-results/` XML. Do not act yet;
   single failures may be transient runner noise.
2. **Second failure within a week** — the test is now considered flaky.
   - Open an issue describing the symptom and link the two failing nightly
     runs.
   - Add a row to the **Disabled tests** table below: test FQN, the date you
     disabled it, an owner, and the condition under which it can be
     re-enabled (typically "underlying flake fixed and root cause
     documented in the linked issue").
   - Disable the test in source via `@Ignore("flaky — see #<issue>")` or
     equivalent so it stops contaminating the signal.
3. **Investigate within one sprint** — the owner of the disabled row drives
   the root-cause investigation.
4. **Re-enable** — once the underlying flake is fixed (e.g. test rewritten
   to be hermetic, runner config adjusted, Compose synchronisation added),
   remove the `@Ignore`, delete the row from the table below, and link the
   fix commit in the closing comment of the tracking issue.

## Disabled tests

| Test | Disabled since | Owner | Re-enable when |
| ---- | -------------- | ----- | -------------- |

_(Empty — no tests are currently disabled for flake. Add rows as flakes are
observed via the process above.)_
