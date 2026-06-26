# Connected Android Proof Runner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build one repeatable `npm run android:proof -- --strict` command that classifies connected Android readiness and orchestrates the existing sample, Copy Prompt, agent-loop, and external fixture strict smokes.

**Architecture:** Add a focused Node.js runner in `scripts/android-proof-runner.mjs` with pure exported helpers for argument parsing, ADB output parsing, environment classification, step normalization, report rendering, and injected command execution. Keep existing smoke scripts as the owners of browser, MCP, source-matching, and external-fixture behavior. Wire the runner into package scripts, evidence profiles, release-gate claims, and docs without changing MCP queue or feedback JSON contracts.

**Tech Stack:** Node.js 20 ESM, `node:test`, `node:assert/strict`, `spawnSync`, existing npm evidence scripts, Markdown documentation, existing release-gate report schema.

## Global Constraints

- FixThis remains debug-only and Jetpack Compose-only.
- Do not auto-create, boot, or manage emulators.
- Do not control Android Studio or AVD Manager.
- Do not change browser console behavior.
- Do not change MCP queue semantics.
- Do not change source-matching, runtime evidence, or persisted feedback JSON fields.
- Do not commit `.fixthis/`, raw screenshots, or local runtime artifacts.
- The new runner orchestrates existing smoke commands; it does not duplicate browser, MCP, source matching, or external fixture logic.
- Strict mode fails missing SDK, missing device, unauthorized device, offline device, boot-incomplete device, multiple-device ambiguity, and smoke failures.
- Non-strict mode defers unavailable Android runtime prerequisites with an exact failure code and next action.
- Reports are written under `build/reports/fixthis-android-proof/` by default.

---

## File Structure

- Create `scripts/android-proof-runner.mjs`
  - Owns the connected Android proof runner.
  - Exports pure helper functions for tests.
  - Provides CLI `main()` when invoked directly.
- Create `scripts/android-proof-runner-test.mjs`
  - Tests parsing, classification, report rendering, step orchestration, package scripts, and injected command behavior without requiring a real device.
- Modify `package.json`
  - Adds `android:proof` and `android:proof:test`.
- Modify `scripts/evidence-runner.mjs`
  - Adds a gate-profile step named `Connected Android proof`.
- Modify `scripts/evidence-runner-test.mjs`
  - Asserts the new package scripts and evidence profile step.
- Modify `scripts/release-gate.mjs`
  - Adds release claim `connected-android-proof` mapped to `Connected Android proof`.
- Modify `scripts/release-gate-test.mjs`
  - Asserts the release claim and gate step mapping.
- Modify `CONTRIBUTING.md`
  - Makes `npm run android:proof -- --strict` the primary connected-device command and keeps individual smokes as focused debugging commands.
- Modify `docs/contributing/release-readiness.md`
  - Adds connected Android proof evidence.
- Modify `scripts/check-release-readiness.mjs`
  - Adds guards that require the new release-readiness and contributing documentation.

---

### Task 1: Pin The Runner Contract With Failing Tests And Package Scripts

**Files:**
- Create: `scripts/android-proof-runner-test.mjs`
- Modify: `package.json`

**Interfaces:**
- Consumes: no new project code.
- Produces:
  - `parseArgs(argv: string[]): AndroidProofOptions`
  - `parseAdbDevices(stdout: string): AdbDeviceRow[]`
  - `selectDevice(rows: AdbDeviceRow[], options: { device?: string | null, envSerial?: string | null }): DeviceSelection`
  - `classifyAndroidEnvironment(input: AndroidEnvironmentInput): AndroidEnvironmentSummary`
  - `failureForCode(code: string): { failureCode: string, nextAction: string }`
  - `buildReport(input: AndroidProofReportInput): AndroidProofReport`
  - `renderMarkdownReport(report: AndroidProofReport): string`
  - `buildProofSteps(options: AndroidProofOptions, environment: AndroidEnvironmentSummary): ProofStep[]`

- [ ] **Step 1: Create failing runner contract tests**

Create `scripts/android-proof-runner-test.mjs` with this content:

