import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  buildRuntimeEvidenceReport,
  createRuntimeEvidenceSmokeReport,
  parseArgs,
  proveRuntimeEvidenceProductPath,
  renderRuntimeEvidenceMarkdown,
  validateRuntimeEvidenceProductPath,
  writeRuntimeEvidenceReport,
} from "./runtime-evidence-smoke.mjs";

const productPath = {
  tool: "fixthis_collect_runtime_evidence",
  sessionId: "session-1",
  itemIds: ["item-1"],
  captureStatus: "complete",
  attachmentCount: 3,
  artifactVerified: true,
  compactHandoffBounded: true,
  replayVerified: true,
  autoHandoffVerified: true,
  linkageVerified: true,
  redactionVerified: true,
};

function productFixture(root) {
  const evidenceRoot = join(root, ".fixthis/runtime-evidence/session-1");
  const types = [
    ["logcat_window", "logcat.txt"],
    ["memory_summary", "memory.txt"],
    ["frame_summary", "frames.txt"],
  ];
  mkdirSync(join(evidenceRoot, "capture-manual"), { recursive: true });
  mkdirSync(join(evidenceRoot, "capture-auto"), { recursive: true });
  const attachments = [];
  for (const [index, [type, fileName]] of types.entries()) {
    writeFileSync(join(evidenceRoot, "capture-manual", fileName), "safe manual output\n");
    writeFileSync(join(evidenceRoot, "capture-auto", fileName), index === 0 ? "Authorization: [REDACTED]\n" : "safe auto output\n");
    for (const [prefix, trigger, captureId] of [
      ["manual", "mcp_manual", "capture-manual"],
      ["auto", "handoff_auto", "capture-auto"],
    ]) attachments.push({
      evidenceId: `${prefix}-${index + 1}`,
      type,
      status: "complete",
      trigger,
      captureId,
      deviceSerial: "emulator-5554",
      packageName: "io.github.beyondwin.fixthis.sample",
      screenCapturedAtEpochMillis: 1_000,
      captureStartedAtEpochMillis: 1_100,
      captureCompletedAtEpochMillis: 1_200,
      proximity: "near",
      artifactPath: `.fixthis/runtime-evidence/session-1/${captureId}/${fileName}`,
      warnings: prefix === "auto" && index === 0 ? ["redaction_applied"] : [],
    });
  }
  const manualIds = attachments.filter(entry => entry.trigger === "mcp_manual").map(entry => entry.evidenceId);
  const autoIds = attachments.filter(entry => entry.trigger === "handoff_auto").map(entry => entry.evidenceId);
  const session = {
    sessionId: "session-1",
    runtimeEvidencePolicy: "auto_on_handoff",
    runtimeEvidence: attachments,
    items: [{ itemId: "item-1", runtimeEvidenceIds: [...manualIds, ...autoIds] }],
  };
  return {
    toolName: "fixthis_collect_runtime_evidence",
    sessionId: "session-1",
    itemId: "item-1",
    projectDir: root,
    expectedDeviceSerial: "emulator-5554",
    policy: { runtimeEvidencePolicy: "auto_on_handoff" },
    collected: {
      attempted: true,
      captureId: "capture-manual",
      status: "complete",
      attachmentIds: manualIds,
      linkedItemIds: ["item-1"],
    },
    handoff: {
      prompt: "runtimeEvidenceAttempt:\n  attempted=true\n  status=complete\n",
      runtimeEvidence: {
        attempted: true,
        captureId: "capture-auto",
        status: "complete",
        attachmentIds: autoIds,
        linkedItemIds: ["item-1"],
      },
      session,
    },
    session,
    replayed: structuredClone(session),
  };
}

test("product-path report carries the strict MCP collection contract", async () => {
  const report = await createRuntimeEvidenceSmokeReport({
    args: { strict: true, outDir: "build/custom" },
    environment: { ready: true, device: "emulator-5554", envPatch: {} },
    runProductPath: async () => productPath,
  });
  assert.equal(report.status, "pass");
  assert.deepEqual(report.productPath, productPath);
});

test("non-strict missing Android defers while strict fails closed", async () => {
  const missing = { ready: false, reason: "No connected Android device." };
  assert.equal((await createRuntimeEvidenceSmokeReport({ args: { strict: false }, environment: missing })).status, "deferred");
  assert.equal((await createRuntimeEvidenceSmokeReport({ args: { strict: true }, environment: missing })).status, "fail");
});

