#!/usr/bin/env node
import { spawnSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { delimiter, dirname, join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { resolveAndroidEnvironment } from "./evidence-runner.mjs";

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
  runtime_evidence_failed: {
    reason: "The runtime evidence MCP product-path proof failed.",
    nextAction: "Inspect the runtime evidence report and rerun `npm run runtime-evidence:smoke -- --strict`.",
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
      name: "Runtime evidence product path",
      command: "npm run runtime-evidence:smoke -- --strict",
      failureCode: "runtime_evidence_failed",
      reportPath: "build/reports/fixthis-runtime-evidence/report.json",
    },
    {
      name: "External fixture matrix",
      command: "npm run external-fixture:matrix -- --strict",
      failureCode: "external_fixture_failed",
      reportPath: "build/reports/fixthis-external-fixture-matrix/report.json",
    },
  ];
}

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

function proofCommandEnvironment(preflight) {
  const platformTools = preflight.adb ? dirname(preflight.adb) : null;
  return {
    ...(preflight.sdk ? { ANDROID_HOME: preflight.sdk, ANDROID_SDK_ROOT: preflight.sdk } : {}),
    ...(preflight.deviceSerial ? { ANDROID_SERIAL: preflight.deviceSerial } : {}),
    PATH: [platformTools, process.env.PATH].filter(Boolean).join(delimiter),
  };
}

function runShellCommand(command, envPatch = {}) {
  const started = Date.now();
  const result = spawnSync("bash", ["-c", command], {
    cwd: repoRoot,
    encoding: "utf8",
    stdio: "pipe",
    env: { ...process.env, ...envPatch },
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
    const commandEnvironment = proofCommandEnvironment(preflight);
    for (const step of buildProofSteps(options, preflight)) {
      const normalized = normalizeStepResult(step, run(step.command, commandEnvironment));
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
  const { report, paths } = runAndroidProof(options);
  console.log(`Android proof: ${report.status}`);
  console.log(`JSON: ${paths.json}`);
  console.log(`Markdown: ${paths.markdown}`);
  return report.status === "fail" ? 1 : 0;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exitCode = await main();
}