```js
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import {
  buildProofSteps,
  buildReport,
  classifyAndroidEnvironment,
  failureForCode,
  parseAdbDevices,
  parseArgs,
  renderMarkdownReport,
  selectDevice,
} from "./android-proof-runner.mjs";

test("parseArgs supports strict continue report device skip-build and headed", () => {
  assert.deepEqual(parseArgs([
    "--strict",
    "--continue",
    "--report-dir",
    "out/android-proof",
    "--device",
    "emulator-5554",
    "--skip-build",
    "--headed",
  ]), {
    strict: true,
    continueOnFailure: true,
    reportDir: "out/android-proof",
    device: "emulator-5554",
    skipBuild: true,
    headed: true,
  });
});

test("parseArgs rejects unknown and missing-value flags", () => {
  assert.throws(() => parseArgs(["--device"]), /--device requires a value/);
  assert.throws(() => parseArgs(["--report-dir"]), /--report-dir requires a value/);
  assert.throws(() => parseArgs(["--unknown"]), /Unknown argument: --unknown/);
});

test("parseAdbDevices returns serial state and metadata", () => {
  const rows = parseAdbDevices([
    "List of devices attached",
    "emulator-5554\tdevice product:sdk_gphone64 model:Pixel_8 device:emu64a transport_id:1",
    "phone-1\tunauthorized usb:338690048X",
    "192.168.1.5:5555\toffline",
    "",
  ].join("\n"));

  assert.deepEqual(rows, [
    {
      serial: "emulator-5554",
      state: "device",
      metadata: "product:sdk_gphone64 model:Pixel_8 device:emu64a transport_id:1",
    },
    {
      serial: "phone-1",
      state: "unauthorized",
      metadata: "usb:338690048X",
    },
    {
      serial: "192.168.1.5:5555",
      state: "offline",
      metadata: "",
    },
  ]);
});

test("selectDevice uses explicit serial env serial single ready device and detects ambiguity", () => {
  const rows = [
    { serial: "emulator-5554", state: "device", metadata: "" },
    { serial: "phone-1", state: "device", metadata: "" },
    { serial: "phone-2", state: "offline", metadata: "" },
  ];

  assert.deepEqual(selectDevice(rows, { device: "phone-1" }), {
    status: "pass",
    serial: "phone-1",
    failureCode: null,
    reason: null,
  });
  assert.deepEqual(selectDevice(rows, { envSerial: "emulator-5554" }), {
    status: "pass",
    serial: "emulator-5554",
    failureCode: null,
    reason: null,
  });
  assert.deepEqual(selectDevice(rows.slice(0, 1), {}), {
    status: "pass",
    serial: "emulator-5554",
    failureCode: null,
    reason: null,
  });
  assert.deepEqual(selectDevice(rows, {}), {
    status: "fail",
    serial: null,
    failureCode: "multiple_devices",
    reason: "Multiple ready Android devices are connected. Rerun with --device <serial>.",
  });
});

test("selectDevice classifies missing unauthorized offline and unknown explicit serial", () => {
  assert.equal(selectDevice([], {}).failureCode, "device_missing");
  assert.equal(selectDevice([{ serial: "phone-1", state: "unauthorized", metadata: "" }], {}).failureCode, "device_unauthorized");
  assert.equal(selectDevice([{ serial: "phone-1", state: "offline", metadata: "" }], {}).failureCode, "device_offline");
  assert.deepEqual(selectDevice([{ serial: "phone-1", state: "device", metadata: "" }], { device: "missing" }), {
    status: "fail",
    serial: "missing",
    failureCode: "device_missing",
    reason: "Selected Android device missing was not found in adb devices -l.",
  });
});

test("classifyAndroidEnvironment handles sdk missing boot incomplete strict and deferred modes", () => {
  const sdkMissing = classifyAndroidEnvironment({
    strict: false,
    sdk: null,
    adb: null,
    adbDevicesStdout: "",
  });
  assert.equal(sdkMissing.status, "deferred");
  assert.equal(sdkMissing.failureCode, "android_sdk_missing");
  assert.equal(sdkMissing.nextAction, "Install Android SDK platform-tools or fix ANDROID_HOME.");

  const strictMissing = classifyAndroidEnvironment({
    strict: true,
    sdk: null,
    adb: null,
    adbDevicesStdout: "",
  });
  assert.equal(strictMissing.status, "fail");

  const bootIncomplete = classifyAndroidEnvironment({
    strict: true,
    sdk: "/sdk",
    adb: "/sdk/platform-tools/adb",
    adbDevicesStdout: "List of devices attached\nemulator-5554\tdevice\n",
    bootCompletedStdout: "0\n",
  });
  assert.equal(bootIncomplete.status, "fail");
  assert.equal(bootIncomplete.failureCode, "boot_incomplete");
  assert.equal(bootIncomplete.deviceSerial, "emulator-5554");
});

test("classifyAndroidEnvironment passes ready boot-completed device", () => {
  const environment = classifyAndroidEnvironment({
    strict: true,
    sdk: "/sdk",
    adb: "/sdk/platform-tools/adb",
    adbDevicesStdout: "List of devices attached\nemulator-5554\tdevice model:Pixel_8\n",
    bootCompletedStdout: "1\n",
  });

  assert.equal(environment.status, "pass");
  assert.equal(environment.failureCode, null);
  assert.equal(environment.nextAction, null);
  assert.equal(environment.deviceSerial, "emulator-5554");
  assert.equal(environment.bootCompleted, true);
});

test("failureForCode returns stable next actions", () => {
  assert.equal(failureForCode("device_offline").nextAction, "Run `adb kill-server && adb start-server`, confirm `adb devices -l`, then rerun `npm run android:proof -- --strict`.");
  assert.equal(failureForCode("multiple_devices").nextAction, "Rerun with `npm run android:proof -- --strict --device <serial>`.");
  assert.equal(failureForCode("copy_prompt_failed").nextAction, "Inspect the real Copy Prompt smoke report and rerun `npm run real-copy-prompt:smoke -- --strict`.");
});

test("buildProofSteps creates ordered smoke commands and forwards flags", () => {
  const steps = buildProofSteps({
    strict: true,
    continueOnFailure: false,
    reportDir: "build/reports/fixthis-android-proof",
    device: "emulator-5554",
    skipBuild: true,
    headed: true,
  }, {
    status: "pass",
    deviceSerial: "emulator-5554",
  });

  assert.deepEqual(steps.map((step) => step.name), [
    "Sample smoke",
    "Real Copy Prompt smoke",
    "Agent loop smoke",
    "External fixture matrix",
  ]);
  assert.equal(steps[0].command, "scripts/fixthis-smoke.sh --package io.github.beyondwin.fixthis.sample --device emulator-5554 --no-build");
  assert.equal(steps[1].command, "npm run real-copy-prompt:smoke -- --strict --headed");
  assert.equal(steps[2].command, "npm run agent-loop:smoke -- --strict --headed");
  assert.equal(steps[3].command, "npm run external-fixture:matrix -- --strict");
});

test("buildReport derives fail deferred and pass statuses", () => {
  assert.equal(buildReport({
    strict: true,
    environment: { status: "fail", failureCode: "device_missing", nextAction: "Start an emulator." },
    steps: [],
    generatedAt: "2026-06-26T00:00:00.000Z",
  }).status, "fail");

  assert.equal(buildReport({
    strict: false,
    environment: { status: "deferred", failureCode: "device_missing", nextAction: "Start an emulator." },
    steps: [],
    generatedAt: "2026-06-26T00:00:00.000Z",
  }).status, "deferred");

  assert.equal(buildReport({
    strict: true,
    environment: { status: "pass", failureCode: null, nextAction: null },
    steps: [
      { name: "Sample smoke", command: "true", status: "pass", exitCode: 0, durationMs: 1, failureCode: null, reason: null },
      { name: "Agent loop smoke", command: "false", status: "fail", exitCode: 1, durationMs: 1, failureCode: "agent_loop_failed", reason: "failed" },
    ],
    generatedAt: "2026-06-26T00:00:00.000Z",
  }).status, "fail");
});

test("renderMarkdownReport shows environment failures steps and next actions", () => {
  const report = buildReport({
    strict: true,
    environment: {
      status: "fail",
      sdk: "/sdk",
      adb: "/sdk/platform-tools/adb",
      deviceSerial: null,
      bootCompleted: false,
      failureCode: "device_missing",
      nextAction: "Start an emulator.",
    },
    steps: [],
    generatedAt: "2026-06-26T00:00:00.000Z",
  });

  const text = renderMarkdownReport(report);
  assert.match(text, /# FixThis Connected Android Proof/);
  assert.match(text, /- Status: fail/);
  assert.match(text, /- Failure code: device_missing/);
  assert.match(text, /- Next action: Start an emulator\./);
});

test("package exposes android proof commands", () => {
  const pkg = JSON.parse(readFileSync("package.json", "utf8"));
  assert.equal(pkg.scripts["android:proof"], "node scripts/android-proof-runner.mjs");
  assert.equal(pkg.scripts["android:proof:test"], "node --test scripts/android-proof-runner-test.mjs");
});
```

- [ ] **Step 2: Run the new test to verify the runner module is missing**

Run:

```bash
node --test scripts/android-proof-runner-test.mjs
```

Expected: FAIL with an import error containing:

```text
Cannot find module
```

- [ ] **Step 3: Add package scripts only**

Modify `package.json` inside the top-level `scripts` object. Add these two keys near the other evidence and smoke scripts:

```json
"android:proof": "node scripts/android-proof-runner.mjs",
"android:proof:test": "node --test scripts/android-proof-runner-test.mjs",
```

Keep existing script values unchanged.

- [ ] **Step 4: Run the new test again to confirm only the runner module is still missing**

Run:

```bash
node --test scripts/android-proof-runner-test.mjs
```

Expected: FAIL with an import error containing:

```text
Cannot find module
```

- [ ] **Step 5: Commit the contract tests and package scripts**

Run:

```bash
git add package.json scripts/android-proof-runner-test.mjs
git commit -m "test(android): pin connected proof runner contract"
```

Expected: commit succeeds.

---

### Task 2: Implement Pure Android Proof Parsing Classification And Rendering

**Files:**
- Create: `scripts/android-proof-runner.mjs`
- Test: `scripts/android-proof-runner-test.mjs`

**Interfaces:**
- Consumes:
  - `resolveAndroidEnvironment` is not consumed in this task.
- Produces:
  - `parseArgs(argv: string[]): AndroidProofOptions`
  - `parseAdbDevices(stdout: string): AdbDeviceRow[]`
  - `selectDevice(rows: AdbDeviceRow[], options: { device?: string | null, envSerial?: string | null }): DeviceSelection`
  - `failureForCode(code: string): { failureCode: string, nextAction: string, reason: string }`
  - `classifyAndroidEnvironment(input: AndroidEnvironmentInput): AndroidEnvironmentSummary`
  - `buildProofSteps(options: AndroidProofOptions, environment: AndroidEnvironmentSummary): ProofStep[]`
  - `buildReport(input: AndroidProofReportInput): AndroidProofReport`
  - `renderMarkdownReport(report: AndroidProofReport): string`

- [ ] **Step 1: Create the runner module with pure helpers**

Create `scripts/android-proof-runner.mjs` with this content:

```js
#!/usr/bin/env node
import { mkdirSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

export const repoRoot = fileURLToPath(new URL("..", import.meta.url));
export const defaultReportDir = join(repoRoot, "build/reports/fixthis-android-proof");

export function parseArgs(argv = process.argv.slice(2)) {
  const options = {
    strict: false,
    continueOnFailure: false,
    reportDir: "build/reports/fixthis-android-proof",
    device: null,
    skipBuild: false,
    headed: false,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--strict") {
      options.strict = true;
    } else if (arg === "--continue") {
      options.continueOnFailure = true;
    } else if (arg === "--report-dir") {
      const value = argv[++index];
      if (!value) throw new Error("--report-dir requires a value");
      options.reportDir = value;
    } else if (arg === "--device") {
      const value = argv[++index];
      if (!value) throw new Error("--device requires a value");
      options.device = value;
    } else if (arg === "--skip-build") {
      options.skipBuild = true;
    } else if (arg === "--headed") {
      options.headed = true;
    } else if (arg === "-h" || arg === "--help") {
      options.help = true;
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  return options;
}

export function parseAdbDevices(stdout = "") {
  return String(stdout)
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith("List of devices attached"))
    .map((line) => {
      const [serialState, ...metadataParts] = line.split(/\s+/);
      const [serial, tabState] = line.split(/\s+/);
      const state = tabState || "";
      const metadata = metadataParts.slice(1).join(" ");
      return { serial: serialState || serial, state, metadata };
    })
    .filter((row) => row.serial && row.state);
}

const failureCatalog = Object.freeze({
  android_sdk_missing: {
    reason: "Android SDK platform-tools could not be resolved.",
    nextAction: "Install Android SDK platform-tools or fix ANDROID_HOME.",
  },
  device_missing: {
    reason: "No connected ready Android device or emulator is available.",
    nextAction: "Start an emulator or connect a device, then run `adb devices -l`.",
  },
  device_unauthorized: {
    reason: "ADB reports a device as unauthorized.",
    nextAction: "Unlock the device, approve USB debugging, confirm `adb devices -l`, then rerun `npm run android:proof -- --strict`.",
  },
  device_offline: {
    reason: "ADB reports a device as offline.",
    nextAction: "Run `adb kill-server && adb start-server`, confirm `adb devices -l`, then rerun `npm run android:proof -- --strict`.",
  },
  boot_incomplete: {
    reason: "The selected Android device has not completed boot.",
    nextAction: "Wait until `adb shell getprop sys.boot_completed` prints `1`, then rerun `npm run android:proof -- --strict`.",
  },
  multiple_devices: {
    reason: "Multiple ready Android devices are connected. Rerun with --device <serial>.",
    nextAction: "Rerun with `npm run android:proof -- --strict --device <serial>`.",
  },
  sample_smoke_failed: {
    reason: "The sample package smoke failed.",
    nextAction: "Inspect the sample smoke report under `.fixthis/smoke-reports/` and rerun `scripts/fixthis-smoke.sh --package io.github.beyondwin.fixthis.sample`.",
  },
  copy_prompt_failed: {
    reason: "The real browser Copy Prompt smoke failed.",
    nextAction: "Inspect the real Copy Prompt smoke report and rerun `npm run real-copy-prompt:smoke -- --strict`.",
  },
  agent_loop_failed: {
    reason: "The MCP agent loop smoke failed.",
    nextAction: "Inspect the agent-loop smoke report and rerun `npm run agent-loop:smoke -- --strict`.",
  },
  external_fixture_failed: {
    reason: "The strict external fixture matrix failed.",
    nextAction: "Inspect the external fixture matrix report and rerun `npm run external-fixture:matrix -- --strict`.",
  },
  unknown_failure: {
    reason: "The runner could not classify the failure.",
    nextAction: "Inspect the failed command output and rerun the printed command.",
  },
});

export function failureForCode(code) {
  const entry = failureCatalog[code] || failureCatalog.unknown_failure;
  return { failureCode: failureCatalog[code] ? code : "unknown_failure", ...entry };
}

function statusForBlocker(strict) {
  return strict ? "fail" : "deferred";
}

export function selectDevice(rows = [], options = {}) {
  const readyRows = rows.filter((row) => row.state === "device");
  const requestedSerial = options.device || options.envSerial || null;
  if (requestedSerial) {
    const selected = rows.find((row) => row.serial === requestedSerial);
    if (!selected) {
      return {
        status: "fail",
        serial: requestedSerial,
        failureCode: "device_missing",
        reason: `Selected Android device ${requestedSerial} was not found in adb devices -l.`,
      };
    }
    if (selected.state !== "device") {
      const failureCode = selected.state === "unauthorized"
        ? "device_unauthorized"
        : selected.state === "offline"
          ? "device_offline"
          : "device_missing";
      return {
        status: "fail",
        serial: requestedSerial,
        failureCode,
        reason: failureForCode(failureCode).reason,
      };
    }
    return { status: "pass", serial: requestedSerial, failureCode: null, reason: null };
  }
  if (readyRows.length === 1) {
    return { status: "pass", serial: readyRows[0].serial, failureCode: null, reason: null };
  }
  if (readyRows.length > 1) {
    return {
      status: "fail",
      serial: null,
      failureCode: "multiple_devices",
      reason: failureForCode("multiple_devices").reason,
    };
  }
  if (rows.some((row) => row.state === "unauthorized")) {
    return { status: "fail", serial: null, failureCode: "device_unauthorized", reason: failureForCode("device_unauthorized").reason };
  }
  if (rows.some((row) => row.state === "offline")) {
    return { status: "fail", serial: null, failureCode: "device_offline", reason: failureForCode("device_offline").reason };
  }
  return { status: "fail", serial: null, failureCode: "device_missing", reason: failureForCode("device_missing").reason };
}

export function classifyAndroidEnvironment(input = {}) {
  const strict = input.strict === true;
  if (!input.sdk || !input.adb) {
    const failure = failureForCode("android_sdk_missing");
    return {
      status: statusForBlocker(strict),
      sdk: input.sdk || null,
      adb: input.adb || null,
      deviceSerial: null,
      adbDevices: [],
      adbDevicesStdout: input.adbDevicesStdout || "",
      bootCompleted: false,
      failureCode: failure.failureCode,
      reason: failure.reason,
      nextAction: failure.nextAction,
    };
  }
  const rows = parseAdbDevices(input.adbDevicesStdout || "");
  const selection = selectDevice(rows, {
    device: input.device || null,
    envSerial: input.envSerial || null,
  });
  if (selection.status !== "pass") {
    const failure = failureForCode(selection.failureCode);
    return {
      status: statusForBlocker(strict),
      sdk: input.sdk,
      adb: input.adb,
      deviceSerial: selection.serial,
      adbDevices: rows,
      adbDevicesStdout: input.adbDevicesStdout || "",
      bootCompleted: false,
      failureCode: failure.failureCode,
      reason: selection.reason || failure.reason,
      nextAction: failure.nextAction,
    };
  }
  const bootCompleted = String(input.bootCompletedStdout || "").trim() === "1";
  if (!bootCompleted) {
    const failure = failureForCode("boot_incomplete");
    return {
      status: statusForBlocker(strict),
      sdk: input.sdk,
      adb: input.adb,
      deviceSerial: selection.serial,
      adbDevices: rows,
      adbDevicesStdout: input.adbDevicesStdout || "",
      bootCompleted: false,
      failureCode: failure.failureCode,
      reason: failure.reason,
      nextAction: failure.nextAction,
    };
  }
  return {
    status: "pass",
    sdk: input.sdk,
    adb: input.adb,
    deviceSerial: selection.serial,
    adbDevices: rows,
    adbDevicesStdout: input.adbDevicesStdout || "",
    bootCompleted: true,
    failureCode: null,
    reason: null,
    nextAction: null,
  };
}

function shellQuote(value) {
  const text = String(value);
  if (/^[A-Za-z0-9_./:@=-]+$/.test(text)) return text;
  return `'${text.replaceAll("'", "'\\''")}'`;
}

export function buildProofSteps(options = {}, environment = {}) {
  const serial = environment.deviceSerial || options.device || null;
  const sampleArgs = [
    "scripts/fixthis-smoke.sh",
    "--package",
    "io.github.beyondwin.fixthis.sample",
  ];
  if (serial) sampleArgs.push("--device", serial);
  if (options.skipBuild) sampleArgs.push("--no-build");
  const browserFlag = options.headed ? " --headed" : "";
  return [
    {
      name: "Sample smoke",
      command: sampleArgs.map(shellQuote).join(" "),
      failureCode: "sample_smoke_failed",
      reportPath: ".fixthis/smoke-reports/",
    },
    {
      name: "Real Copy Prompt smoke",
      command: `npm run real-copy-prompt:smoke -- --strict${browserFlag}`,
      failureCode: "copy_prompt_failed",
      reportPath: "build/reports/fixthis-real-copy-prompt/report.json",
    },
    {
      name: "Agent loop smoke",
      command: `npm run agent-loop:smoke -- --strict${browserFlag}`,
      failureCode: "agent_loop_failed",
      reportPath: "build/reports/fixthis-agent-loop/report.json",
    },
    {
      name: "External fixture matrix",
      command: "npm run external-fixture:matrix -- --strict",
      failureCode: "external_fixture_failed",
      reportPath: "build/reports/fixthis-external-fixture-matrix/report.json",
    },
  ];
}

export function buildReport({ strict = false, environment = {}, steps = [], generatedAt = new Date().toISOString() } = {}) {
  const failures = [];
  if (environment.status === "fail" || environment.status === "deferred") {
    failures.push({
      scope: "environment",
      failureCode: environment.failureCode,
      reason: environment.reason || failureForCode(environment.failureCode).reason,
      nextAction: environment.nextAction || failureForCode(environment.failureCode).nextAction,
    });
  }
  for (const step of steps) {
    if (step.status === "fail" || step.status === "deferred") {
      failures.push({
        scope: "step",
        step: step.name,
        failureCode: step.failureCode || "unknown_failure",
        reason: step.reason || failureForCode(step.failureCode).reason,
        nextAction: failureForCode(step.failureCode).nextAction,
      });
    }
  }
  const status = environment.status === "deferred"
    ? "deferred"
    : environment.status === "fail" || steps.some((step) => step.status === "fail")
      ? "fail"
      : steps.some((step) => step.status === "deferred")
        ? "pass_with_deferred"
        : "pass";
  return {
    schemaVersion: "1.0",
    status,
    strict,
    generatedAt,
    environment,
    steps,
    failures,
  };
}

function cell(value) {
  if (value === null || value === undefined || value === "") return "-";
  return String(value).replaceAll("|", "\\|");
}

export function renderMarkdownReport(report) {
  const lines = [
    "# FixThis Connected Android Proof",
    "",
    `- Status: ${report.status}`,
    `- Strict: ${report.strict}`,
    `- Generated: ${report.generatedAt}`,
    "",
    "## Environment",
    "",
    `- Status: ${report.environment?.status || "unknown"}`,
    `- SDK: ${report.environment?.sdk || "unavailable"}`,
    `- ADB: ${report.environment?.adb || "unavailable"}`,
    `- Device: ${report.environment?.deviceSerial || "unavailable"}`,
    `- Boot completed: ${report.environment?.bootCompleted === true}`,
  ];
  if (report.environment?.failureCode) lines.push(`- Failure code: ${report.environment.failureCode}`);
  if (report.environment?.nextAction) lines.push(`- Next action: ${report.environment.nextAction}`);
  lines.push("", "## Steps", "");
  lines.push("| Step | Status | Command | Duration | Failure | Reason |");
  lines.push("| --- | --- | --- | --- | --- | --- |");
  for (const step of report.steps || []) {
    lines.push(`| ${cell(step.name)} | ${cell(step.status)} | \`${cell(step.command)}\` | ${cell(step.durationMs ?? 0)}ms | ${cell(step.failureCode)} | ${cell(step.reason)} |`);
  }
  if ((report.failures || []).length > 0) {
    lines.push("", "## Next Actions", "");
    for (const failure of report.failures) {
      lines.push(`- ${failure.failureCode}: ${failure.nextAction}`);
    }
  }
  return `${lines.join("\n")}\n`;
}

