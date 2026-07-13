import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { delimiter, join } from "node:path";
import test from "node:test";
import {
  expandProfile,
  parseReadyAndroidDevice,
  planRun,
  renderDryRun,
  resolveAndroidEnvironment,
  runPlan,
  writeReports,
} from "./evidence-runner.mjs";

test("fast profile expands to cheap local commands", () => {
  const commands = expandProfile("fast").map((step) => step.command);
  assert.deepEqual(commands, [
    "node scripts/check-doc-consistency.mjs",
    "node scripts/check-release-readiness.mjs",
    "npm run docs:agent-bootstrap:test",
    "node scripts/build-console-assets.mjs --check",
    "npm run console:test:fast",
    "node scripts/check-whitespace.mjs diff --check",
  ]);
});

test("trust profile includes source matching and runtime strict as deferrable", () => {
  const steps = expandProfile("trust");
  assert.ok(steps.some((step) => step.command === "npm run source-matching:fixtures:test"));
  const runtimeEvidence = steps.find((step) => step.command === "npm run runtime-evidence:smoke");
  assert.equal(runtimeEvidence.name, "Runtime evidence attachment");
  const runtime = steps.find((step) => step.command === "npm run source-matching:fixtures:runtime -- --strict");
  assert.equal(runtime.deferrable, true);
  assert.equal(runtime.requiresAndroid, true);
  const copyPrompt = steps.find((step) => step.command === "npm run real-copy-prompt:smoke -- --strict");
  assert.equal(copyPrompt.deferrable, true);
  assert.equal(copyPrompt.requiresAndroid, true);
  const agentLoop = steps.find((step) => step.command === "npm run agent-loop:smoke -- --strict");
  assert.equal(agentLoop.deferrable, true);
  assert.equal(agentLoop.requiresAndroid, true);
  const runtimeProductPath = steps.find((step) => step.name === "Runtime evidence product path");
  assert.equal(runtimeProductPath.command, "npm run runtime-evidence:smoke -- --strict");
  assert.equal(runtimeProductPath.deferrable, true);
  assert.equal(runtimeProductPath.requiresAndroid, true);
  assert.equal(runtimeProductPath.reportPath, "build/reports/fixthis-runtime-evidence/report.json");
});

test("trust profile keeps focused external trust matrix v2 strict evidence", () => {
  const trustCommands = expandProfile('trust').map((step) => step.command);

  assert.ok(trustCommands.includes('npm run external-fixture:matrix -- --strict'));

  const trustStep = expandProfile('trust').find((step) => step.command === 'npm run external-fixture:matrix -- --strict');
  assert.equal(trustStep.name, 'External trust matrix v2 strict');
  assert.equal(trustStep.deferrable, true);
  assert.equal(trustStep.requiresAndroid, true);
});

test("android device parser returns the first ready adb device", () => {
  assert.equal(parseReadyAndroidDevice("List of devices attached\nemulator-5554\tdevice\nphone-1\toffline\n"), "emulator-5554");
  assert.equal(parseReadyAndroidDevice("List of devices attached\nemulator-5554\tdevice\nphone-1\tdevice\n", "phone-1"), "phone-1");
  assert.equal(parseReadyAndroidDevice("List of devices attached\nemulator-5554\tdevice\nphone-1\toffline\n", "phone-1"), null);
  assert.equal(parseReadyAndroidDevice("List of devices attached\nemulator-5554\toffline\n"), null);
});

test("android readiness pins the propagated ANDROID_SERIAL instead of the first ready device", () => {
  const sdk = "/sdk";
  const result = resolveAndroidEnvironment({
    env: { ANDROID_HOME: sdk, ANDROID_SERIAL: "phone-1", PATH: "/usr/bin" },
    homeDir: "/Users/test",
    platform: "darwin",
    exists: (path) => path === join(sdk, "platform-tools", "adb"),
    spawn: () => ({
      status: 0,
      stdout: "List of devices attached\nemulator-5554\tdevice\nphone-1\tdevice\n",
      stderr: "",
    }),
  });

  assert.equal(result.ready, true);
  assert.equal(result.device, "phone-1");
  assert.equal(result.envPatch.ANDROID_SERIAL, "phone-1");
});

