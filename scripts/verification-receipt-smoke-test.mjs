import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { PassThrough } from "node:stream";
import test from "node:test";
import {
  assertStrictReport,
  assertActivityStartOutput,
  buildReport,
  coldLaunch,
  createOwnedResourceController,
  createRunPaths,
  installLifecycleCleanup,
  fixtureSettings,
  mcpCall,
  packageCleanupOutcome,
  parseArgs,
  savedItemRowSelector,
  runOwnedCommand,
  stopOwnedChild,
  terminateOwnedProcessGroup,
  writeReport,
} from "./verification-receipt-smoke.mjs";

const requiredSteps = [
  "baseline_install",
  "feedback_sent_and_claimed",
  "stale_install_failed_receipt",
  "rebuilt_install_passed_receipt",
  "restart_replayed_receipts",
  "failed_receipt_rejected",
  "passing_receipt_resolved",
  "console_rendered_verified",
];

function successfulSteps() {
  return requiredSteps.map((name) => ({ name, status: "PASS", evidence: `${name} evidence` }));
}

test("strict result requires every product-path checkpoint", () => {
  const report = buildReport({
    strict: true,
    deviceSerial: "emulator-5554",
    packageName: "io.github.beyondwin.fixthis.receipt.r1",
    steps: successfulSteps(),
  });

  assert.equal(report.status, "PASS");
  assert.deepEqual(report.steps.map((step) => step.name), requiredSteps);
  for (const name of requiredSteps) {
    assert.equal(report.steps.find((step) => step.name === name)?.status, "PASS");
  }
  assert.doesNotThrow(() => assertStrictReport(report));
});

test("strict report fails closed for missing, duplicate, failed, deferred, or skipped checkpoints", () => {
  const cases = [
    successfulSteps().slice(1),
    [...successfulSteps(), { name: "baseline_install", status: "PASS" }],
    successfulSteps().map((step) => step.name === "failed_receipt_rejected" ? { ...step, status: "FAIL" } : step),
    successfulSteps().map((step) => step.name === "baseline_install" ? { ...step, status: "DEFERRED" } : step),
    successfulSteps().map((step) => step.name === "console_rendered_verified" ? { ...step, status: "SKIPPED" } : step),
  ];

  for (const steps of cases) {
    const report = buildReport({ strict: true, steps });
    assert.equal(report.status, "FAIL");
    assert.throws(() => assertStrictReport(report), /verification receipt strict proof failed/i);
  }
});

test("strict report fails closed when cleanup records a failure after all checkpoints pass", () => {
  const report = buildReport({
    strict: true,
    steps: successfulSteps(),
    failures: ["Package cleanup failed"],
  });

  assert.equal(report.status, "FAIL");
  assert.throws(() => assertStrictReport(report), /verification receipt strict proof failed/i);
});

test("report preserves explicit stale, replay artifact, resolution, and browser evidence", () => {
  const steps = successfulSteps();
  steps[2].details = { verdict: "fail", receiptId: "receipt-fail", checkKinds: ["SOURCE_INSTALL_STALE"] };
  steps[3].details = { verdict: "pass", receiptId: "receipt-pass", assertions: ["target_present", "text_present"] };
  steps[4].details = {
    receiptIds: ["receipt-fail", "receipt-pass"],
    afterArtifact: ".fixthis/feedback-sessions/session-1/verification/receipt-pass/after.png",
    artifactContained: true,
    artifactSurvivedRestart: true,
  };
  steps[5].details = { prefix: "VERIFICATION_RECEIPT_FAILED:" };
  steps[6].details = { resolutionVerificationReceiptId: "receipt-pass" };
  steps[7].details = { badge: "verified", linkedReceiptId: "receipt-pass" };

  const report = buildReport({ strict: true, steps });
  assert.equal(report.status, "PASS");
  assert.deepEqual(report.steps[2].details.checkKinds, ["SOURCE_INSTALL_STALE"]);
  assert.equal(report.steps[4].details.artifactContained, true);
  assert.equal(report.steps[4].details.artifactSurvivedRestart, true);
  assert.equal(report.steps[6].details.resolutionVerificationReceiptId, "receipt-pass");
  assert.equal(report.steps[7].details.badge, "verified");
});