export function writeReports(report, reportDir = defaultReportDir) {
  mkdirSync(reportDir, { recursive: true });
  const json = join(reportDir, "report.json");
  const markdown = join(reportDir, "report.md");
  writeFileSync(json, `${JSON.stringify(report, null, 2)}\n`);
  writeFileSync(markdown, renderMarkdownReport(report));
  return { json, markdown };
}

export async function main(argv = process.argv.slice(2)) {
  const options = parseArgs(argv);
  if (options.help) {
    process.stdout.write("Usage: node scripts/android-proof-runner.mjs [--strict] [--continue] [--report-dir <path>] [--device <serial>] [--skip-build] [--headed]\n");
    return 0;
  }
  const report = buildReport({
    strict: options.strict,
    environment: classifyAndroidEnvironment({
      strict: options.strict,
      sdk: null,
      adb: null,
      adbDevicesStdout: "",
    }),
  });
  const reportDir = resolve(repoRoot, options.reportDir);
  const paths = writeReports(report, reportDir);
  console.log(`Android proof: ${report.status}`);
  console.log(`JSON: ${paths.json}`);
  console.log(`Markdown: ${paths.markdown}`);
  return report.status === "fail" ? 1 : 0;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exitCode = await main();
}
```

- [ ] **Step 2: Run focused tests**

Run:

```bash
node --test scripts/android-proof-runner-test.mjs
```

Expected: PASS.

- [ ] **Step 3: Run the package script test path**

Run:

```bash
npm run android:proof:test
```

Expected: PASS.

- [ ] **Step 4: Run the CLI in non-strict mode without requiring Android**

Run:

```bash
npm run android:proof
```

Expected: exits 0 and prints:

```text
Android proof: deferred
```

It also writes:

```text
build/reports/fixthis-android-proof/report.json
build/reports/fixthis-android-proof/report.md
```

- [ ] **Step 5: Commit pure runner helpers**

Run:

```bash
git add package.json scripts/android-proof-runner.mjs scripts/android-proof-runner-test.mjs
git commit -m "feat(android): add connected proof report helpers"
```

Expected: commit succeeds.

---

### Task 3: Add Real Preflight And Smoke Step Orchestration

**Files:**
- Modify: `scripts/android-proof-runner.mjs`
- Modify: `scripts/android-proof-runner-test.mjs`

**Interfaces:**
- Consumes:
  - `parseArgs(argv)`
  - `classifyAndroidEnvironment(input)`
  - `buildProofSteps(options, environment)`
  - `buildReport(input)`
  - `writeReports(report, reportDir)`
- Produces:
  - `resolveAndroidPreflight(options: AndroidProofOptions, deps?: AndroidProofDeps): AndroidEnvironmentSummary`
  - `normalizeStepResult(step: ProofStep, result: CommandResult): NormalizedProofStep`
  - `runAndroidProof(options: AndroidProofOptions, deps?: AndroidProofDeps): { report: AndroidProofReport, paths: { json: string, markdown: string } }`

- [ ] **Step 1: Add failing orchestration tests**

First, extend the existing import from `./android-proof-runner.mjs` in
`scripts/android-proof-runner-test.mjs` so it also imports:

```js
  normalizeStepResult,
  resolveAndroidPreflight,
  runAndroidProof,
```

Then append these tests after the existing tests in
`scripts/android-proof-runner-test.mjs`:

```js
test("resolveAndroidPreflight records devices and boot state through injected spawn", () => {
  const calls = [];
  const environment = resolveAndroidPreflight({
    strict: true,
    device: "emulator-5554",
  }, {
    resolveAndroidEnvironment: () => ({
      ready: true,
      envPatch: {
        ANDROID_HOME: "/sdk",
        ANDROID_SDK_ROOT: "/sdk",
        PATH: "/sdk/platform-tools:/usr/bin",
      },
      sdk: "/sdk",
      adb: "/sdk/platform-tools/adb",
      device: "emulator-5554",
    }),
    spawnSync: (command, args) => {
      calls.push([command, ...args]);
      if (args.includes("devices")) {
        return {
          status: 0,
          stdout: "List of devices attached\nemulator-5554\tdevice model:Pixel_8\n",
          stderr: "",
        };
      }
      return { status: 0, stdout: "1\n", stderr: "" };
    },
  });

  assert.equal(environment.status, "pass");
  assert.equal(environment.sdk, "/sdk");
  assert.equal(environment.adb, "/sdk/platform-tools/adb");
  assert.equal(environment.deviceSerial, "emulator-5554");
  assert.equal(environment.bootCompleted, true);
  assert.deepEqual(calls, [
    ["/sdk/platform-tools/adb", "devices", "-l"],
    ["/sdk/platform-tools/adb", "-s", "emulator-5554", "shell", "getprop", "sys.boot_completed"],
  ]);
});

test("normalizeStepResult maps command failures to stable failure codes", () => {
  assert.deepEqual(normalizeStepResult({
    name: "Agent loop smoke",
    command: "npm run agent-loop:smoke -- --strict",
    failureCode: "agent_loop_failed",
    reportPath: "build/reports/fixthis-agent-loop/report.json",
  }, {
    status: 1,
    stdout: "out",
    stderr: "bad",
    durationMs: 42,
  }), {
    name: "Agent loop smoke",
    command: "npm run agent-loop:smoke -- --strict",
    status: "fail",
    exitCode: 1,
    durationMs: 42,
    failureCode: "agent_loop_failed",
    reason: "bad",
    reportPath: "build/reports/fixthis-agent-loop/report.json",
  });
});

