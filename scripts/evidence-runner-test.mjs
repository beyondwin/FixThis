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
  const runtime = steps.find((step) => step.command === "npm run source-matching:fixtures:runtime -- --strict");
  assert.equal(runtime.deferrable, true);
  assert.equal(runtime.requiresAndroid, true);
});

test("android device parser returns the first ready adb device", () => {
  assert.equal(parseReadyAndroidDevice("List of devices attached\nemulator-5554\tdevice\nphone-1\toffline\n"), "emulator-5554");
  assert.equal(parseReadyAndroidDevice("List of devices attached\nemulator-5554\toffline\n"), null);
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
