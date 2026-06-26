# Connected Android Proof Runner Design

Date: 2026-06-26

## Goal

Turn connected Android verification from a manual checklist into one repeatable
local proof command.

Maintainers should be able to run a single Android proof runner before release
decisions and get a structured answer: Android is ready, the sample smoke works,
real Copy Prompt works, Save to MCP can be read and resolved through MCP, and
external fixture setup still passes. When the environment is not ready, the
report should say exactly whether the blocker is SDK, ADB, device state, boot
state, or a specific smoke failure.

## Current Context

FixThis already has the pieces:

- `scripts/fixthis-smoke.sh` builds host artifacts, diagnoses the sample package,
  and writes local smoke reports.
- `npm run real-copy-prompt:smoke -- --strict` proves the browser Copy Prompt
  path on installed debug apps.
- `npm run agent-loop:smoke -- --strict` proves Save to MCP through MCP
  read, claim, resolve, persistence, and console reflection.
- `npm run external-fixture:matrix -- --strict` proves external fixture setup and
  verify behavior against generated Android project shapes.
- `scripts/evidence-runner.mjs` and `scripts/release-gate.mjs` already aggregate
  many evidence steps, but Android connected proof is spread across several
  commands.

The remaining gap is not another low-level smoke. It is a reliable connected
Android proof orchestrator that runs the existing strict checks in one place,
normalizes their outcomes, and makes environment failures distinct from product
regressions.

## Scope

In scope:

- add a new `android:proof` command that orchestrates existing connected Android
  proof commands;
- add a stable JSON and Markdown report under
  `build/reports/fixthis-android-proof/`;
- classify Android environment blockers before running expensive smoke steps;
- support strict and non-strict modes with the same deferral semantics as other
  evidence tools;
- wire the new proof into release evidence so connected Android readiness is a
  first-class claim;
- update contributor and release-readiness docs to stop describing connected
  verification as purely manual.

Out of scope:

- auto-creating, booting, or managing emulators;
- controlling Android Studio or AVD Manager;
- changing browser console behavior;
- changing MCP queue semantics;
- changing source-matching, runtime evidence, or persisted feedback JSON fields;
- committing `.fixthis/`, raw screenshots, or local runtime artifacts.

## Runner Contract

The new entry point is:

```bash
npm run android:proof -- --strict
```

The implementation lives in `scripts/android-proof-runner.mjs`. It accepts:

- `--strict`: Android environment blockers fail instead of deferring.
- `--continue`: continue after failed smoke steps to collect more evidence.
- `--report-dir <path>`: write reports outside the default report directory.
- `--device <serial>`: select an ADB serial when multiple devices are connected.
- `--skip-build`: skip build/installDist work when the caller has already built
  the required local distributions.
- `--headed`: pass headed browser mode to browser-driven smoke commands.

The runner is an orchestrator. It must not duplicate the browser, MCP, source
matching, or external fixture logic that existing commands own. It should call
those commands and normalize their results.

## Android Preflight

The runner starts with an Android environment preflight before launching any
smoke.

Preflight checks:

1. Resolve Android SDK and ADB using existing lookup behavior from
   `resolveAndroidEnvironment`.
2. Record `adb devices -l`.
3. Select a serial:
   - use `--device` when provided;
   - use `ANDROID_SERIAL` when set;
   - use the only ready device when exactly one exists;
   - fail or defer with `multiple_devices` when several ready devices exist.
4. Classify device states:
   - no device rows: `device_missing`;
   - `unauthorized`: `device_unauthorized`;
   - `offline`: `device_offline`;
   - ready device but `sys.boot_completed != 1`: `boot_incomplete`.
5. Emit an environment summary with SDK path, ADB path, selected serial, raw
   device output, boot status, status, failure code, and next action.

Strict mode fails environment blockers. Non-strict mode writes a deferred report
and exits zero, matching the existing evidence-runner posture for unavailable
Android runtime prerequisites.

## Proof Steps

When preflight passes, the runner executes these steps in order:

1. Sample smoke:

   ```bash
   scripts/fixthis-smoke.sh --package io.github.beyondwin.fixthis.sample --device <serial>
   ```

2. Real Copy Prompt smoke:

   ```bash
   npm run real-copy-prompt:smoke -- --strict
   ```

3. Agent loop smoke:

   ```bash
   npm run agent-loop:smoke -- --strict
   ```

4. External fixture matrix:

   ```bash
   npm run external-fixture:matrix -- --strict
   ```

Each step records:

- `name`;
- `command`;
- `status`: `pass`, `fail`, or `deferred`;
- `exitCode`;
- `durationMs`;
- `failureCode`;
- `reason`;
- `reportPath` when the child command writes one;
- `stdoutPath` and `stderrPath` when the runner captures logs.

Without `--continue`, the runner stops after the first failed step and writes a
complete report. With `--continue`, it runs all remaining steps and reports every
failure it observed.

## Failure Classification

The report uses stable failure codes so humans, release-gate code, and future
agents can handle failures without scraping prose.