test("runAndroidProof stops on first failed step by default", () => {
  const commands = [];
  const { report } = runAndroidProof({
    strict: true,
    continueOnFailure: false,
    reportDir: "build/reports/fixthis-android-proof-test",
    device: null,
    skipBuild: false,
    headed: false,
  }, {
    resolveAndroidPreflight: () => ({
      status: "pass",
      sdk: "/sdk",
      adb: "/sdk/platform-tools/adb",
      deviceSerial: "emulator-5554",
      bootCompleted: true,
      failureCode: null,
      nextAction: null,
    }),
    runCommand: (command) => {
      commands.push(command);
      return { status: command.includes("real-copy-prompt") ? 1 : 0, stdout: "", stderr: "copy failed", durationMs: 5 };
    },
    writeReports: (proofReport) => ({ json: "/tmp/report.json", markdown: "/tmp/report.md", proofReport }),
  });

  assert.deepEqual(commands, [
    "scripts/fixthis-smoke.sh --package io.github.beyondwin.fixthis.sample --device emulator-5554",
    "npm run real-copy-prompt:smoke -- --strict",
  ]);
  assert.equal(report.status, "fail");
  assert.equal(report.steps[1].failureCode, "copy_prompt_failed");
});

test("runAndroidProof continues after failed steps when requested", () => {
  const commands = [];
  const { report } = runAndroidProof({
    strict: true,
    continueOnFailure: true,
    reportDir: "build/reports/fixthis-android-proof-test",
    device: null,
    skipBuild: false,
    headed: false,
  }, {
    resolveAndroidPreflight: () => ({
      status: "pass",
      sdk: "/sdk",
      adb: "/sdk/platform-tools/adb",
      deviceSerial: "emulator-5554",
      bootCompleted: true,
      failureCode: null,
      nextAction: null,
    }),
    runCommand: (command) => {
      commands.push(command);
      return { status: command.includes("agent-loop") ? 1 : 0, stdout: "", stderr: "agent failed", durationMs: 5 };
    },
    writeReports: (proofReport) => ({ json: "/tmp/report.json", markdown: "/tmp/report.md", proofReport }),
  });

  assert.equal(commands.length, 4);
  assert.equal(report.status, "fail");
  assert.equal(report.steps.find((step) => step.name === "Agent loop smoke").failureCode, "agent_loop_failed");
});

test("runAndroidProof writes deferred environment report without running smokes", () => {
  const commands = [];
  const { report } = runAndroidProof({
    strict: false,
    continueOnFailure: false,
    reportDir: "build/reports/fixthis-android-proof-test",
    device: null,
    skipBuild: false,
    headed: false,
  }, {
    resolveAndroidPreflight: () => ({
      status: "deferred",
      sdk: null,
      adb: null,
      deviceSerial: null,
      bootCompleted: false,
      failureCode: "android_sdk_missing",
      reason: "Android SDK platform-tools could not be resolved.",
      nextAction: "Install Android SDK platform-tools or fix ANDROID_HOME.",
    }),
    runCommand: (command) => {
      commands.push(command);
      return { status: 0, stdout: "", stderr: "", durationMs: 1 };
    },
    writeReports: (proofReport) => ({ json: "/tmp/report.json", markdown: "/tmp/report.md", proofReport }),
  });

  assert.deepEqual(commands, []);
  assert.equal(report.status, "deferred");
  assert.equal(report.failures[0].failureCode, "android_sdk_missing");
});
```

- [ ] **Step 2: Run the orchestration tests and verify they fail**

Run:

```bash
node --test scripts/android-proof-runner-test.mjs
```

Expected: FAIL with missing export errors for:

```text
normalizeStepResult
resolveAndroidPreflight
runAndroidProof
```

- [ ] **Step 3: Implement preflight and orchestration**

Modify `scripts/android-proof-runner.mjs`.

Add imports at the top:

```js
import { spawnSync } from "node:child_process";
import { resolveAndroidEnvironment } from "./evidence-runner.mjs";
```

Add these exports after `buildProofSteps`:

```js
function sdkFromEnvironment(environment) {
  return environment.sdk || environment.envPatch?.ANDROID_HOME || environment.envPatch?.ANDROID_SDK_ROOT || null;
}

function adbFromEnvironment(environment) {
  const sdk = sdkFromEnvironment(environment);
  return environment.adb || (sdk ? join(sdk, "platform-tools", process.platform === "win32" ? "adb.exe" : "adb") : null);
}

export function resolveAndroidPreflight(options = {}, deps = {}) {
  const resolveEnvironment = deps.resolveAndroidEnvironment || resolveAndroidEnvironment;
  const spawn = deps.spawnSync || spawnSync;
  const base = resolveEnvironment();
  const sdk = sdkFromEnvironment(base);
  const adb = adbFromEnvironment(base);
  if (!sdk || !adb) {
    return classifyAndroidEnvironment({
      strict: options.strict,
      sdk,
      adb,
      adbDevicesStdout: "",
      device: options.device,
      envSerial: process.env.ANDROID_SERIAL || null,
    });
  }
  const env = { ...process.env, ...(base.envPatch || {}) };
  const devices = spawn(adb, ["devices", "-l"], {
    cwd: repoRoot,
    encoding: "utf8",
    env,
  });
  const deviceRows = parseAdbDevices(devices.stdout || "");
  const selection = selectDevice(deviceRows, {
    device: options.device || null,
    envSerial: process.env.ANDROID_SERIAL || null,
  });
  let bootCompletedStdout = "";
  if (selection.status === "pass" && selection.serial) {
    const boot = spawn(adb, ["-s", selection.serial, "shell", "getprop", "sys.boot_completed"], {
      cwd: repoRoot,
      encoding: "utf8",
      env,
    });
    bootCompletedStdout = boot.stdout || "";
  }
  return classifyAndroidEnvironment({
    strict: options.strict,
    sdk,
    adb,
    adbDevicesStdout: devices.stdout || "",
    bootCompletedStdout,
    device: options.device,
    envSerial: process.env.ANDROID_SERIAL || null,
  });
}

export function normalizeStepResult(step, result) {
  const exitCode = result.status ?? result.exitCode ?? 1;
  const passed = exitCode === 0 || result.status === "passed" || result.status === "pass";
  const stderrLine = String(result.stderr || "").split(/\r?\n/).find(Boolean);
  const stdoutLine = String(result.stdout || "").split(/\r?\n/).find(Boolean);
  return {
    name: step.name,
    command: step.command,
    status: passed ? "pass" : "fail",
    exitCode: passed ? 0 : exitCode,
    durationMs: result.durationMs ?? 0,
    failureCode: passed ? null : step.failureCode || "unknown_failure",
    reason: passed ? null : stderrLine || stdoutLine || failureForCode(step.failureCode).reason,
    reportPath: step.reportPath || null,
  };
}

function runShellCommand(command) {
  const started = Date.now();
  const result = spawnSync("bash", ["-lc", command], {
    cwd: repoRoot,
    encoding: "utf8",
    stdio: "pipe",
    env: process.env,
  });
  return {
    status: result.status ?? 1,
    stdout: result.stdout || "",
    stderr: result.stderr || "",
    durationMs: Date.now() - started,
  };
}