test("validator rejects missing tool attachment unsafe artifact raw handoff and replay mismatch", () => {
  const invalid = [
    [{ ...productPath, tool: null }, /missing collection tool/],
    [{ ...productPath, attachmentCount: 0 }, /three baseline attachments/],
    [{ ...productPath, captureStatus: "partial" }, /complete baseline capture/],
    [{ ...productPath, attachmentCount: 1 }, /three baseline attachments/],
    [{ ...productPath, captureStatus: "failed" }, /complete baseline capture/],
    [{ ...productPath, artifactVerified: false }, /missing or outside/],
    [{ ...productPath, compactHandoffBounded: false }, /unbounded.*raw secret/],
    [{ ...productPath, replayVerified: false }, /replay mismatch/],
  ];
  for (const [value, pattern] of invalid) assert.throws(() => validateRuntimeEvidenceProductPath(value), pattern);
});

test("product-path proof ties manual MCP and Auto handoff attachments to one item, artifacts, redaction, and replay", () => {
  const root = mkdtempSync(join(tmpdir(), "fixthis-runtime-proof-"));
  try {
    assert.deepEqual(proveRuntimeEvidenceProductPath(productFixture(root)), productPath);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("product-path proof fails closed for skipped Auto, missing linkage, unsafe artifacts, raw secret, and replay drift", () => {
  const cases = [
    [fixture => { fixture.policy.runtimeEvidencePolicy = "manual"; }, /policy did not persist/],
    [fixture => { fixture.handoff.runtimeEvidence.attempted = false; }, /Auto handoff did not attempt/],
    [fixture => { fixture.handoff.runtimeEvidence.linkedItemIds = []; }, /linked item mismatch/],
    [fixture => { fixture.handoff.session.runtimeEvidence = fixture.handoff.session.runtimeEvidence.filter(entry => entry.evidenceId !== "auto-1"); }, /attachment missing/],
    [fixture => { fixture.handoff.session.items[0].runtimeEvidenceIds = fixture.handoff.session.items[0].runtimeEvidenceIds.filter(id => id !== "auto-1"); }, /not linked/],
    [fixture => { fixture.handoff.session.runtimeEvidence.find(entry => entry.evidenceId === "auto-1").artifactPath = "../outside.txt"; }, /artifact missing or outside/],
    [fixture => { fixture.session.runtimeEvidence.find(entry => entry.evidenceId === "auto-1").deviceSerial = "wrong-device"; }, /wrong device serial/],
    [fixture => { fixture.session.runtimeEvidence.find(entry => entry.evidenceId === "auto-1").packageName = "wrong.package"; }, /wrong package name/],
    [fixture => { fixture.session.runtimeEvidence.find(entry => entry.evidenceId === "auto-1").captureStartedAtEpochMillis = null; }, /missing start timestamp/],
    [fixture => { fixture.session.runtimeEvidence.find(entry => entry.evidenceId === "auto-1").type = "trace_artifact"; }, /type set does not match baseline/],
    [fixture => { fixture.handoff.prompt += "fixthis-runtime-secret-7f3a"; }, /raw secret entered handoff/],
    [fixture => { fixture.replayed.runtimeEvidence.find(entry => entry.evidenceId === "auto-1").captureId = "drifted"; }, /replay mismatch/],
  ];
  for (const [mutate, expected] of cases) {
    const root = mkdtempSync(join(tmpdir(), "fixthis-runtime-proof-fail-"));
    try {
      const fixture = productFixture(root);
      mutate(fixture);
      assert.throws(() => proveRuntimeEvidenceProductPath(fixture), expected);
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  }
});

test("ready product-path validation failure produces a failing report", async () => {
  const report = await createRuntimeEvidenceSmokeReport({
    args: { strict: true },
    environment: { ready: true, device: "emulator-5554", envPatch: {} },
    runProductPath: async () => validateRuntimeEvidenceProductPath({ ...productPath, replayVerified: false }),
  });
  assert.equal(report.status, "fail");
  assert.match(report.reason, /replay mismatch/);
});

test("report markdown renders product proof without direct generic adb rows", () => {
  const report = buildRuntimeEvidenceReport({ strict: true, status: "pass", productPath });
  const markdown = renderRuntimeEvidenceMarkdown(report);
  assert.match(markdown, /fixthis_collect_runtime_evidence/);
  assert.match(markdown, /session-1/);
  assert.doesNotMatch(markdown, /adb logcat -d -t 80|strict-runtime/);
});

test("parseArgs accepts only strict and report output", () => {
  assert.deepEqual(parseArgs(["--strict", "--out-dir", "build/custom"]), { strict: true, outDir: "build/custom" });
  assert.throws(() => parseArgs(["--item", "fabricated"]), /Unknown flag/);
});

test("writeRuntimeEvidenceReport writes JSON and Markdown", () => {
  const root = mkdtempSync(join(tmpdir(), "fixthis-runtime-report-"));
  try {
    const paths = writeRuntimeEvidenceReport(buildRuntimeEvidenceReport({ strict: true, status: "pass", productPath }), root);
    assert.match(readFileSync(paths.json, "utf8"), /"replayVerified": true/);
    assert.match(readFileSync(paths.markdown, "utf8"), /Runtime Evidence Report/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
