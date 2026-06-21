import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  buildRuntimeEvidenceReport,
  normalizeRuntimeEvidenceStatus,
  parseArgs,
  renderRuntimeEvidenceMarkdown,
  selectRuntimeEvidenceCommand,
  writeRuntimeEvidenceReport,
} from "./runtime-evidence-smoke.mjs";

test("normalizeRuntimeEvidenceStatus defers missing Android prerequisites in non-strict mode", () => {
  assert.deepEqual(
    normalizeRuntimeEvidenceStatus({ strict: false, androidReady: false, reason: "No connected Android device." }),
    { status: "deferred", reason: "No connected Android device." },
  );
  assert.deepEqual(
    normalizeRuntimeEvidenceStatus({ strict: true, androidReady: false, reason: "No connected Android device." }),
    { status: "fail", reason: "No connected Android device." },
  );
  assert.deepEqual(
    normalizeRuntimeEvidenceStatus({ strict: true, androidReady: true }),
    { status: "pass", reason: null },
  );
});

test("selectRuntimeEvidenceCommand maps evidence type to stable command description", () => {
  assert.equal(selectRuntimeEvidenceCommand("logcat_window").label, "Logcat window");
  assert.equal(selectRuntimeEvidenceCommand("frame_summary").command, "adb shell dumpsys gfxinfo <package>");
  assert.equal(selectRuntimeEvidenceCommand("memory_summary").command, "adb shell dumpsys meminfo <package>");
  assert.throws(() => selectRuntimeEvidenceCommand("raw_log_dump"), /Unsupported runtime evidence type/);
});

test("report markdown includes status type and artifact path", () => {
  const report = buildRuntimeEvidenceReport({
    strict: false,
    status: "pass",
    evidence: [
      {
        itemId: "item-1",
        type: "logcat_window",
        summary: "2 warnings",
        artifactPath: ".fixthis/runtime-evidence/e-1/logcat.txt",
      },
    ],
  });
  const markdown = renderRuntimeEvidenceMarkdown(report);

  assert.match(markdown, /# FixThis Runtime Evidence Report/);
  assert.match(markdown, /\| item-1 \| logcat_window \| 2 warnings \| `.fixthis\/runtime-evidence\/e-1\/logcat.txt` \|/);
});

test("report markdown bounds long summaries and keeps raw lines out", () => {
  const report = buildRuntimeEvidenceReport({
    strict: false,
    status: "pass",
    evidence: [
      {
        itemId: "item-1",
        type: "logcat_window",
        summary: "RuntimeException stack line ".repeat(40),
        artifactPath: ".fixthis/runtime-evidence/e-1/logcat.txt",
      },
    ],
  });
  const markdown = renderRuntimeEvidenceMarkdown(report);

  assert.ok(markdown.length < 900);
  assert.doesNotMatch(markdown, /RuntimeException stack line RuntimeException stack line RuntimeException stack line RuntimeException stack line RuntimeException stack line RuntimeException stack line RuntimeException stack line RuntimeException stack line/);
});

test("parseArgs recognizes strict output and evidence rows", () => {
  assert.deepEqual(
    parseArgs([
      "--strict",
      "--out-dir",
      "build/custom",
      "--item",
      "item-1",
      "--type",
      "frame_summary",
      "--summary",
      "6 slow frames",
      "--artifact",
      ".fixthis/runtime-evidence/e-2/gfxinfo.json",
    ]),
    {
      strict: true,
      outDir: "build/custom",
      evidence: [
        {
          itemId: "item-1",
          type: "frame_summary",
          summary: "6 slow frames",
          artifactPath: ".fixthis/runtime-evidence/e-2/gfxinfo.json",
        },
      ],
    },
  );
});

test("writeRuntimeEvidenceReport writes json and markdown reports", () => {
  const root = mkdtempSync(join(tmpdir(), "fixthis-runtime-evidence-"));
  try {
    const report = buildRuntimeEvidenceReport({
      strict: false,
      status: "pass",
      evidence: [
        {
          itemId: "item-1",
          type: "memory_summary",
          summary: "No growth detected",
          artifactPath: ".fixthis/runtime-evidence/e-3/meminfo.txt",
        },
      ],
    });
    const paths = writeRuntimeEvidenceReport(report, root);

    assert.match(readFileSync(paths.json, "utf8"), /"status": "pass"/);
    assert.match(readFileSync(paths.markdown, "utf8"), /FixThis Runtime Evidence Report/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
