# Build & Test Performance Measurement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a measurement-first harness (parser + aggregator + comparator + committed baseline + CI report) that proves whether a build/test config change helps, with one proof-of-value Gradle JVM tweak shipping alongside as empirical evidence the gate works.

**Architecture:** All driver/parser/comparator code is ESM Node scripts under `scripts/perf/` (matching the project's existing `scripts/*.mjs` convention) with Node's built-in test runner. The benchmark JSON schema is single-source-of-truth — parser, aggregator, comparator all consume/emit it. Wall-clock numbers are parsed from Gradle `--profile` HTML for Gradle scenarios and from `process.hrtime.bigint()` wrapping `node --test` invocations for JS scenarios. Statistical gate uses median + 2σ band, not p-value tests (N is too small).

**Tech Stack:** Node 20+/25 ESM, `node --test`, `node:test`, `node:assert/strict`, Gradle 9 `--profile`, GitHub Actions `$GITHUB_STEP_SUMMARY`, Markdown reporting.

Spec: [`../specs/2026-05-16-build-test-performance-measurement-design.md`](../specs/2026-05-16-build-test-performance-measurement-design.md)

---

## File Structure

**Create:**
- `scripts/perf/perf-scenarios.json` — single source of truth for tracked scenarios.
- `scripts/perf/parse-gradle-profile.mjs` — Gradle `--profile` HTML parser.
- `scripts/perf/aggregate.mjs` — sample aggregation (drop outliers, compute median/p95/stddev).
- `scripts/perf/compare-perf.mjs` — baseline-vs-current comparator + markdown report.
- `scripts/perf/bench-gradle.mjs` — Gradle scenario driver.
- `scripts/perf/bench-node.mjs` — node-test scenario driver.
- `scripts/perf/bench.mjs` — orchestrator dispatched by `npm run perf:bench`.
- `scripts/perf/env-fingerprint.mjs` — captures os/CPU/RAM/JDK/Node versions.
- `scripts/perf/README.md` — usage doc.
- `scripts/perf/__fixtures__/profile-2026-05-16-baseline.html` — committed Gradle profile HTML for parser tests.
- `scripts/perf/__fixtures__/baseline-A.json` — synthetic baseline for comparator tests.
- `scripts/perf/__fixtures__/current-regress.json` — synthetic regression input.
- `scripts/perf/__fixtures__/current-improve.json` — synthetic improvement input.
- `scripts/perf/__fixtures__/current-neutral.json` — synthetic neutral input.
- `scripts/perf/parse-gradle-profile-test.mjs` — parser unit tests.
- `scripts/perf/aggregate-test.mjs` — aggregator unit tests.
- `scripts/perf/compare-perf-test.mjs` — comparator unit tests.
- `docs/perf/baseline-2026-05-16.json` — committed baseline JSON.
- `docs/perf/evidence/` — directory for Task 9 outcome markdown (positive or negative).
- `.github/workflows/perf-report.yml` — manual + nightly perf workflow.

**Modify:**
- `gradle.properties` — Task 8 only, raises `-Xmx` after measurement justifies it.
- `package.json` — add `perf:bench`, `perf:compare`, `perf:test` scripts.
- `CONTRIBUTING.md` — add "Performance Measurement" section.
- `.gitignore` — add `output/perf/` (runtime measurement outputs are not committed).
- `scripts/console-tests.json` — unchanged; included only as a read source.

**Output (not committed):**
- `output/perf/run-<timestamp>.json` — every harness invocation writes here.

---

## Task 1: Define scenario schema and capture the parser fixture

**Files:**
- Create: `scripts/perf/perf-scenarios.json`
- Create: `scripts/perf/__fixtures__/profile-2026-05-16-baseline.html`
- Create: `scripts/perf/README.md`

- [ ] **Step 1: Write the scenario manifest**

Create `scripts/perf/perf-scenarios.json` with this exact content:

```json
{
  "$schema": "./perf-scenarios.schema.json",
  "scenarios": [
    {
      "key": "cold-mcp-test",
      "kind": "gradle",
      "command": [":fixthis-mcp:test"],
      "mode": "cold",
      "iterations": 3,
      "regress_threshold_pct": 10,
      "improve_threshold_pct": 5
    },
    {
      "key": "warm-mcp-test",
      "kind": "gradle",
      "command": [":fixthis-mcp:test"],
      "mode": "warm",
      "iterations": 5,
      "regress_threshold_pct": 8,
      "improve_threshold_pct": 5
    },
    {
      "key": "cold-assemble",
      "kind": "gradle",
      "command": [":app:assembleDebug"],
      "mode": "cold",
      "iterations": 3,
      "regress_threshold_pct": 10,
      "improve_threshold_pct": 5
    },
    {
      "key": "warm-assemble",
      "kind": "gradle",
      "command": [":app:assembleDebug"],
      "mode": "warm",
      "iterations": 5,
      "regress_threshold_pct": 8,
      "improve_threshold_pct": 5
    },
    {
      "key": "installdist-mcp",
      "kind": "gradle",
      "command": [":fixthis-mcp:installDist"],
      "mode": "warm",
      "iterations": 5,
      "regress_threshold_pct": 8,
      "improve_threshold_pct": 5
    },
    {
      "key": "console-test-fast",
      "kind": "node",
      "groups": ["availability", "pending", "beforeunload", "undo", "activity", "preview"],
      "mode": "warm",
      "iterations": 5,
      "regress_threshold_pct": 10,
      "improve_threshold_pct": 5
    },
    {
      "key": "console-test-all",
      "kind": "node",
      "groups": ["availability", "canonical", "pending", "beforeunload", "undo", "activity", "preview", "draft", "session", "harness"],
      "mode": "warm",
      "iterations": 5,
      "regress_threshold_pct": 10,
      "improve_threshold_pct": 5
    }
  ]
}
```

- [ ] **Step 2: Capture a Gradle profile fixture from main HEAD**

Run from repo root on a clean checkout:

```bash
./gradlew :fixthis-mcp:test --profile --no-build-cache --rerun-tasks
ls -1 build/reports/profile/ | tail -1
```

Copy the latest HTML file:

```bash
cp build/reports/profile/$(ls -1 build/reports/profile/ | tail -1) scripts/perf/__fixtures__/profile-2026-05-16-baseline.html
```

Verify the file is non-empty and contains `<table` and `Total Build Time`:

```bash
test -s scripts/perf/__fixtures__/profile-2026-05-16-baseline.html
grep -c "Total Build Time" scripts/perf/__fixtures__/profile-2026-05-16-baseline.html
```

Expected: `test` exits 0; `grep -c` prints `1` or more.

- [ ] **Step 3: Write the perf README**

Create `scripts/perf/README.md` with this exact content:

````markdown
# Performance Measurement Harness

This directory contains the build/test benchmark harness. See
[`../../docs/superpowers/specs/2026-05-16-build-test-performance-measurement-design.md`](../../docs/superpowers/specs/2026-05-16-build-test-performance-measurement-design.md)
for the design.

## Quick start

```bash
# Run all scenarios (slow — 20–40 minutes depending on hardware)
npm run perf:bench

# Run one scenario, fewer iterations
npm run perf:bench -- --scenario warm-mcp-test --iterations 3

# Compare against the committed baseline
npm run perf:compare -- docs/perf/baseline-2026-05-16.json output/perf/run-<timestamp>.json
```

## Output

Every run writes `output/perf/run-<ISO-timestamp>.json` (gitignored).
The schema is documented in `perf-scenarios.json` and the parser
output is documented in `parse-gradle-profile.mjs`.

## Regression rule

A scenario is flagged `REGRESS` when:
- `median_current - median_baseline > max(2 * stddev_baseline, 0.02 * median_baseline)` (noise band)
- AND `(median_current - median_baseline) / median_baseline * 100 > scenario.regress_threshold_pct`

Improvements use the symmetric rule against `improve_threshold_pct`.
Everything else is `NEUTRAL`.

## Hardware variance

The JSON records OS, CPU model, RAM, JDK, and Node versions. When
comparing across machines the comparator prints a warning but does
not fail; re-baseline locally for a fair comparison.
````

- [ ] **Step 4: Verify file shape**

Run:

```bash
node -e "JSON.parse(require('node:fs').readFileSync('scripts/perf/perf-scenarios.json'))"
test -s scripts/perf/__fixtures__/profile-2026-05-16-baseline.html
test -s scripts/perf/README.md
```

Expected: all three commands exit 0.

- [ ] **Step 5: Commit**

```bash
git add scripts/perf/perf-scenarios.json scripts/perf/__fixtures__/profile-2026-05-16-baseline.html scripts/perf/README.md
git commit -m "perf: define scenarios and capture parser fixture"
```

---

## Task 2: Gradle profile parser (TDD)

**Files:**
- Create: `scripts/perf/parse-gradle-profile.mjs`
- Create: `scripts/perf/parse-gradle-profile-test.mjs`

- [ ] **Step 1: Write the failing test**

Create `scripts/perf/parse-gradle-profile-test.mjs`:

```javascript
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { parseGradleProfile } from "./parse-gradle-profile.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const fixture = fs.readFileSync(path.join(here, "__fixtures__/profile-2026-05-16-baseline.html"), "utf8");

test("parseGradleProfile returns total wall-clock ms", () => {
  const result = parseGradleProfile(fixture);
  assert.equal(typeof result.totalMs, "number");
  assert.ok(result.totalMs > 0, `totalMs should be > 0, got ${result.totalMs}`);
});

test("parseGradleProfile returns ISO start timestamp", () => {
  const result = parseGradleProfile(fixture);
  assert.match(result.startedAt, /^\d{4}-\d{2}-\d{2}T/);
});

test("parseGradleProfile throws on empty input", () => {
  assert.throws(() => parseGradleProfile(""), /empty|no profile|invalid/i);
});

test("parseGradleProfile throws when Total Build Time row missing", () => {
  assert.throws(
    () => parseGradleProfile("<html><body>no profile here</body></html>"),
    /total build time/i,
  );
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
node --test scripts/perf/parse-gradle-profile-test.mjs
```

Expected: FAIL with `Cannot find module ... parse-gradle-profile.mjs` or similar.

- [ ] **Step 3: Write the minimal parser**

Create `scripts/perf/parse-gradle-profile.mjs`:

```javascript
const TOTAL_RE = /Total Build Time[\s\S]*?<td[^>]*>([0-9.]+)\s*(ms|s|m|h)/i;
const STARTED_RE = /Started at[^<]*<\/td>\s*<td[^>]*>([^<]+)</i;
const STARTED_FALLBACK_RE = /generated\s*<\/td>\s*<td[^>]*>([^<]+)</i;

const UNIT_MS = { ms: 1, s: 1000, m: 60_000, h: 3_600_000 };

export function parseGradleProfile(html) {
  if (!html || typeof html !== "string" || html.trim().length === 0) {
    throw new Error("parseGradleProfile: empty input");
  }
  const totalMatch = TOTAL_RE.exec(html);
  if (!totalMatch) {
    throw new Error("parseGradleProfile: 'Total Build Time' row not found");
  }
  const totalMs = Number(totalMatch[1]) * (UNIT_MS[totalMatch[2].toLowerCase()] ?? 1);
  const startedMatch = STARTED_RE.exec(html) ?? STARTED_FALLBACK_RE.exec(html);
  const startedAt = startedMatch ? new Date(startedMatch[1].trim()).toISOString() : new Date().toISOString();
  return { totalMs, startedAt };
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
node --test scripts/perf/parse-gradle-profile-test.mjs
```

Expected: `# pass 4` (4 passing tests).

If "Total Build Time" parsing fails on the fixture, inspect the actual row format:

```bash
grep -A1 -i "Total Build Time" scripts/perf/__fixtures__/profile-2026-05-16-baseline.html | head -10
```

…and refine `TOTAL_RE` to match the observed markup. Re-run the tests.

- [ ] **Step 5: Commit**

```bash
git add scripts/perf/parse-gradle-profile.mjs scripts/perf/parse-gradle-profile-test.mjs
git commit -m "perf: add Gradle --profile parser with unit tests"
```

---

## Task 3: Sample aggregator (TDD)

**Files:**
- Create: `scripts/perf/aggregate.mjs`
- Create: `scripts/perf/aggregate-test.mjs`

- [ ] **Step 1: Write the failing test**

Create `scripts/perf/aggregate-test.mjs`:

```javascript
import { test } from "node:test";
import assert from "node:assert/strict";
import { aggregate } from "./aggregate.mjs";

test("aggregate drops first sample as warmup", () => {
  const r = aggregate([1000, 100, 110, 105, 115], { dropWarmup: true });
  assert.equal(r.dropped.includes("warmup:1000"), true);
  assert.ok(r.median_ms < 200, `median should ignore warmup, got ${r.median_ms}`);
});

test("aggregate drops min and max when N-1 >= 4", () => {
  const r = aggregate([100, 200, 300, 400, 500, 600], { dropWarmup: true });
  // After warmup drop: [200,300,400,500,600], N=5 → drop min(200) and max(600)
  // Remaining: [300,400,500] → median = 400
  assert.equal(r.median_ms, 400);
  assert.ok(r.dropped.some((d) => d.startsWith("min:")), `expected min drop in ${r.dropped}`);
  assert.ok(r.dropped.some((d) => d.startsWith("max:")), `expected max drop in ${r.dropped}`);
});

test("aggregate does not drop min/max when N-1 < 4", () => {
  const r = aggregate([1000, 100, 200, 300], { dropWarmup: true });
  // After warmup drop: [100,200,300], N=3 → no min/max drop
  assert.equal(r.median_ms, 200);
  assert.equal(r.dropped.filter((d) => d.startsWith("min:") || d.startsWith("max:")).length, 0);
});

test("aggregate reports p95 by linear interpolation", () => {
  const r = aggregate([100, 100, 100, 100, 100, 100], { dropWarmup: true });
  assert.equal(r.p95_ms, 100);
});

test("aggregate reports stddev with Bessel correction", () => {
  const r = aggregate([0, 100, 200, 300, 400], { dropWarmup: true });
  // After warmup drop: [100,200,300,400], N=4 → no min/max drop
  // mean = 250, stddev = sqrt((150^2 + 50^2 + 50^2 + 150^2) / 3) ≈ 129.10
  assert.ok(Math.abs(r.stddev_ms - 129.10) < 0.5, `stddev=${r.stddev_ms}`);
});

test("aggregate throws if no samples remain after drops", () => {
  assert.throws(() => aggregate([100], { dropWarmup: true }), /no samples/i);
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
node --test scripts/perf/aggregate-test.mjs
```

Expected: FAIL (module not found).

- [ ] **Step 3: Write the minimal aggregator**

Create `scripts/perf/aggregate.mjs`:

```javascript
export function aggregate(samples_ms, { dropWarmup = true } = {}) {
  if (!Array.isArray(samples_ms) || samples_ms.length === 0) {
    throw new Error("aggregate: samples_ms must be a non-empty array");
  }
  const dropped = [];
  let work = samples_ms.slice();
  if (dropWarmup && work.length >= 2) {
    dropped.push(`warmup:${work[0]}`);
    work = work.slice(1);
  }
  if (work.length >= 4) {
    const min = Math.min(...work);
    const max = Math.max(...work);
    const minIdx = work.indexOf(min);
    work.splice(minIdx, 1);
    dropped.push(`min:${min}`);
    const maxIdx = work.indexOf(max);
    work.splice(maxIdx, 1);
    dropped.push(`max:${max}`);
  }
  if (work.length === 0) {
    throw new Error("aggregate: no samples remain after drops");
  }
  const sorted = work.slice().sort((a, b) => a - b);
  const n = sorted.length;
  const mean = sorted.reduce((s, v) => s + v, 0) / n;
  const median_ms = n % 2 === 0 ? (sorted[n / 2 - 1] + sorted[n / 2]) / 2 : sorted[(n - 1) / 2];
  const rank = 0.95 * (n - 1);
  const lo = Math.floor(rank);
  const hi = Math.ceil(rank);
  const p95_ms = lo === hi ? sorted[lo] : sorted[lo] + (rank - lo) * (sorted[hi] - sorted[lo]);
  const variance = n > 1
    ? sorted.reduce((s, v) => s + (v - mean) ** 2, 0) / (n - 1)
    : 0;
  const stddev_ms = Math.sqrt(variance);
  return {
    n,
    samples_ms: sorted,
    mean_ms: round(mean),
    median_ms: round(median_ms),
    p95_ms: round(p95_ms),
    stddev_ms: round(stddev_ms),
    dropped,
  };
}

function round(value) {
  return Math.round(value * 100) / 100;
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
node --test scripts/perf/aggregate-test.mjs
```

Expected: `# pass 6`.

- [ ] **Step 5: Commit**

```bash
git add scripts/perf/aggregate.mjs scripts/perf/aggregate-test.mjs
git commit -m "perf: add sample aggregator with outlier handling"
```

---

## Task 4: Comparator (TDD)

**Files:**
- Create: `scripts/perf/compare-perf.mjs`
- Create: `scripts/perf/compare-perf-test.mjs`
- Create: `scripts/perf/__fixtures__/baseline-A.json`
- Create: `scripts/perf/__fixtures__/current-regress.json`
- Create: `scripts/perf/__fixtures__/current-improve.json`
- Create: `scripts/perf/__fixtures__/current-neutral.json`

- [ ] **Step 1: Write the synthetic fixtures**

Create `scripts/perf/__fixtures__/baseline-A.json`:

```json
{
  "schema": 1,
  "started_at": "2026-05-16T00:00:00.000Z",
  "git_sha": "0000baseline",
  "env": { "os": "test", "arch": "test", "cpu_model": "test", "ram_mb": 16384, "jdk": "21", "node": "25" },
  "results": [
    { "key": "warm-mcp-test", "n": 4, "samples_ms": [1000,1010,990,1005], "median_ms": 1002.5, "mean_ms": 1001.25, "p95_ms": 1007.75, "stddev_ms": 8.54, "dropped": ["warmup:1100"], "regress_threshold_pct": 8, "improve_threshold_pct": 5 },
    { "key": "warm-assemble", "n": 4, "samples_ms": [5000,5050,4950,5025], "median_ms": 5012.5, "mean_ms": 5006.25, "p95_ms": 5046.25, "stddev_ms": 42.7, "dropped": ["warmup:5400"], "regress_threshold_pct": 8, "improve_threshold_pct": 5 }
  ]
}
```

Create `scripts/perf/__fixtures__/current-regress.json`:

```json
{
  "schema": 1,
  "started_at": "2026-05-17T00:00:00.000Z",
  "git_sha": "1111regress",
  "env": { "os": "test", "arch": "test", "cpu_model": "test", "ram_mb": 16384, "jdk": "21", "node": "25" },
  "results": [
    { "key": "warm-mcp-test", "n": 4, "samples_ms": [1200,1210,1190,1205], "median_ms": 1202.5, "mean_ms": 1201.25, "p95_ms": 1207.75, "stddev_ms": 8.54, "dropped": ["warmup:1300"], "regress_threshold_pct": 8, "improve_threshold_pct": 5 },
    { "key": "warm-assemble", "n": 4, "samples_ms": [5000,5050,4950,5025], "median_ms": 5012.5, "mean_ms": 5006.25, "p95_ms": 5046.25, "stddev_ms": 42.7, "dropped": ["warmup:5400"], "regress_threshold_pct": 8, "improve_threshold_pct": 5 }
  ]
}
```

Create `scripts/perf/__fixtures__/current-improve.json`:

```json
{
  "schema": 1,
  "started_at": "2026-05-17T00:00:00.000Z",
  "git_sha": "2222improve",
  "env": { "os": "test", "arch": "test", "cpu_model": "test", "ram_mb": 16384, "jdk": "21", "node": "25" },
  "results": [
    { "key": "warm-mcp-test", "n": 4, "samples_ms": [800,810,790,805], "median_ms": 802.5, "mean_ms": 801.25, "p95_ms": 807.75, "stddev_ms": 8.54, "dropped": ["warmup:900"], "regress_threshold_pct": 8, "improve_threshold_pct": 5 },
    { "key": "warm-assemble", "n": 4, "samples_ms": [5000,5050,4950,5025], "median_ms": 5012.5, "mean_ms": 5006.25, "p95_ms": 5046.25, "stddev_ms": 42.7, "dropped": ["warmup:5400"], "regress_threshold_pct": 8, "improve_threshold_pct": 5 }
  ]
}
```

Create `scripts/perf/__fixtures__/current-neutral.json`:

```json
{
  "schema": 1,
  "started_at": "2026-05-17T00:00:00.000Z",
  "git_sha": "3333neutral",
  "env": { "os": "test", "arch": "test", "cpu_model": "test", "ram_mb": 16384, "jdk": "21", "node": "25" },
  "results": [
    { "key": "warm-mcp-test", "n": 4, "samples_ms": [1005,1015,995,1010], "median_ms": 1007.5, "mean_ms": 1006.25, "p95_ms": 1013.75, "stddev_ms": 8.54, "dropped": ["warmup:1100"], "regress_threshold_pct": 8, "improve_threshold_pct": 5 },
    { "key": "warm-assemble", "n": 4, "samples_ms": [5000,5050,4950,5025], "median_ms": 5012.5, "mean_ms": 5006.25, "p95_ms": 5046.25, "stddev_ms": 42.7, "dropped": ["warmup:5400"], "regress_threshold_pct": 8, "improve_threshold_pct": 5 }
  ]
}
```

- [ ] **Step 2: Write the failing test**

Create `scripts/perf/compare-perf-test.mjs`:

```javascript
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
```

- [ ] **Step 3: Run the test to verify it fails**

Run:

```bash
node --test scripts/perf/compare-perf-test.mjs
```

Expected: FAIL (module not found).

- [ ] **Step 4: Write the comparator**

Create `scripts/perf/compare-perf.mjs`:

```javascript
import fs from "node:fs";

const ENV_KEYS = ["os", "arch", "cpu_model", "ram_mb", "jdk", "node"];

export function comparePerf(baseline, current) {
  const warnings = [];
  for (const key of ENV_KEYS) {
    if (baseline.env?.[key] !== current.env?.[key]) {
      warnings.push(`env.${key} differs: baseline=${baseline.env?.[key]} current=${current.env?.[key]}`);
    }
  }
  const baselineByKey = new Map(baseline.results.map((r) => [r.key, r]));
  const rows = [];
  const regressed = [];
  const improved = [];
  for (const cur of current.results) {
    const base = baselineByKey.get(cur.key);
    if (!base) {
      rows.push({ key: cur.key, verdict: "NEW", deltaMs: null, deltaPct: null });
      continue;
    }
    const deltaMs = cur.median_ms - base.median_ms;
    const deltaPct = (deltaMs / base.median_ms) * 100;
    const noiseBand = Math.max(2 * base.stddev_ms, 0.02 * base.median_ms);
    const regressThreshold = cur.regress_threshold_pct ?? base.regress_threshold_pct ?? 10;
    const improveThreshold = cur.improve_threshold_pct ?? base.improve_threshold_pct ?? 5;

    let verdict = "NEUTRAL";
    if (deltaMs > noiseBand && deltaPct > regressThreshold) {
      verdict = "REGRESS";
      regressed.push(cur.key);
    } else if (-deltaMs > noiseBand && -deltaPct >= improveThreshold) {
      verdict = "IMPROVE";
      improved.push(cur.key);
    }
    rows.push({
      key: cur.key,
      baselineMedianMs: base.median_ms,
      currentMedianMs: cur.median_ms,
      deltaMs: round(deltaMs),
      deltaPct: round(deltaPct),
      noiseBandMs: round(noiseBand),
      regressThresholdPct: regressThreshold,
      verdict,
    });
  }
  return { rows, regressed, improved, warnings, exitCode: regressed.length > 0 ? 1 : 0 };
}

export function renderMarkdown(result) {
  const lines = [];
  lines.push("| Scenario | Baseline (median) | Current (median) | Delta | Verdict |");
  lines.push("| --- | --- | --- | --- | --- |");
  for (const r of result.rows) {
    const verdictTag = r.verdict === "REGRESS" ? "❌ REGRESS" : r.verdict === "IMPROVE" ? "✅ IMPROVE" : r.verdict;
    const delta = r.deltaMs == null ? "—" : `${r.deltaMs >= 0 ? "+" : ""}${r.deltaMs} ms (${r.deltaPct >= 0 ? "+" : ""}${r.deltaPct}%)`;
    lines.push(`| ${r.key} | ${r.baselineMedianMs ?? "—"} ms | ${r.currentMedianMs ?? "—"} ms | ${delta} | ${verdictTag} |`);
  }
  if (result.warnings.length > 0) {
    lines.push("");
    lines.push("**Environment warnings:**");
    for (const w of result.warnings) lines.push(`- ${w}`);
  }
  return lines.join("\n") + "\n";
}

function round(v) {
  return Math.round(v * 100) / 100;
}

const isMain = import.meta.url === `file://${process.argv[1]}` || process.argv[1]?.endsWith("compare-perf.mjs");
if (isMain) {
  const [, , baselinePath, currentPath] = process.argv;
  if (!baselinePath || !currentPath) {
    console.error("Usage: compare-perf.mjs <baseline.json> <current.json>");
    process.exit(2);
  }
  const baseline = JSON.parse(fs.readFileSync(baselinePath, "utf8"));
  const current = JSON.parse(fs.readFileSync(currentPath, "utf8"));
  const result = comparePerf(baseline, current);
  process.stdout.write(renderMarkdown(result));
  process.exit(result.exitCode);
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run:

```bash
node --test scripts/perf/compare-perf-test.mjs
```

Expected: `# pass 5`.

- [ ] **Step 6: Smoke-test the CLI entry point**

Run:

```bash
node scripts/perf/compare-perf.mjs scripts/perf/__fixtures__/baseline-A.json scripts/perf/__fixtures__/current-regress.json
echo "exit=$?"
```

Expected: markdown table printed, last line `exit=1`.

Run:

```bash
node scripts/perf/compare-perf.mjs scripts/perf/__fixtures__/baseline-A.json scripts/perf/__fixtures__/current-improve.json
echo "exit=$?"
```

Expected: markdown table with `IMPROVE`, last line `exit=0`.

- [ ] **Step 7: Commit**

```bash
git add scripts/perf/compare-perf.mjs scripts/perf/compare-perf-test.mjs scripts/perf/__fixtures__/baseline-A.json scripts/perf/__fixtures__/current-regress.json scripts/perf/__fixtures__/current-improve.json scripts/perf/__fixtures__/current-neutral.json
git commit -m "perf: add comparator with regression/improve/neutral verdicts"
```

---

## Task 5: Environment fingerprint module

**Files:**
- Create: `scripts/perf/env-fingerprint.mjs`

- [ ] **Step 1: Implement fingerprint**

Create `scripts/perf/env-fingerprint.mjs`:

```javascript
import os from "node:os";
import { execSync } from "node:child_process";

export function captureEnv() {
  const cpus = os.cpus();
  return {
    os: process.platform,
    arch: process.arch,
    cpu_model: cpus[0]?.model ?? "unknown",
    cpu_count: cpus.length,
    ram_mb: Math.round(os.totalmem() / 1024 / 1024),
    jdk: safeExec("java -version 2>&1 | head -1") || "unknown",
    node: process.version,
  };
}

function safeExec(cmd) {
  try {
    return execSync(cmd, { stdio: ["ignore", "pipe", "pipe"] }).toString().trim();
  } catch {
    return null;
  }
}

const isMain = process.argv[1]?.endsWith("env-fingerprint.mjs");
if (isMain) {
  process.stdout.write(JSON.stringify(captureEnv(), null, 2) + "\n");
}
```

- [ ] **Step 2: Smoke check**

Run:

```bash
node scripts/perf/env-fingerprint.mjs
```

Expected: JSON object with `os`, `arch`, `cpu_model`, `ram_mb`, `jdk`, `node` keys; `jdk` contains something like `openjdk version "21"`.

- [ ] **Step 3: Commit**

```bash
git add scripts/perf/env-fingerprint.mjs
git commit -m "perf: capture environment fingerprint for runs"
```

---

## Task 6: Gradle scenario driver

**Files:**
- Create: `scripts/perf/bench-gradle.mjs`

- [ ] **Step 1: Implement the driver**

Create `scripts/perf/bench-gradle.mjs`:

```javascript
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { parseGradleProfile } from "./parse-gradle-profile.mjs";

const PROFILE_DIR = "build/reports/profile";

export function runGradleScenario(scenario, { repoRoot = process.cwd(), log = console.error } = {}) {
  const samples_ms = [];
  for (let i = 0; i < scenario.iterations + 1; i += 1) {
    if (scenario.mode === "cold") {
      log(`[bench-gradle] iter ${i}: clean`);
      const clean = spawnSync("./gradlew", ["clean", "--quiet"], { cwd: repoRoot, stdio: "inherit" });
      if (clean.status !== 0) throw new Error(`clean failed: status=${clean.status}`);
    }
    log(`[bench-gradle] iter ${i}: ${scenario.command.join(" ")}`);
    const before = listProfileFiles(repoRoot);
    // Run with --no-daemon so we measure the same startup + first-task cost
    // contributors hit via scripts/verify-ci-local.sh and the pre-push hook.
    const args = [...scenario.command, "--profile", "--no-daemon"];
    if (scenario.mode === "cold") args.push("--rerun-tasks", "--no-build-cache");
    const run = spawnSync("./gradlew", args, { cwd: repoRoot, stdio: "inherit" });
    if (run.status !== 0) throw new Error(`gradle failed: status=${run.status}`);
    const after = listProfileFiles(repoRoot);
    const fresh = after.find((f) => !before.includes(f));
    if (!fresh) throw new Error("no fresh profile HTML produced");
    const html = fs.readFileSync(path.join(repoRoot, PROFILE_DIR, fresh), "utf8");
    const { totalMs } = parseGradleProfile(html);
    samples_ms.push(totalMs);
    log(`[bench-gradle] iter ${i}: ${totalMs} ms`);
  }
  return samples_ms;
}

function listProfileFiles(repoRoot) {
  const dir = path.join(repoRoot, PROFILE_DIR);
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir);
}
```

- [ ] **Step 2: Smoke test against a fast scenario**

Run from repo root:

```bash
node --input-type=module -e "
import { runGradleScenario } from './scripts/perf/bench-gradle.mjs';
const samples = runGradleScenario({
  key: 'smoke',
  kind: 'gradle',
  command: [':fixthis-mcp:help'],
  mode: 'warm',
  iterations: 2,
});
console.log('samples_ms=', samples);
"
```

Expected: prints at least 3 numeric samples (1 warmup + 2 iterations). Each sample > 0.

- [ ] **Step 3: Commit**

```bash
git add scripts/perf/bench-gradle.mjs
git commit -m "perf: add Gradle scenario driver with profile-based timing"
```

---

## Task 7: Node scenario driver + orchestrator

**Files:**
- Create: `scripts/perf/bench-node.mjs`
- Create: `scripts/perf/bench.mjs`
- Modify: `package.json`
- Modify: `.gitignore`

- [ ] **Step 1: Implement the node driver**

Create `scripts/perf/bench-node.mjs`:

```javascript
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

export function runNodeScenario(scenario, { repoRoot = process.cwd(), log = console.error } = {}) {
  const groups = JSON.parse(fs.readFileSync(path.join(repoRoot, "scripts/console-tests.json"), "utf8"));
  const files = [];
  for (const g of scenario.groups) {
    if (!(g in groups)) throw new Error(`unknown console group: ${g}`);
    files.push(...groups[g]);
  }
  const samples_ms = [];
  for (let i = 0; i < scenario.iterations + 1; i += 1) {
    log(`[bench-node] iter ${i}: node --test (${files.length} files)`);
    const start = process.hrtime.bigint();
    const run = spawnSync(process.execPath, ["--test", ...files], { cwd: repoRoot, stdio: "inherit" });
    const elapsed = Number((process.hrtime.bigint() - start) / 1_000_000n);
    if (run.status !== 0) throw new Error(`node --test failed: status=${run.status}`);
    samples_ms.push(elapsed);
    log(`[bench-node] iter ${i}: ${elapsed} ms`);
  }
  return samples_ms;
}
```

- [ ] **Step 2: Implement the orchestrator**

Create `scripts/perf/bench.mjs`:

```javascript
#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { execSync } from "node:child_process";
import { aggregate } from "./aggregate.mjs";
import { captureEnv } from "./env-fingerprint.mjs";
import { runGradleScenario } from "./bench-gradle.mjs";
import { runNodeScenario } from "./bench-node.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, "../..");
const manifest = JSON.parse(fs.readFileSync(path.join(here, "perf-scenarios.json"), "utf8"));

const args = parseArgs(process.argv.slice(2));
const wanted = args.scenario ? manifest.scenarios.filter((s) => s.key === args.scenario) : manifest.scenarios;
if (wanted.length === 0) {
  console.error(`no scenarios match ${args.scenario}`);
  process.exit(2);
}

const results = [];
for (const scenario of wanted) {
  const effective = args.iterations ? { ...scenario, iterations: Number(args.iterations) } : scenario;
  console.error(`\n=== ${effective.key} (${effective.iterations} iterations, mode=${effective.mode}) ===`);
  const samples = effective.kind === "gradle"
    ? runGradleScenario(effective, { repoRoot })
    : runNodeScenario(effective, { repoRoot });
  const agg = aggregate(samples, { dropWarmup: true });
  results.push({
    key: effective.key,
    regress_threshold_pct: effective.regress_threshold_pct,
    improve_threshold_pct: effective.improve_threshold_pct,
    ...agg,
  });
}

const outDir = path.join(repoRoot, "output/perf");
fs.mkdirSync(outDir, { recursive: true });
const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
const outPath = path.join(outDir, `run-${timestamp}.json`);
const payload = {
  schema: 1,
  started_at: new Date().toISOString(),
  git_sha: safeExec("git rev-parse --short HEAD") || "unknown",
  env: captureEnv(),
  results,
};
fs.writeFileSync(outPath, JSON.stringify(payload, null, 2) + "\n");
console.error(`\nwrote ${outPath}`);

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === "--scenario") out.scenario = argv[++i];
    else if (argv[i] === "--iterations") out.iterations = argv[++i];
  }
  return out;
}

function safeExec(cmd) {
  try { return execSync(cmd, { stdio: ["ignore", "pipe", "pipe"] }).toString().trim(); }
  catch { return null; }
}
```

- [ ] **Step 3: Add npm scripts**

In `package.json`, locate the `"scripts"` block and add these entries (preserve any existing entries; insert alphabetically near other `perf:` / `console:` scripts):

```json
"perf:bench": "node scripts/perf/bench.mjs",
"perf:compare": "node scripts/perf/compare-perf.mjs",
"perf:test": "node --test scripts/perf/parse-gradle-profile-test.mjs scripts/perf/aggregate-test.mjs scripts/perf/compare-perf-test.mjs",
```

- [ ] **Step 4: Gitignore the runtime output dir**

In `.gitignore`, add (if not already present):

```
output/perf/
```

Verify it's there:

```bash
grep -c "output/perf/" .gitignore
```

Expected: prints `1`.

- [ ] **Step 5: Run the unit tests**

Run:

```bash
npm run perf:test
```

Expected: aggregated pass count = 15 (4 parser + 6 aggregator + 5 comparator). No failures.

- [ ] **Step 6: Smoke-test the orchestrator on the fastest scenario**

Run:

```bash
node scripts/perf/bench.mjs --scenario console-test-fast --iterations 2
```

Expected: console output shows `iter 0`, `iter 1`, `iter 2` lines; final line is `wrote output/perf/run-*.json`; the JSON file exists and contains `results[0].median_ms` as a number.

Inspect the latest output JSON:

```bash
ls -1t output/perf/ | head -1
node -e "
const fs = require('node:fs');
const path = require('node:path');
const latest = fs.readdirSync('output/perf').sort().pop();
const data = JSON.parse(fs.readFileSync(path.join('output/perf', latest), 'utf8'));
console.log('scenarios:', data.results.map((r) => r.key));
console.log('first result:', data.results[0]);
"
```

Expected: JSON with `results[0].key === "console-test-fast"` and a numeric `median_ms`.

- [ ] **Step 7: Commit**

```bash
git add scripts/perf/bench-node.mjs scripts/perf/bench.mjs package.json .gitignore
git commit -m "perf: add Node scenario driver and bench orchestrator"
```

---

## Task 8: Capture committed baseline from current main

**Files:**
- Create: `docs/perf/baseline-2026-05-16.json`

- [ ] **Step 1: Confirm working tree is clean and on `main`**

Run:

```bash
git status --short
git rev-parse --abbrev-ref HEAD
```

Expected: empty status output; `main` printed. If not clean, stash before continuing.

- [ ] **Step 2: Run the full bench (this is slow — 20–40 minutes)**

Run:

```bash
node scripts/perf/bench.mjs
```

Expected: each scenario reports per-iteration timings; final `wrote output/perf/run-<timestamp>.json`. If any scenario fails (e.g., disk full, network), re-run only that scenario:

```bash
node scripts/perf/bench.mjs --scenario <key>
```

- [ ] **Step 3: Copy the run JSON into `docs/perf/`**

Run:

```bash
mkdir -p docs/perf
cp "$(ls -t output/perf/run-*.json | head -1)" docs/perf/baseline-2026-05-16.json
```

- [ ] **Step 4: Sanity-check the baseline**

Run:

```bash
node -e "
const d = JSON.parse(require('node:fs').readFileSync('docs/perf/baseline-2026-05-16.json'));
for (const r of d.results) {
  if (!Number.isFinite(r.median_ms) || r.median_ms <= 0) throw new Error('bad median for ' + r.key);
  if (r.n < 3) throw new Error('too few samples for ' + r.key + ': ' + r.n);
}
console.log('baseline OK with', d.results.length, 'scenarios');
"
```

Expected: prints `baseline OK with 7 scenarios`. If a scenario has too few samples, re-run that scenario and re-copy.

- [ ] **Step 5: Self-compare (sanity)**

Run:

```bash
node scripts/perf/compare-perf.mjs docs/perf/baseline-2026-05-16.json docs/perf/baseline-2026-05-16.json
echo "exit=$?"
```

Expected: every row is `NEUTRAL` (delta is zero); last line `exit=0`.

- [ ] **Step 6: Commit**

```bash
git add docs/perf/baseline-2026-05-16.json
git commit -m "perf: capture committed baseline from main HEAD"
```

---

## Task 9: Proof-of-value — Gradle JVM heap tweak measured end-to-end

**Files:**
- Modify: `gradle.properties`

**Goal of this task:** prove the harness actually distinguishes signal from noise by trying one minimal config change and reporting the empirical outcome. The change ships only if the harness gate confirms ≥ 5% improvement on at least one scenario with no regressions.

- [ ] **Step 1: Re-capture a "current" run on main HEAD to estimate noise floor**

Run:

```bash
node scripts/perf/bench.mjs
cp "$(ls -t output/perf/run-*.json | head -1)" /tmp/perf-main-rerun.json
node scripts/perf/compare-perf.mjs docs/perf/baseline-2026-05-16.json /tmp/perf-main-rerun.json
echo "exit=$?"
```

Expected: every row `NEUTRAL`, `exit=0`. If any row shows `REGRESS` or `IMPROVE` despite no code change, the threshold is too tight for this hardware — raise both `regress_threshold_pct` and `improve_threshold_pct` by 2 in `scripts/perf/perf-scenarios.json` and re-baseline (Task 8 Steps 2–6). Do **not** proceed until two consecutive runs against the committed baseline are all `NEUTRAL`.

**Recalibration cap:** if a scenario still produces a spurious verdict after **two** threshold raises (i.e., the third no-change run still flags it), the scenario's hardware noise is too high to gate on. In `scripts/perf/perf-scenarios.json` raise its `regress_threshold_pct` and `improve_threshold_pct` to `100` for that scenario — at 100% the comparator will never trigger and the scenario becomes a measurement-only entry (its delta still appears in the markdown table; it just cannot fail the gate). Commit with a message noting the scenario is "advisory-only due to hardware noise floor", then proceed. Do not modify `compare-perf.mjs`.

- [ ] **Step 2: Apply the candidate change (4G heap)**

In `gradle.properties`, change:

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
```

to:

```properties
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
```

- [ ] **Step 3: Measure**

Run:

```bash
node scripts/perf/bench.mjs
cp "$(ls -t output/perf/run-*.json | head -1)" /tmp/perf-xmx-4g.json
node scripts/perf/compare-perf.mjs docs/perf/baseline-2026-05-16.json /tmp/perf-xmx-4g.json | tee /tmp/perf-xmx-4g-report.md
echo "exit=$?"
```

Expected one of:
- **At least one `IMPROVE`, no `REGRESS`**, exit=0 → proceed to Step 5.
- **No `IMPROVE`, no `REGRESS`** → 4G was insufficient signal; proceed to Step 4.
- **Any `REGRESS`** → 4G hurt something; proceed to Step 4 (and consider that the change is rejected even if you escalate to 6G).

- [ ] **Step 4: Escalate to 6G if 4G was insufficient signal**

Only if Step 3 produced no `IMPROVE` *and* no `REGRESS`. Edit `gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx6144m -Dfile.encoding=UTF-8
```

Re-measure:

```bash
node scripts/perf/bench.mjs
cp "$(ls -t output/perf/run-*.json | head -1)" /tmp/perf-xmx-6g.json
node scripts/perf/compare-perf.mjs docs/perf/baseline-2026-05-16.json /tmp/perf-xmx-6g.json | tee /tmp/perf-xmx-6g-report.md
echo "exit=$?"
```

Expected one of:
- **At least one `IMPROVE`, no `REGRESS`** → proceed to Step 5 with 6G as the final value.
- **No improvement OR any regression** → the change is empirically unjustified. Revert `gradle.properties` by replacing the line back to:

  ```properties
  org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
  ```

  Confirm the revert landed:

  ```bash
  git diff gradle.properties
  ```

  Expected: empty diff (the only line touched in this task is now back to baseline). Then go to Step 7.

- [ ] **Step 5: Document positive outcome and keep the change**

Append the markdown report into a PR-attached comment file:

```bash
mkdir -p docs/perf/evidence
cp /tmp/perf-xmx-4g-report.md docs/perf/evidence/2026-05-16-xmx-tuning.md 2>/dev/null \
  || cp /tmp/perf-xmx-6g-report.md docs/perf/evidence/2026-05-16-xmx-tuning.md
```

Edit `docs/perf/evidence/2026-05-16-xmx-tuning.md` and prepend:

```markdown
# Evidence: Gradle `-Xmx` tuning (2026-05-16)

**Change:** `org.gradle.jvmargs` `-Xmx2048m` → `-Xmx4096m` (or `-Xmx6144m`, fill in actual).

**Method:** Ran `scripts/perf/bench.mjs` against `docs/perf/baseline-2026-05-16.json`.

**Verdict:** Merge. See table below.

---

```

- [ ] **Step 6: Commit the change with evidence**

```bash
git add gradle.properties docs/perf/evidence/2026-05-16-xmx-tuning.md
git commit -m "perf(gradle): raise -Xmx based on harness evidence

See docs/perf/evidence/2026-05-16-xmx-tuning.md for the median/p95 deltas."
```

- [ ] **Step 7: Document negative outcome if the change was reverted**

Only if you reverted in Step 4. Create `docs/perf/evidence/2026-05-16-xmx-tuning-negative.md`:

```markdown
# Evidence: Gradle `-Xmx` tuning rejected (2026-05-16)

Tried `-Xmx4096m` and `-Xmx6144m` against `docs/perf/baseline-2026-05-16.json`.
Neither produced ≥ 5% median improvement; one or both produced regressions
or all-neutral noise. Recommendation: keep `-Xmx2048m` until a different
optimization (Kotlin daemon args, AGP build features) creates a heap
ceiling pressure that justifies revisiting.

Raw reports preserved at:
- `/tmp/perf-xmx-4g-report.md` (not committed)
- `/tmp/perf-xmx-6g-report.md` (not committed, if applicable)
```

```bash
git add docs/perf/evidence/2026-05-16-xmx-tuning-negative.md
git commit -m "perf: document rejected -Xmx tuning attempt with evidence"
```

The harness still ships with empirical proof of its gate — a negative result is a successful outcome of this plan.

---

## Task 10: CI workflow for perf reports

**Files:**
- Create: `.github/workflows/perf-report.yml`

- [ ] **Step 1: Write the workflow**

Create `.github/workflows/perf-report.yml`:

```yaml
name: Perf report

on:
  workflow_dispatch:
    inputs:
      scenario:
        description: "Scenario key (empty = all)"
        required: false
        default: ""
      iterations:
        description: "Override iterations (empty = manifest default)"
        required: false
        default: ""
  schedule:
    # Nightly at 04:30 UTC. Mirror connected-tests.yml cadence so reports
    # land in the same nightly review window.
    - cron: "30 4 * * *"

jobs:
  perf:
    runs-on: ubuntu-latest
    timeout-minutes: 90
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
      - uses: actions/setup-node@v4
        with:
          node-version: "20"
      - run: npm ci
      - name: Run harness
        run: |
          if [ -n "${{ github.event.inputs.scenario }}" ]; then
            node scripts/perf/bench.mjs --scenario "${{ github.event.inputs.scenario }}" ${{ github.event.inputs.iterations && format('--iterations {0}', github.event.inputs.iterations) || '' }}
          else
            node scripts/perf/bench.mjs
          fi
      - name: Compare against committed baseline
        id: compare
        run: |
          latest=$(ls -t output/perf/run-*.json | head -1)
          node scripts/perf/compare-perf.mjs docs/perf/baseline-2026-05-16.json "$latest" | tee /tmp/perf-report.md
          echo "exit=$?" >> "$GITHUB_OUTPUT"
        continue-on-error: true
      - name: Post markdown summary
        if: always()
        run: |
          {
            echo "# Perf report"
            echo
            cat /tmp/perf-report.md 2>/dev/null || echo "(no report produced)"
          } >> "$GITHUB_STEP_SUMMARY"
      - name: Upload JSON artifact
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: perf-${{ github.run_id }}
          path: output/perf/
          retention-days: 30
      - name: Fail on regression
        if: steps.compare.outputs.exit != '0'
        run: |
          echo "::error::Perf regression detected — see job summary."
          exit 1
```

- [ ] **Step 2: Lint the YAML**

Run:

```bash
node -e "
const yaml = require('node:fs').readFileSync('.github/workflows/perf-report.yml', 'utf8');
if (!yaml.includes('workflow_dispatch')) throw 'missing workflow_dispatch';
if (!yaml.includes('GITHUB_STEP_SUMMARY')) throw 'missing summary post';
if (!yaml.includes('docs/perf/baseline-2026-05-16.json')) throw 'missing baseline reference';
console.log('OK');
"
```

Expected: prints `OK`.

If the repo has `actionlint` available, also run:

```bash
which actionlint && actionlint .github/workflows/perf-report.yml || echo "actionlint not installed, skipping"
```

Expected: no errors from actionlint, or the not-installed fallback message.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/perf-report.yml
git commit -m "perf(ci): add nightly + manual perf-report workflow"
```

---

## Task 11: Document the workflow in CONTRIBUTING.md

**Files:**
- Modify: `CONTRIBUTING.md`

- [ ] **Step 1: Add a Performance Measurement section**

In `CONTRIBUTING.md`, find the line `## Local Artifacts` (near the end). Insert a new section **immediately before** it:

```markdown
## Performance Measurement

The `scripts/perf/` harness measures Gradle and console JS scenario
wall-clock times and compares them against a committed baseline. See
[`scripts/perf/README.md`](scripts/perf/README.md) for details and
[`docs/superpowers/specs/2026-05-16-build-test-performance-measurement-design.md`](docs/superpowers/specs/2026-05-16-build-test-performance-measurement-design.md)
for the design.

Typical loop when proposing a build/test config change:

```bash
# 1. Confirm main is clean on your machine (no rogue REGRESS/IMPROVE).
node scripts/perf/bench.mjs
node scripts/perf/compare-perf.mjs docs/perf/baseline-2026-05-16.json "$(ls -t output/perf/run-*.json | head -1)"

# 2. Apply your change, then re-measure.
node scripts/perf/bench.mjs
node scripts/perf/compare-perf.mjs docs/perf/baseline-2026-05-16.json "$(ls -t output/perf/run-*.json | head -1)"

# 3. Attach the comparator output to the PR description.
```

Re-baseline (`docs/perf/baseline-2026-05-16.json`) only when adopting a
deliberate, reviewed change. Hardware variance between contributors is
expected and the comparator warns rather than fails on environment
mismatch — CI uses its own nightly run as the authoritative baseline.

```

- [ ] **Step 2: Verify the doc consistency check still passes**

Run:

```bash
node scripts/check-doc-consistency.mjs
```

Expected: exits 0 with no `FAIL` lines.

If it complains about an unreferenced script, add a one-line mention in the new section linking to `scripts/perf/README.md` (already present) and re-run.

- [ ] **Step 3: Commit**

```bash
git add CONTRIBUTING.md
git commit -m "docs: document performance measurement loop in CONTRIBUTING"
```

---

## Task 12: Final end-to-end verification

**Files:** none (verification only).

- [ ] **Step 1: Confirm all unit tests pass**

Run:

```bash
npm run perf:test
```

Expected: 15 passing tests, 0 failures.

- [ ] **Step 2: Confirm the fast scenario runs end-to-end**

Run:

```bash
node scripts/perf/bench.mjs --scenario console-test-fast --iterations 2
node scripts/perf/compare-perf.mjs docs/perf/baseline-2026-05-16.json "$(ls -t output/perf/run-*.json | head -1)"
echo "exit=$?"
```

Expected: markdown table prints; `console-test-fast` row has a verdict; exit code matches verdict (0 unless regression).

- [ ] **Step 3: Confirm the negative-path test (REGRESS) still gates**

Run:

```bash
node scripts/perf/compare-perf.mjs scripts/perf/__fixtures__/baseline-A.json scripts/perf/__fixtures__/current-regress.json
echo "exit=$?"
```

Expected: `exit=1`.

- [ ] **Step 4: Confirm CI workflow YAML parses on push (push to a topic branch and observe Actions)**

Push the branch:

```bash
git push -u origin <topic-branch>
```

Then in the GitHub UI navigate to the Actions tab and trigger
`Perf report` via `Run workflow` on the topic branch. The job should
either succeed (NEUTRAL/IMPROVE) or fail with `Perf regression
detected` (REGRESS). In either case the job summary should show the
markdown table from Task 4.

If you cannot push to remote in this environment, document the
required follow-up in the PR description and skip the live verification.

- [ ] **Step 5: Run the standard local check to ensure no regression to existing gates**

Run:

```bash
npm run ci:local:changed
```

Expected: exits 0. If failures appear in unrelated checks, investigate (do not bypass).

- [ ] **Step 6: Self-review**

Open the diff and confirm:

- [ ] No `--no-daemon` flag was added to or removed from any script.
- [ ] No existing test was modified.
- [ ] No public Kotlin file was modified.
- [ ] `gradle.properties` was modified **only** by Task 9 and **only** if the harness confirmed improvement.
- [ ] `docs/perf/baseline-2026-05-16.json` is present and committed.
- [ ] `output/perf/` is in `.gitignore`.

If any item fails, fix before opening the PR.

---

## Plan completion criteria

This plan is done when:

1. `npm run perf:test` shows 15 passing tests.
2. `node scripts/perf/bench.mjs --scenario console-test-fast --iterations 2` writes a JSON report and the comparator agrees with `docs/perf/baseline-2026-05-16.json` (`NEUTRAL` for unchanged code).
3. `docs/perf/baseline-2026-05-16.json` is committed with at least 7 scenarios, each ≥ 3 samples.
4. `.github/workflows/perf-report.yml` exists and contains a `workflow_dispatch` trigger plus a step that calls `compare-perf.mjs` and posts to `$GITHUB_STEP_SUMMARY`.
5. `CONTRIBUTING.md` contains the new "Performance Measurement" section.
6. Either Task 9 Step 6 (positive evidence) or Task 9 Step 7 (negative evidence) was committed.
7. `npm run ci:local:changed` passes after every Task that touched JS or Gradle config.