test("android readiness falls back to common macOS SDK and injects platform-tools", () => {
  const calls = [];
  const sdk = "/Users/test/Library/Android/sdk";
  const result = resolveAndroidEnvironment({
    env: { PATH: "/usr/bin" },
    homeDir: "/Users/test",
    platform: "darwin",
    exists: (path) => path === join(sdk, "platform-tools", "adb"),
    spawn: (command, args, options) => {
      calls.push({ command, args, options });
      return {
        status: 0,
        stdout: "List of devices attached\nemulator-5554\tdevice\n",
        stderr: "",
      };
    },
  });

  assert.equal(result.ready, true);
  assert.equal(result.device, "emulator-5554");
  assert.equal(result.envPatch.ANDROID_HOME, sdk);
  assert.equal(result.envPatch.ANDROID_SDK_ROOT, sdk);
  assert.equal(result.envPatch.PATH.split(delimiter)[0], join(sdk, "platform-tools"));
  assert.equal(calls[0].command, join(sdk, "platform-tools", "adb"));
});

test("android readiness prefers an SDK candidate with bundled adb", () => {
  const validSdk = "/valid/sdk";
  const result = resolveAndroidEnvironment({
    env: {
      ANDROID_HOME: "/stale/sdk",
      ANDROID_SDK_ROOT: validSdk,
      PATH: "/usr/bin",
    },
    homeDir: "/Users/test",
    platform: "darwin",
    exists: (path) => path === join(validSdk, "platform-tools", "adb"),
    spawn: () => ({
      status: 0,
      stdout: "List of devices attached\nemulator-5554\tdevice\n",
      stderr: "",
    }),
  });

  assert.equal(result.ready, true);
  assert.equal(result.envPatch.ANDROID_HOME, validSdk);
  assert.equal(result.envPatch.ANDROID_SDK_ROOT, validSdk);
  assert.equal(result.envPatch.PATH.split(delimiter)[0], join(validSdk, "platform-tools"));
});

test("runPlan passes discovered Android environment to required steps", () => {
  const commands = [];
  const report = runPlan(
    {
      schemaVersion: "1.0",
      profile: "android",
      steps: [
        {
          name: "Runtime trust strict",
          command: "npm run source-matching:fixtures:runtime -- --strict",
          requiresAndroid: true,
        },
      ],
    },
    {
      androidEnvironment: {
        ready: true,
        envPatch: {
          ANDROID_HOME: "/sdk",
          ANDROID_SDK_ROOT: "/sdk",
          PATH: `/sdk/platform-tools${delimiter}/usr/bin`,
        },
      },
      runCommandFn: (command, envPatch) => {
        commands.push({ command, envPatch });
        return {
          status: "passed",
          durationMs: 1,
          stdout: "",
          stderr: "",
          exitCode: 0,
        };
      },
    },
  );

  assert.equal(report.status, "passed");
  assert.equal(commands[0].envPatch.ANDROID_HOME, "/sdk");
  assert.equal(commands[0].envPatch.PATH.split(delimiter)[0], "/sdk/platform-tools");
});

test("dry run renders commands without executing", () => {
  const text = renderDryRun(planRun({ profile: "release", dryRun: true }));
  assert.match(text, /DRY RUN profile=release/);
  assert.match(text, /node scripts\/check-release-readiness\.mjs/);
  assert.match(text, /npm run release:version:check/);
});

test("release profile starts with release reality and includes agent loop contracts", () => {
  const commands = expandProfile("release").map((step) => step.command);
  assert.equal(commands[0], "npm run release:reality");
  assert.ok(commands.includes("npm run agent-loop:smoke:test"));
  assert.ok(commands.includes("npm run runtime-evidence:smoke -- --strict"));
});

test("release and gate profiles include release drift guard", () => {
  const releaseCommands = expandProfile("release").map((step) => step.command);
  const gateCommands = expandProfile("gate").map((step) => step.command);

  assert.ok(releaseCommands.includes("npm run release:drift"));
  assert.ok(gateCommands.includes("npm run release:drift -- --strict"));
});

test("trust profile includes focused external fixture matrix commands", () => {
  const trustCommands = expandProfile("trust").map((step) => step.command);

  assert.ok(trustCommands.includes("npm run external-fixture:matrix:test"));
  assert.ok(trustCommands.includes("npm run external-fixture:matrix -- --strict"));
});