test("mcpCall returns structured content and preserves stable tool failures", async () => {
  const successClient = {
    requestTool: async (name, args) => ({
      content: [{ type: "text", text: `${name}:${args.itemId}` }],
      structuredContent: { receipt: { receiptId: "receipt-1", verdict: "pass" } },
      isError: false,
    }),
  };
  assert.deepEqual(
    await mcpCall(successClient, "fixthis_verify_feedback", { itemId: "item-1" }),
    {
      content: [{ type: "text", text: "fixthis_verify_feedback:item-1" }],
      structuredContent: { receipt: { receiptId: "receipt-1", verdict: "pass" } },
      isError: false,
    },
  );

  const failedClient = {
    requestTool: async () => ({
      content: [{ type: "text", text: "VERIFICATION_RECEIPT_FAILED: receipt-fail" }],
      isError: true,
    }),
  };
  await assert.rejects(
    () => mcpCall(failedClient, "fixthis_resolve_feedback", {}),
    /VERIFICATION_RECEIPT_FAILED:/,
  );
});

test("parseArgs supports strict, headed, device, report directory, bounded retries, and offline help", () => {
  assert.deepEqual(
    parseArgs([
      "--strict",
      "--headed",
      "--device",
      "emulator-5554",
      "--report-dir",
      "build/custom-receipts",
      "--max-retries",
      "5",
    ]),
    {
      strict: true,
      headed: true,
      help: false,
      device: "emulator-5554",
      reportDir: "build/custom-receipts",
      maxRetries: 5,
    },
  );
  assert.equal(parseArgs(["--help"]).help, true);
  assert.throws(() => parseArgs(["--max-retries", "0"]), /positive integer/);
  assert.throws(() => parseArgs(["--unknown"]), /Unknown argument/);
});

