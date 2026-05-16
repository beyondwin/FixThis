import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { comparePerf, renderMarkdown } from "./compare-perf.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const loadFixture = (name) => JSON.parse(fs.readFileSync(path.join(here, "__fixtures__", name), "utf8"));

const baseline = loadFixture("baseline-A.json");

test("comparePerf flags REGRESS when median delta exceeds threshold and band", () => {
  const result = comparePerf(baseline, loadFixture("current-regress.json"));
  const row = result.rows.find((r) => r.key === "warm-mcp-test");
  assert.equal(row.verdict, "REGRESS");
  assert.equal(result.regressed.length, 1);
  assert.equal(result.exitCode, 1);
});

test("comparePerf flags IMPROVE when median drops past threshold and band", () => {
  const result = comparePerf(baseline, loadFixture("current-improve.json"));
  const row = result.rows.find((r) => r.key === "warm-mcp-test");
  assert.equal(row.verdict, "IMPROVE");
  assert.equal(result.exitCode, 0);
});

test("comparePerf stays NEUTRAL within noise band", () => {
  const result = comparePerf(baseline, loadFixture("current-neutral.json"));
  for (const row of result.rows) {
    assert.equal(row.verdict, "NEUTRAL", `${row.key} should be NEUTRAL, got ${row.verdict}`);
  }
  assert.equal(result.exitCode, 0);
});

test("comparePerf warns on env fingerprint mismatch but does not fail", () => {
  const altEnv = JSON.parse(JSON.stringify(loadFixture("current-neutral.json")));
  altEnv.env.cpu_model = "different-cpu";
  const result = comparePerf(baseline, altEnv);
  assert.ok(result.warnings.some((w) => /cpu/i.test(w)), `expected cpu warning in ${result.warnings.join("; ")}`);
  assert.equal(result.exitCode, 0);
});

test("renderMarkdown produces a table with verdicts", () => {
  const result = comparePerf(baseline, loadFixture("current-regress.json"));
  const md = renderMarkdown(result);
  assert.match(md, /\| Scenario \| Baseline \(median\) \| Current \(median\) \| Delta \| Verdict \|/);
  assert.match(md, /REGRESS/);
});
