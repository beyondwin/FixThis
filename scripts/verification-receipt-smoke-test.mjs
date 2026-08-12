import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  assertStrictReport,
  assertActivityStartOutput,
  buildReport,
  fixtureSettings,
  mcpCall,
  packageCleanupOutcome,
  parseArgs,
  savedItemRowSelector,
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

test("browser proof scopes the verified badge to the exact saved item row", () => {
  assert.equal(
    savedItemRowSelector("item-123"),
    '[data-focus-saved="item-123"]',
  );
  assert.throws(() => savedItemRowSelector('item-123"] .verification-badge'), /invalid item id/i);
});