test("writeReport emits stable JSON and Markdown checkpoint contracts", () => {
  const directory = mkdtempSync(join(tmpdir(), "fixthis-verification-receipt-report-"));
  try {
    const report = buildReport({ strict: true, deviceSerial: "emulator-5554", steps: successfulSteps() });
    const paths = writeReport(report, directory);
    assert.match(readFileSync(paths.json, "utf8"), /"status": "PASS"/);
    const markdown = readFileSync(paths.markdown, "utf8");
    assert.match(markdown, /FixThis Verification Receipt Product Path/);
    assert.match(markdown, /stale_install_failed_receipt/);
    assert.match(markdown, /restart_replayed_receipts/);
    assert.match(markdown, /emulator-5554/);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("external fixture resolves runtime artifacts from a fixture-contained Maven repository", () => {
  const settings = fixtureSettings({
    pluginDirectory: "/repo/fixthis-gradle-plugin",
    versionCatalogPath: "/repo/gradle/libs.versions.toml",
    localMavenDirectory: "/fixture/local-maven",
  });

  assert.match(settings, /includeBuild\("\/repo\/fixthis-gradle-plugin"\)/);
  assert.match(settings, /maven \{ url = uri\("\/fixture\/local-maven"\) \}/);
  assert.doesNotMatch(settings, /include\(":fixthis-compose-(?:core|sidekick)"\)/);
});

test("cleanup treats a never-installed unique package as already absent", () => {
  assert.deepEqual(packageCleanupOutcome({ packagePathStatus: 0, packagePathOutput: "", uninstallStatus: null, uninstallOutput: "" }), {
    packageWasInstalled: false,
    packageUninstalled: true,
  });
  assert.deepEqual(packageCleanupOutcome({ packagePathStatus: 0, packagePathOutput: "package:/data/app/base.apk", uninstallStatus: 0, uninstallOutput: "Success" }), {
    packageWasInstalled: true,
    packageUninstalled: true,
  });
  assert.deepEqual(packageCleanupOutcome({ packagePathStatus: 0, packagePathOutput: "package:/data/app/base.apk", uninstallStatus: 0, uninstallOutput: "Failure" }), {
    packageWasInstalled: true,
    packageUninstalled: false,
  });
});

test("cold launch rejects adb am output that did not start the fixture activity", () => {
  assert.doesNotThrow(() => assertActivityStartOutput(
    "Starting: Intent { cmp=io.example/io.fixture.MainActivity }\nStatus: ok\nActivity: io.example/io.fixture.MainActivity\n",
    "io.example",
    "io.fixture.MainActivity",
  ));
  assert.throws(
    () => assertActivityStartOutput("Error type 3\nActivity class does not exist", "io.example", "io.fixture.MainActivity"),
    /did not start io\.example\/io\.fixture\.MainActivity/,
  );
  assert.throws(
    () => assertActivityStartOutput("Status: timeout", "io.example", "io.fixture.MainActivity"),
    /did not start io\.example\/io\.fixture\.MainActivity/,
  );
});

test("cold launch retries only an exact timeout/unknown result and force-stops before every attempt", async () => {
  const packageName = "io.example";
  const activityClass = "io.fixture.MainActivity";
  const calls = [];
  const starts = [
    `Starting: Intent { cmp=${packageName}/${activityClass} }\nStatus: timeout\nLaunchState: UNKNOWN (-1)\nActivity: ${packageName}/${activityClass}\n`,
    `Starting: Intent { cmp=${packageName}/${activityClass} }\nStatus: ok\nActivity: ${packageName}/${activityClass}\n`,
  ];
  const run = (_command, args) => {
    calls.push(args);
    return { stdout: args.includes("start") ? starts.shift() : "" };
  };

  await coldLaunch(
    { device: "emulator-5554", env: {} },
    { packageName, namespace: "io.fixture" },
    { run, attempts: 2, delay: async () => {} },
  );

  assert.deepEqual(calls.map((args) => args.slice(3, 5)), [
    ["am", "force-stop"],
    ["am", "start"],
    ["am", "force-stop"],
    ["am", "start"],
  ]);
});

test("cold launch fails immediately for non-retryable adb semantics and bounds timeout retries", async () => {
  const packageName = "io.example";
  const activityClass = "io.fixture.MainActivity";
  const cases = [
    "Error type 3\nActivity class does not exist\n",
    "Status: timeout\nLaunchState: UNKNOWN (-1)\nActivity: io.other/io.other.MainActivity\n",
  ];
  for (const output of cases) {
    let starts = 0;
    await assert.rejects(
      () => coldLaunch(
        { device: "emulator-5554", env: {} },
        { packageName, namespace: "io.fixture" },
        {
          run: (_command, args) => {
            if (args.includes("start")) starts += 1;
            return { stdout: args.includes("start") ? output : "" };
          },
          attempts: 2,
          delay: async () => {},
        },
      ),
      /did not start io\.example\/io\.fixture\.MainActivity/,
    );
    assert.equal(starts, 1);
  }

  let timeoutStarts = 0;
  await assert.rejects(
    () => coldLaunch(
      { device: "emulator-5554", env: {} },
      { packageName, namespace: "io.fixture" },
      {
        run: (_command, args) => {
          if (args.includes("start")) timeoutStarts += 1;
          return {
            stdout: args.includes("start")
              ? `Status: timeout\nLaunchState: UNKNOWN (-1)\nActivity: ${packageName}/${activityClass}\n`
              : "",
          };
        },
        attempts: 2,
        delay: async () => {},
      },
    ),
    /did not start io\.example\/io\.fixture\.MainActivity/,
  );
  assert.equal(timeoutStarts, 2);
});

test("browser proof scopes the verified badge to the exact saved item row", () => {
  assert.equal(
    savedItemRowSelector("item-123"),
    '[data-focus-saved="item-123"]',
  );
  assert.throws(() => savedItemRowSelector('item-123"] .verification-badge'), /invalid item id/i);
});

test("owned child shutdown is recorded only after verified exit", async () => {
  class FakeChild extends EventEmitter {
    constructor(closeOnSignal = null) {
      super();
      this.pid = 41;
      this.exitCode = null;
      this.signalCode = null;
      this.closeOnSignal = closeOnSignal;
      this.signals = [];
    }
    kill(signal) {
      this.signals.push(signal);
      if (signal === this.closeOnSignal) {
        this.signalCode = signal;
        queueMicrotask(() => this.emit("close", null, signal));
      }
      return true;
    }
  }

  const stopped = new FakeChild("SIGTERM");
  assert.equal(await stopOwnedChild(stopped, { graceMs: 5, killMs: 5 }), true);
  assert.deepEqual(stopped.signals, ["SIGTERM"]);

  const leaked = new FakeChild();
  assert.equal(await stopOwnedChild(leaked, { graceMs: 1, killMs: 1 }), false);
  assert.deepEqual(leaked.signals, ["SIGTERM", "SIGKILL"]);

  const controller = createOwnedResourceController();
  controller.trackMcp({ pid: leaked.pid, close: async () => false });
  const cleanup = await controller.cleanup("test");
  assert.deepEqual(cleanup.ownedMcpStopped, []);
  assert.match(cleanup.failures.join("\n"), /unconfirmed owned MCP PID 41/i);
});

test("run paths are contained and isolated for concurrent runs", () => {
  const base = "/repo/build/tmp/fixthis-verification-receipt";
  const first = createRunPaths(base, "run-a");
  const second = createRunPaths(base, "run-b");

  assert.equal(first.fixtureDirectory, "/repo/build/tmp/fixthis-verification-receipt/run-a/fixture");
  assert.equal(second.fixtureDirectory, "/repo/build/tmp/fixthis-verification-receipt/run-b/fixture");
  assert.notEqual(first.runDirectory, second.runDirectory);
  assert.throws(() => createRunPaths(base, "../escape"), /invalid run id/i);
});

test("lifecycle cleanup is once-only and preserves signal and exception exit semantics", async () => {
  const processLike = new EventEmitter();
  processLike.exitCode = 0;
  const exits = [];
  let cleanups = 0;
  const dispose = installLifecycleCleanup({
    processLike,
    cleanup: async () => { cleanups += 1; },
    exit: (code) => exits.push(code),
  });

  processLike.emit("SIGTERM");
  processLike.emit("SIGINT");
  await new Promise((resolvePromise) => setImmediate(resolvePromise));
  assert.equal(cleanups, 1);
  assert.deepEqual(exits, [143]);
  dispose();

  const exceptionProcess = new EventEmitter();
  const exceptionExits = [];
  installLifecycleCleanup({
    processLike: exceptionProcess,
    cleanup: async () => {},
    exit: (code) => exceptionExits.push(code),
  });
  exceptionProcess.emit("uncaughtException", new Error("boom"));
  await new Promise((resolvePromise) => setImmediate(resolvePromise));
  assert.deepEqual(exceptionExits, [1]);
});

test("command timeout terminates and waits for only its exact owned process group", async () => {
  const calls = [];
  const child = { pid: 91, exitCode: null, signalCode: null };
  const stopped = await terminateOwnedProcessGroup(child, {
    killGroup: (pid, signal) => calls.push([pid, signal]),
    waitForExit: async (_child, phase) => phase === "kill",
  });

  assert.equal(stopped, true);
  assert.deepEqual(calls, [[91, "SIGTERM"], [91, "SIGKILL"]]);
  assert.equal(calls.some(([pid]) => pid !== 91), false);
});

test("owned command timeout reports failure only after its process group stops", async () => {
  const child = new EventEmitter();
  child.pid = 92;
  child.exitCode = null;
  child.signalCode = null;
  child.stdout = new PassThrough();
  child.stderr = new PassThrough();
  const terminated = [];

  await assert.rejects(
    () => runOwnedCommand("gradlew", ["task"], {
      timeout: 1,
      spawnImpl: () => child,
      terminateGroup: async (active) => {
        terminated.push(active.pid);
        active.signalCode = "SIGKILL";
        active.emit("close", null, "SIGKILL");
        return true;
      },
    }),
    /timed out.*owned process group PID 92 stopped/i,
  );
  assert.deepEqual(terminated, [92]);
});

test("owned command spawn error verifies its exact process group stopped before rejection", async () => {
  const child = new EventEmitter();
  child.pid = 93;
  child.exitCode = null;
  child.signalCode = null;
  child.stdout = new PassThrough();
  child.stderr = new PassThrough();
  const terminated = [];
  const command = runOwnedCommand("gradlew", ["task"], {
    timeout: 100,
    spawnImpl: () => child,
    terminateGroup: async (active) => {
      terminated.push(active.pid);
      active.signalCode = "SIGKILL";
      return true;
    },
  });
  queueMicrotask(() => child.emit("error", Object.assign(new Error("spawn broke"), { code: "EIO" })));

  await assert.rejects(command, /failed \(EIO\).*owned process group PID 93 stopped/i);
  assert.deepEqual(terminated, [93]);
});