export function runAndroidProof(options = {}, deps = {}) {
  const preflight = (deps.resolveAndroidPreflight || resolveAndroidPreflight)(options, deps);
  const steps = [];
  if (preflight.status === "pass") {
    const run = deps.runCommand || runShellCommand;
    for (const step of buildProofSteps(options, preflight)) {
      const normalized = normalizeStepResult(step, run(step.command));
      steps.push(normalized);
      if (normalized.status === "fail" && !options.continueOnFailure) break;
    }
  }
  const report = buildReport({
    strict: options.strict,
    environment: preflight,
    steps,
  });
  const write = deps.writeReports || writeReports;
  const paths = write(report, resolve(repoRoot, options.reportDir || "build/reports/fixthis-android-proof"));
  return { report, paths };
}
```

Replace the body of `main()` with:

```js
export async function main(argv = process.argv.slice(2)) {
  const options = parseArgs(argv);
  if (options.help) {
    process.stdout.write("Usage: node scripts/android-proof-runner.mjs [--strict] [--continue] [--report-dir <path>] [--device <serial>] [--skip-build] [--headed]\n");
    return 0;
  }
  const { report, paths } = runAndroidProof(options);
  console.log(`Android proof: ${report.status}`);
  console.log(`JSON: ${paths.json}`);
  console.log(`Markdown: ${paths.markdown}`);
  return report.status === "fail" ? 1 : 0;
}
```

- [ ] **Step 4: Run focused runner tests**

Run:

```bash
npm run android:proof:test
```

Expected: PASS.

- [ ] **Step 5: Run non-strict CLI smoke**

Run:

```bash
npm run android:proof
```

Expected: exits 0. If Android is unavailable, it prints:

```text
Android proof: deferred
```

If Android is available, it may run connected smokes and should print either:

```text
Android proof: pass
```

or:

```text
Android proof: fail
```

When it prints fail because a real smoke failed, inspect `build/reports/fixthis-android-proof/report.md` and keep the report for debugging. Do not commit report artifacts.

- [ ] **Step 6: Run strict CLI only when a ready device exists**

Run:

```bash
adb devices -l
```

If no ready unlocked device is listed, skip the strict run and record `SKIPPED_NO_DEVICE` in the task notes.

If a ready unlocked device is listed, run:

```bash
npm run android:proof -- --strict
```

Expected: exits 0 when all connected smokes pass, or exits 1 with a classified failure in `build/reports/fixthis-android-proof/report.md`.

- [ ] **Step 7: Commit orchestration**

Run:

```bash
git add scripts/android-proof-runner.mjs scripts/android-proof-runner-test.mjs
git commit -m "feat(android): orchestrate connected proof smokes"
```

Expected: commit succeeds.

---

### Task 4: Wire Android Proof Into Evidence And Release Gate Claims

**Files:**
- Modify: `scripts/evidence-runner.mjs`
- Modify: `scripts/evidence-runner-test.mjs`
- Modify: `scripts/release-gate.mjs`
- Modify: `scripts/release-gate-test.mjs`

**Interfaces:**
- Consumes:
  - npm script `android:proof`
  - npm script `android:proof:test`
  - release gate evidence step name `Connected Android proof`
- Produces:
  - Evidence profile step:
    - `name: "Connected Android proof"`
    - `command: "npm run android:proof -- --strict --continue"`
    - `deferrable: true`
    - `requiresAndroid: true`
  - Release claim:
    - `id: "connected-android-proof"`
    - `evidenceNames: ["Connected Android proof"]`

- [ ] **Step 1: Add failing evidence-runner tests**

Append this test to `scripts/evidence-runner-test.mjs`:

```js
test("gate profile includes connected Android proof runner", () => {
  const proof = expandProfile("gate").find((step) => step.name === "Connected Android proof");
  assert.equal(proof.command, "npm run android:proof -- --strict --continue");
  assert.equal(proof.deferrable, true);
  assert.equal(proof.requiresAndroid, true);
});

test("package exposes connected Android proof commands", () => {
  const pkg = JSON.parse(readFileSync("package.json", "utf8"));
  assert.equal(pkg.scripts["android:proof"], "node scripts/android-proof-runner.mjs");
  assert.equal(pkg.scripts["android:proof:test"], "node --test scripts/android-proof-runner-test.mjs");
});
```

- [ ] **Step 2: Add failing release-gate tests**

Append these tests to `scripts/release-gate-test.mjs`:

```js
test("release gate maps connected Android proof claim", () => {
  const report = buildReleaseGateReport({
    strict: false,
    generatedAt: "2026-06-26T00:00:00.000Z",
    steps: [
      {
        name: "Connected Android proof",
        command: "npm run android:proof -- --strict --continue",
        status: "deferred",
        reason: "Android SDK platform-tools could not be resolved.",
      },
    ],
  });

  assert.deepEqual(report.claims.find((claim) => claim.id === "connected-android-proof"), {
    id: "connected-android-proof",
    status: "deferred",
    evidence: ["Connected Android proof"],
    reason: "Android SDK platform-tools could not be resolved.",
  });
});

test("release gate profile produces connected Android proof input", () => {
  const proof = releaseGateSteps().find((step) => step.name === "Connected Android proof");
  assert.equal(proof.command, "npm run android:proof -- --strict --continue");
  assert.equal(proof.deferrable, true);
  assert.equal(proof.requiresAndroid, true);
});
```

- [ ] **Step 3: Run tests and verify they fail**

Run:

```bash
node --test scripts/evidence-runner-test.mjs scripts/release-gate-test.mjs
```

Expected: FAIL because `Connected Android proof` and `connected-android-proof` are not wired yet.

- [ ] **Step 4: Add the evidence profile step**

Modify `scripts/evidence-runner.mjs`.

In the `gate` profile, add this step after `Plugin contract` and before `Runtime trust boundary observations`:

```js
    step("Connected Android proof", "npm run android:proof -- --strict --continue", {
      deferrable: true,
      requiresAndroid: true,
    }),
```

- [ ] **Step 5: Add the release claim**

Modify `scripts/release-gate.mjs`.

Add this entry to `releaseClaimDefinitions` after `android-agent-evidence-umbrella`:

```js
  { id: 'connected-android-proof', evidenceNames: ['Connected Android proof'] },
