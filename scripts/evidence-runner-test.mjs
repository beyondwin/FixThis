import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  expandProfile,
  planRun,
  renderDryRun,
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