Environment failure codes:

- `android_sdk_missing`: Android SDK or ADB could not be resolved.
- `device_missing`: no connected ready device or emulator is available.
- `device_unauthorized`: USB debugging authorization is required.
- `device_offline`: ADB reports a device as offline.
- `boot_incomplete`: a ready device exists, but Android has not completed boot.
- `multiple_devices`: more than one ready device exists and no serial was
  selected.

Smoke failure codes:

- `sample_smoke_failed`: the sample package smoke failed.
- `copy_prompt_failed`: real browser Copy Prompt smoke failed.
- `agent_loop_failed`: Save to MCP through read, claim, resolve, persistence, or
  console reflection failed.
- `external_fixture_failed`: strict external fixture matrix failed.
- `unknown_failure`: the runner could not classify the failed step.

Each failure includes a `nextAction`. Examples:

- `android_sdk_missing`: install Android SDK platform-tools or fix
  `ANDROID_HOME`.
- `device_unauthorized`: unlock the device and approve USB debugging.
- `device_offline`: restart ADB with `adb kill-server && adb start-server`, then
  rerun the proof.
- `boot_incomplete`: wait for boot completion, then rerun the proof.
- `multiple_devices`: rerun with `--device <serial>`.
- smoke failures: inspect the child report path and rerun the printed command.

## Report Schema

The JSON report is the stable machine-readable surface:

```json
{
  "schemaVersion": "1.0",
  "status": "pass",
  "strict": true,
  "generatedAt": "2026-06-26T00:00:00.000Z",
  "environment": {
    "status": "pass",
    "sdk": "/path/to/android/sdk",
    "adb": "/path/to/adb",
    "deviceSerial": "emulator-5554",
    "bootCompleted": true,
    "failureCode": null,
    "nextAction": null
  },
  "steps": [
    {
      "name": "Agent loop smoke",
      "command": "npm run agent-loop:smoke -- --strict",
      "status": "pass",
      "exitCode": 0,
      "durationMs": 1000,
      "failureCode": null,
      "reason": null,
      "reportPath": "build/reports/fixthis-agent-loop/report.json"
    }
  ],
  "failures": []
}
```

Overall status rules:

- `fail` if any environment or smoke step fails;
- `deferred` when Android is unavailable in non-strict mode;
- `pass_with_deferred` only if a future optional step is explicitly marked
  deferrable after environment preflight passes;
- `pass` when environment and all required steps pass.

The Markdown report is for release issues and local debugging. It should render
environment first, step results second, and next actions third.

## Release Gate Integration

Add a release claim named `connected-android-proof` to `release-gate.mjs`.

The claim is backed by the Android proof runner, not by repeating each child
smoke command in claim logic. Release-gate can still keep existing child smoke
evidence during the transition, but the new claim should answer this product
question directly:

> Can a connected Android runtime complete the local sample, Copy Prompt, MCP
> handoff lifecycle, and external setup evidence from one proof command?

Strict release-gate runs must fail if `android:proof -- --strict` fails or
defers. Non-strict evidence reports may defer Android runtime prerequisites with
the exact blocker and next action.

## Documentation

Update contributor and release docs:

- `CONTRIBUTING.md` should replace the statement that connected-device
  verification is manual with `npm run android:proof -- --strict` as the primary
  connected proof command.
- `docs/contributing/release-readiness.md` should add the connected Android
  proof claim and required evidence.
- `package.json` should expose:
  - `android:proof`;
  - `android:proof:test`.

The docs should keep the existing individual smoke commands available as
focused debugging commands.

## Evidence And Tests

Add `scripts/android-proof-runner-test.mjs` with focused unit coverage:

- Android device-output parsing;
- serial selection;
- boot-completion parsing;
- strict versus non-strict environment status;
- failure-code and next-action mapping;
- step normalization;
- `--continue` versus stop-on-first-failure behavior;
- JSON and Markdown report rendering;
- package script presence.

Extend release evidence tests:

- `release-gate-test.mjs` covers the new `connected-android-proof` claim.
- `evidence-runner-test.mjs` covers the Android proof step if it becomes part of
  an evidence profile.

Connected runtime verification remains local-only:

```bash
npm run android:proof -- --strict
```

If Android SDK or a ready unlocked device is unavailable, non-strict reports
must record the exact deferred reason instead of implying success.

## Acceptance Criteria

- `npm run android:proof -- --strict` exists and writes JSON and Markdown reports.
- Android environment failures are classified before expensive smoke commands
  run.
- Strict mode fails missing SDK, missing device, unauthorized device, offline
  device, boot-incomplete device, multiple-device ambiguity, and smoke failures.
- Non-strict mode defers unavailable Android runtime prerequisites with an exact
  failure code and next action.
- Existing smoke commands remain the owners of their behavior; the new runner
  only orchestrates and normalizes.
- Release-gate reports include a connected Android proof claim.
- Contributor and release-readiness docs point maintainers to the new command.
- Tests cover parser, classification, report, CLI-option, and release-claim
  behavior without needing a real device.
