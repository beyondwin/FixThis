import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import {
  buildProofSteps,
  buildReport,
  classifyAndroidEnvironment,
  failureForCode,
  normalizeStepResult,
  parseAdbDevices,
  parseArgs,
  renderMarkdownReport,
  resolveAndroidPreflight,
  runAndroidProof,
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
