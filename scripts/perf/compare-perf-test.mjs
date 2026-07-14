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

test("comparePerf keeps cross-environment regressions advisory", () => {
  const altEnv = JSON.parse(JSON.stringify(loadFixture("current-regress.json")));
  altEnv.env.cpu_model = "different-cpu";

  const result = comparePerf(baseline, altEnv);

  assert.deepEqual(result.regressed, []);
  assert.deepEqual(result.advisoryRegressions, ["warm-mcp-test"]);
  assert.equal(result.exitCode, 0);
});

test("comparePerf treats CPU-count drift as a cross-environment comparison", () => {
  const altEnv = JSON.parse(JSON.stringify(loadFixture("current-regress.json")));
  altEnv.env.cpu_count = baseline.env.cpu_count + 1;

  const result = comparePerf(baseline, altEnv);

  assert.ok(result.warnings.some((warning) => warning.includes("cpu_count")));
  assert.deepEqual(result.regressed, []);
  assert.deepEqual(result.advisoryRegressions, ["warm-mcp-test"]);
  assert.equal(result.exitCode, 0);
});

test("comparePerf keeps cross-environment improvements advisory", () => {
  const altEnv = JSON.parse(JSON.stringify(loadFixture("current-improve.json")));
  altEnv.env.cpu_model = "different-cpu";

  const result = comparePerf(baseline, altEnv);
  const row = result.rows.find((candidate) => candidate.key === "warm-mcp-test");

  assert.equal(row.verdict, "ADVISORY");
  assert.deepEqual(result.improved, []);
  assert.deepEqual(result.advisoryImprovements, ["warm-mcp-test"]);
  assert.equal(result.exitCode, 0);
});

test("comparePerf includes current-run variance in the regression noise band", () => {
  const noisyCurrent = JSON.parse(JSON.stringify(loadFixture("current-neutral.json")));
  const row = noisyCurrent.results.find((result) => result.key === "warm-mcp-test");
  row.median_ms = 1100;
  row.stddev_ms = 100;

  const result = comparePerf(baseline, noisyCurrent);
  const compared = result.rows.find((candidate) => candidate.key === "warm-mcp-test");

  assert.equal(compared.verdict, "NEUTRAL");
  assert.equal(compared.noiseBandMs, 200.73);
  assert.equal(result.exitCode, 0);
});

test("renderMarkdown produces a table with verdicts", () => {
  const result = comparePerf(baseline, loadFixture("current-regress.json"));
  const md = renderMarkdown(result);
  assert.match(md, /\| Scenario \| Baseline \(median\) \| Current \(median\) \| Delta \| Verdict \|/);
  assert.match(md, /REGRESS/);
});
