import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { selectConsoleTests } from "./run-console-tests.mjs";

const repoRoot = path.resolve(import.meta.dirname, "..");
const groups = JSON.parse(readFileSync(path.join(repoRoot, "scripts/console-tests.json"), "utf8"));

test("all includes notification, reliability, FSM and canonical regressions", () => {
  const files = selectConsoleTests(["all"]);
  for (const file of [
    "scripts/notificationCenter-test.mjs",
    "scripts/draftRecoveryMatrix-test.mjs",
    "scripts/connectionFsm-test.mjs",
    "scripts/consoleCanonicalRuntimeContract-test.mjs",
  ]) {
    assert.ok(files.includes(file), "all omitted " + file);
  }
  assert.equal(files.length, new Set(files).size, "overlapping groups must run each file once");
});

test("all automatically includes newly registered groups", (t) => {
  const root = mkdtempSync(path.join(tmpdir(), "fixthis-console-groups-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  writeFileSync(path.join(root, "existing-test.mjs"), "");
  writeFileSync(path.join(root, "future-test.mjs"), "");
  const catalog = { existing: ["existing-test.mjs"] };
  assert.deepEqual(selectConsoleTests(["all"], { groups: catalog, root }), ["existing-test.mjs"]);
  catalog.future = ["future-test.mjs"];
  assert.deepEqual(selectConsoleTests(["all"], { groups: catalog, root }), ["existing-test.mjs", "future-test.mjs"]);
});

test("explicit overlapping groups preserve first occurrence order", () => {
  const files = selectConsoleTests(["reliability", "draft", "reliability"]);
  assert.equal(files.length, new Set(files).size);
  assert.equal(files[0], groups.reliability[0]);
});

test("unknown and missing groups fail before running a partial suite", () => {
  assert.throws(() => selectConsoleTests([]), /Usage:/);
  assert.throws(() => selectConsoleTests(["typo"]), /Unknown group: typo/);
  assert.throws(() => selectConsoleTests(["toString"]), /Unknown group: toString/);
});

test("a missing registered test fails instead of silently shrinking the suite", () => {
  assert.throws(
    () => selectConsoleTests(["all"], { groups: { fsm: ["scripts/missing-fsm-test.mjs"] } }),
    /Missing console test: scripts\/missing-fsm-test.mjs/,
  );
});