```

- [ ] **Step 6: Run focused evidence and release-gate tests**

Run:

```bash
node --test scripts/evidence-runner-test.mjs scripts/release-gate-test.mjs
```

Expected: PASS.

- [ ] **Step 7: Run release gate contract test through npm**

Run:

```bash
npm run release:gate:test
```

Expected: PASS.

- [ ] **Step 8: Commit release wiring**

Run:

```bash
git add scripts/evidence-runner.mjs scripts/evidence-runner-test.mjs scripts/release-gate.mjs scripts/release-gate-test.mjs
git commit -m "test(release): gate connected android proof"
```

Expected: commit succeeds.

---

### Task 5: Update Documentation And Release-Readiness Guards

**Files:**
- Modify: `CONTRIBUTING.md`
- Modify: `docs/contributing/release-readiness.md`
- Modify: `scripts/check-release-readiness.mjs`

**Interfaces:**
- Consumes:
  - `npm run android:proof -- --strict`
  - `npm run android:proof:test`
  - release claim `connected-android-proof`
- Produces:
  - Contributor docs that make Android proof runner primary.
  - Release-readiness docs that require Android proof evidence.
  - Release-readiness script guards for those claims.

- [ ] **Step 1: Add failing release-readiness guard tests by updating the guard first**

Modify `scripts/check-release-readiness.mjs`.

Add these checks after the existing `R55.android-agent-evidence-gate-claim` rule:

```js
requireIncludes(
  'R56.connected-android-proof-contributing-command',
  'CONTRIBUTING.md',
  'npm run android:proof -- --strict',
);
requireIncludes(
  'R57.connected-android-proof-readiness-section',
  'docs/contributing/release-readiness.md',
  '## Connected Android Proof Runner Evidence',
);
requireIncludes(
  'R58.connected-android-proof-strict-command',
  'docs/contributing/release-readiness.md',
  '`npm run android:proof -- --strict`',
);
requireIncludes(
  'R59.connected-android-proof-gate-claim',
  'scripts/release-gate.mjs',
  'connected-android-proof',
);
```

- [ ] **Step 2: Run release-readiness check and verify it fails**

Run:

```bash
node scripts/check-release-readiness.mjs
```

Expected: FAIL with messages for:

```text
R56.connected-android-proof-contributing-command
R57.connected-android-proof-readiness-section
R58.connected-android-proof-strict-command
```

`R59` should pass if Task 4 has landed.

- [ ] **Step 3: Update CONTRIBUTING connected device section**

Replace `CONTRIBUTING.md` lines under `## Connected Device Checks` through the focused smoke command list with this text:

```md
## Connected Device Checks

Use the connected Android proof runner when validating release-decision device
behavior:

```bash
npm run android:proof -- --strict
```

The runner checks Android SDK/ADB readiness, device selection, boot completion,
the bundled sample smoke, real Copy Prompt, the external agent lifecycle, and
the external fixture matrix. It writes JSON and Markdown reports under
`build/reports/fixthis-android-proof/`; these reports are ignored build
artifacts and should not be committed.

For a broader failure picture in one run, use:

```bash
npm run android:proof -- --strict --continue
```

When debugging a specific layer, the focused commands remain available:

```bash
scripts/fixthis-smoke.sh --package io.github.beyondwin.fixthis.sample
npm run real-copy-prompt:smoke -- --strict
npm run agent-loop:smoke -- --strict
npm run external-fixture:matrix -- --strict
```

For release-decision evidence, run:
```

Keep the existing release-decision evidence block that follows.

- [ ] **Step 4: Add release-readiness section**

Modify `docs/contributing/release-readiness.md`.

Add this section after `## First Handoff Autopilot Evidence` and before `## Release Gate, Interop Evidence, And SSE Closure`:

```md
## Connected Android Proof Runner Evidence

The connected Android proof runner line may be claimed only when the release
commit has evidence that one command classifies Android readiness and runs the
sample, real Copy Prompt, agent-loop, and external fixture strict smokes.

| Claim | Required evidence |
| --- | --- |
| Connected Android readiness is classified before expensive smokes run, and the proof runner reports exact next actions for SDK, ADB, device, boot, and smoke failures. | `npm run android:proof:test` and `npm run android:proof -- --strict` |
| Release-gate reports expose connected Android proof as a first-class claim. | `npm run release:gate:test` and `npm run release:gate -- --strict` |

Connected Android proof remains local-only. If Android SDK or a ready unlocked
device is unavailable, non-strict reports must record the exact deferred reason
and strict reports must fail.
```

- [ ] **Step 5: Run release-readiness check**

Run:

```bash
node scripts/check-release-readiness.mjs
```

Expected: PASS.

- [ ] **Step 6: Run doc-adjacent tests**

Run:

```bash
node scripts/check-doc-consistency.mjs
node --test scripts/evidence-runner-test.mjs scripts/release-gate-test.mjs
```

Expected: PASS.

- [ ] **Step 7: Commit docs and guards**

Run:

```bash
git add CONTRIBUTING.md docs/contributing/release-readiness.md scripts/check-release-readiness.mjs
git commit -m "docs(android): document connected proof runner"
```

Expected: commit succeeds.

---

## Final Verification

Run these commands after all tasks land:

```bash
npm run android:proof:test
node --test scripts/evidence-runner-test.mjs scripts/release-gate-test.mjs
node scripts/check-release-readiness.mjs
node scripts/check-doc-consistency.mjs
npm run android:proof
git diff --check
graphify update .
```

Expected:

- unit and docs checks pass;
- `npm run android:proof` exits 0 in non-strict mode;
- if Android SDK/device is unavailable, the report status is `deferred` with an exact failure code and next action;
- if Android SDK/device is ready, connected smoke failures are classified as `sample_smoke_failed`, `copy_prompt_failed`, `agent_loop_failed`, or `external_fixture_failed`;
- `graphify update .` completes and dirty `graphify-out/` remains uncommitted.

Run this only when a ready unlocked Android device or emulator is available:

```bash
npm run android:proof -- --strict
```

Expected:

- exits 0 only when all connected smokes pass;
- exits 1 with a classified report when Android runtime or a smoke step fails.

Do not commit these generated artifacts:

```text
build/reports/fixthis-android-proof/
.fixthis/
graphify-out/
```

## Plan Self-Review

Spec coverage:

- New `android:proof` command: Task 1 and Task 2.
- JSON and Markdown report under `build/reports/fixthis-android-proof/`: Task 2 and Task 3.
- Android environment classification before expensive smokes: Task 2 and Task 3.
- Strict and non-strict deferral semantics: Task 1, Task 2, Task 3.
- Existing smoke orchestration without duplicating behavior: Task 3.
- Release-gate connected Android proof claim: Task 4.
- Contributor and release-readiness docs: Task 5.
- Tests not requiring a real device: Task 1 through Task 5.

Placeholder scan:

- No unfinished markers or unspecified validation steps remain.
- Every code-changing step includes the exact code or exact edit target.
- Every test step includes the command and expected outcome.

Type consistency:

- The plan consistently uses `parseArgs`, `parseAdbDevices`, `selectDevice`, `classifyAndroidEnvironment`, `failureForCode`, `buildProofSteps`, `buildReport`, `renderMarkdownReport`, `resolveAndroidPreflight`, `normalizeStepResult`, and `runAndroidProof`.
- Release evidence consistently uses step name `Connected Android proof` and claim id `connected-android-proof`.