test("gate profile uses connected Android proof instead of duplicate connected smokes", () => {
  const commands = expandProfile("gate").map((step) => step.command);

  assert.ok(commands.includes("npm run release:reality"));
  assert.ok(commands.some((command) => command.includes("TargetBoundaryContextFormatterTest")));
  assert.ok(commands.includes("npm run source-matching:fixtures:test"));
  assert.ok(commands.includes("npm run agent-loop:smoke:test"));
  assert.ok(commands.includes("npm run android:proof -- --strict --continue"));
  assert.equal(commands.filter((command) => command === "npm run android:proof -- --strict --continue").length, 1);
  assert.ok(!commands.includes("npm run source-matching:fixtures:runtime -- --strict"));
  assert.ok(!commands.includes("npm run agent-loop:smoke -- --strict"));
  assert.ok(!commands.includes("npm run real-copy-prompt:smoke -- --strict"));
  assert.ok(!commands.includes("npm run external-fixture:matrix -- --strict"));
  assert.ok(commands.includes("npm run handoff:eval:test"));
  assert.ok(commands.includes("npm run console:browser:reliability"));
  assert.ok(commands.includes("node scripts/check-doc-consistency.mjs"));
  assert.ok(commands.includes("node scripts/check-whitespace.mjs diff --check"));
});

test("gate profile includes connected Android proof runner", () => {
  const proof = expandProfile("gate").find((step) => step.name === "Connected Android proof");
  assert.equal(proof.command, "npm run android:proof -- --strict --continue");
  assert.equal(proof.deferrable, true);
  assert.equal(proof.requiresAndroid, true);
  assert.equal(proof.reportPath, "build/reports/fixthis-android-proof/report.json");
});

test("package exposes connected Android proof commands", () => {
  const pkg = JSON.parse(readFileSync("package.json", "utf8"));
  assert.equal(pkg.scripts["android:proof"], "node scripts/android-proof-runner.mjs");
  assert.equal(pkg.scripts["android:proof:test"], "node --test scripts/android-proof-runner-test.mjs");
});

test("package exposes release gate commands", () => {
  const pkg = JSON.parse(readFileSync("package.json", "utf8"));
  assert.equal(pkg.scripts["release:gate"], "node scripts/release-gate.mjs");
  assert.equal(pkg.scripts["release:gate:test"], "node --test scripts/release-gate-test.mjs scripts/console-reliability-report-test.mjs");
});

test("package exposes release drift commands", () => {
  const pkg = JSON.parse(readFileSync("package.json", "utf8"));
  assert.equal(pkg.scripts["release:drift"], "node scripts/release-drift-guard.mjs");
  assert.equal(pkg.scripts["release:drift:test"], "node --test scripts/release-drift-guard-test.mjs");
});

test("package exposes runtime evidence smoke commands", () => {
  const pkg = JSON.parse(readFileSync("package.json", "utf8"));
  assert.equal(pkg.scripts["runtime-evidence:smoke"], "node scripts/runtime-evidence-smoke.mjs");
  assert.equal(pkg.scripts["runtime-evidence:smoke:test"], "node --test scripts/runtime-evidence-smoke-test.mjs");
});

test("writeReports writes json and markdown summaries", () => {
  const root = mkdtempSync(join(tmpdir(), "fixthis-evidence-"));
  try {
    const report = {
      schemaVersion: "1.0",
      profile: "fast",
      status: "passed",
      startedAt: "2026-05-27T00:00:00.000Z",
      finishedAt: "2026-05-27T00:00:01.000Z",
      steps: [
        {
          name: "Docs consistency",
          command: "node scripts/check-doc-consistency.mjs",
          status: "passed",
          durationMs: 10,
        },
      ],
    };
    const paths = writeReports(report, root);
    assert.match(readFileSync(paths.json, "utf8"), /"profile": "fast"/);
    assert.match(readFileSync(paths.markdown, "utf8"), /# FixThis Evidence Report/);
    assert.match(readFileSync(paths.markdown, "utf8"), /\| Docs consistency \| passed \|/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("unknown profile fails during planning", () => {
  assert.throws(() => planRun({ profile: "unknown", dryRun: true }), /Unknown evidence profile/);
});
