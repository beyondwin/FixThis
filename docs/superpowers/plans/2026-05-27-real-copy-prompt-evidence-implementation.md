# Real Copy Prompt Evidence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a local connected-device smoke that validates real Copy Prompt behavior across Reply, Jetsnack, and the bundled FixThis sample app.

**Architecture:** Add one Node/Playwright orchestrator that reuses the existing runtime fixture preparation path, opens a real MCP feedback console per app, creates two annotations, clicks Copy Prompt, and writes local reports. Keep pure parsing, prompt assertion, fixture filtering, and report aggregation unit-tested without Android. Register the connected-device command as an optional deferrable `evidence:trust` step.

**Tech Stack:** Node.js 20 ESM, `node:test`, Playwright, Gradle install tasks, FixThis MCP stdio server, existing `scripts/source-matching-fixtures.mjs` runtime fixture helpers.

---

Source design spec: `docs/superpowers/specs/2026-05-27-real-copy-prompt-evidence-design.md`

Execution status: implemented and merged to `main` on 2026-05-27. The final
risk-closure pass added stale Android SDK environment normalization, README and
CHANGELOG coverage, and post-merge strict trust verification on `emulator-5554`.

## File Structure

- Create `scripts/real-copy-prompt-smoke.mjs`
  - Owns command-line parsing, Android preflight status, runtime fixture selection, MCP stdio calls, Playwright workflow, prompt/session assertions, and report writing.
  - Exports pure helpers for unit tests.
- Create `scripts/real-copy-prompt-smoke-test.mjs`
  - Tests pure helpers with no Android, no browser, and no Gradle.
- Modify `scripts/source-matching-fixtures.mjs`
  - Add `runtimeFixtures()` and `installRuntimeFixture()` exports.
  - Refactor `runRuntimeTrustEvaluation()` to use `installRuntimeFixture()` so the new smoke and existing runtime trust path share install behavior.
- Modify `scripts/source-matching-fixtures-test.mjs`
  - Test runtime fixture filtering and injected install command ordering.
- Modify `scripts/evidence-runner.mjs`
  - Add `Real copy prompt smoke` to the `trust` profile as `requiresAndroid: true` and `deferrable: true`.
- Modify `scripts/evidence-runner-test.mjs`
  - Assert the new trust-profile step is present and deferrable.
- Modify `package.json`
  - Add `real-copy-prompt:smoke` and `real-copy-prompt:smoke:test`.
- Modify `CONTRIBUTING.md`
  - Document the command under local evidence profiles and connected-device checks.
- Modify `docs/reference/feedback-console-contract.md`
  - Clarify that Copy Prompt records `lastHandedOffAtEpochMillis` but does not require `delivery=sent`.
- Modify `docs/reference/mcp-tools.md`
  - Clarify Copy Prompt versus Save to MCP semantics for agents.

## Implementation Tasks

### Task 1: Add Pure Smoke Helper Tests And Script Skeleton

**Files:**
- Create: `scripts/real-copy-prompt-smoke-test.mjs`
- Create: `scripts/real-copy-prompt-smoke.mjs`
- Modify: `package.json`

- [ ] **Step 1: Add the package scripts**

Edit `package.json` inside the `"scripts"` object near the source-matching scripts:

```json
"real-copy-prompt:smoke": "node scripts/real-copy-prompt-smoke.mjs",
"real-copy-prompt:smoke:test": "node --test scripts/real-copy-prompt-smoke-test.mjs",
```

- [ ] **Step 2: Write the failing unit tests**

Create `scripts/real-copy-prompt-smoke-test.mjs`:

```js
import assert from "node:assert/strict";
import test from "node:test";
import {
  assertCopiedPrompt,
  buildReport,
  parseArgs,
  renderMarkdownReport,
  selectRuntimeFixtures,
  statusForEnvironment,
  summarizeApps,
} from "./real-copy-prompt-smoke.mjs";

const manifest = {
  fixtures: [
    {
      id: "reply",
      applicationId: "com.example.reply",
      cases: [{ id: "reply-runtime", mode: "runtime-trust", runtimeTarget: { text: "Inbox" } }],
    },
    {
      id: "jetsnack",
      applicationId: "com.example.jetsnack",
      cases: [{ id: "jetsnack-runtime", mode: "runtime-trust", runtimeTarget: { text: "Home" } }],
    },
    {
      id: "fixthis-sample",
      applicationId: "io.github.beyondwin.fixthis.sample",
      cases: [{ id: "sample-runtime", mode: "runtime-trust", runtimeTarget: { text: "FixThis" } }],
    },
    {
      id: "nowinandroid",
      applicationId: "com.google.samples.apps.nowinandroid",
      cases: [{ id: "nia-source", mode: "source-index", expectedTop1PathContains: "Topic" }],
    },
  ],
};

test("parseArgs supports strict, fixture subset, report dir, and headed mode", () => {
  assert.deepEqual(
    parseArgs(["--fixtures", "reply,jetsnack", "--strict", "--report-dir", "out/report", "--headed"]),
    {
      strict: true,
      fixtureIds: ["reply", "jetsnack"],
      reportDir: "out/report",
      headed: true,
    },
  );
});

test("selectRuntimeFixtures defaults to the three real copy-prompt apps", () => {
  const selected = selectRuntimeFixtures(manifest, []);
  assert.deepEqual(selected.map((fixture) => fixture.id), ["reply", "jetsnack", "fixthis-sample"]);
});

test("selectRuntimeFixtures rejects source-index-only and unknown ids", () => {
  assert.throws(() => selectRuntimeFixtures(manifest, ["nowinandroid"]), /nowinandroid is not an installable runtime Copy Prompt fixture/);
  assert.throws(() => selectRuntimeFixtures(manifest, ["missing"]), /Unknown fixture id: missing/);
});

test("assertCopiedPrompt checks comments, id lines, quality, and agent protocol", () => {
  const markdown = [
    "agent_protocol: fixthis-feedback/v1",
    "Handoff quality: high",
    "- id: ann-1",
    "  comment: Reply copy prompt smoke annotation 1",
    "- id: ann-2",
    "  comment: Reply copy prompt smoke annotation 2",
  ].join("\n");

  const result = assertCopiedPrompt(markdown, [
    "Reply copy prompt smoke annotation 1",
    "Reply copy prompt smoke annotation 2",
  ]);

  assert.equal(result.idLineCount, 2);
  assert.equal(result.promptChars, markdown.length);
});

test("assertCopiedPrompt reports every missing marker", () => {
  assert.throws(
    () => assertCopiedPrompt("comment only", ["expected comment"]),
    /missing comment: expected comment.*expected at least 2 id lines.*missing Handoff quality.*missing agent_protocol/s,
  );
});

test("summarizeApps and buildReport aggregate pass fail and deferred rows", () => {
  const apps = [
    { fixtureId: "reply", status: "pass" },
    { fixtureId: "jetsnack", status: "fail", failures: ["preview_timeout"] },
    { fixtureId: "fixthis-sample", status: "deferred" },
  ];

  assert.deepEqual(summarizeApps(apps), {
    totalApps: 3,
    passedApps: 1,
    failedApps: 1,
    deferredApps: 1,
  });

  const report = buildReport({
    strict: false,
    device: "emulator-5554",
    startedAt: "2026-05-27T00:00:00.000Z",
    finishedAt: "2026-05-27T00:01:00.000Z",
    apps,
  });

  assert.equal(report.status, "fail");
  assert.equal(report.failures[0], "jetsnack: preview_timeout");
});

test("statusForEnvironment distinguishes strict failure from non-strict deferral", () => {
  assert.deepEqual(statusForEnvironment({ strict: false, androidReady: false, reason: "No connected Android device." }), {
    status: "deferred",
    exitCode: 0,
    failures: ["No connected Android device."],
  });
  assert.deepEqual(statusForEnvironment({ strict: true, androidReady: false, reason: "No connected Android device." }), {
    status: "fail",
    exitCode: 1,
    failures: ["No connected Android device."],
  });
});

test("renderMarkdownReport includes app evidence rows", () => {
  const text = renderMarkdownReport({
    status: "pass",
    startedAt: "2026-05-27T00:00:00.000Z",
    finishedAt: "2026-05-27T00:01:00.000Z",
    strict: true,
    device: "emulator-5554",
    summary: { totalApps: 1, passedApps: 1, failedApps: 0, deferredApps: 0 },
    apps: [{
      fixtureId: "reply",
      packageName: "com.example.reply",
      status: "pass",
      itemCount: 2,
      idLineCount: 2,
      handedOffCount: 2,
      promptPath: "artifacts/reply-prompt.md",
    }],
    failures: [],
  });

  assert.match(text, /# FixThis Real Copy Prompt Smoke/);
  assert.match(text, /\| reply \| com\.example\.reply \| pass \| 2 \| 2 \| 2 \| artifacts\/reply-prompt\.md \|/);
});
```

- [ ] **Step 3: Run the test and verify it fails**

Run:

```bash
npm run real-copy-prompt:smoke:test
```

Expected: FAIL because `scripts/real-copy-prompt-smoke.mjs` does not exist or does not export the tested helpers.

- [ ] **Step 4: Add the initial script with pure helpers**

Create `scripts/real-copy-prompt-smoke.mjs`:

```js
#!/usr/bin/env node
import { mkdirSync, realpathSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

export const repoRoot = fileURLToPath(new URL("..", import.meta.url));
export const defaultReportDir = join(repoRoot, "build/reports/fixthis-real-copy-prompt");
export const defaultFixtureIds = ["reply", "jetsnack", "fixthis-sample"];

export function parseArgs(argv = process.argv.slice(2)) {
  const result = {
    strict: false,
    fixtureIds: [],
    reportDir: defaultReportDir,
    headed: false,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--strict") {
      result.strict = true;
    } else if (arg === "--headed") {
      result.headed = true;
    } else if (arg === "--fixtures") {
      const value = argv[++index];
      if (!value) throw new Error("--fixtures requires a comma-separated fixture list");
      result.fixtureIds = value.split(",").map((entry) => entry.trim()).filter(Boolean);
    } else if (arg === "--report-dir") {
      const value = argv[++index];
      if (!value) throw new Error("--report-dir requires a path");
      result.reportDir = value;
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  return result;
}

function hasRuntimeTrustCase(fixture) {
  return (fixture.cases || []).some((testCase) => testCase.mode === "runtime-trust");
}

export function selectRuntimeFixtures(manifest, fixtureIds = []) {
  const ids = fixtureIds.length > 0 ? fixtureIds : defaultFixtureIds;
  const byId = new Map((manifest.fixtures || []).map((fixture) => [fixture.id, fixture]));
  return ids.map((id) => {
    const fixture = byId.get(id);
    if (!fixture) throw new Error(`Unknown fixture id: ${id}`);
    if (!defaultFixtureIds.includes(id) || !hasRuntimeTrustCase(fixture)) {
      throw new Error(`${id} is not an installable runtime Copy Prompt fixture`);
    }
    return fixture;
  });
}

export function assertCopiedPrompt(markdown, expectedComments) {
  const failures = [];
  for (const comment of expectedComments) {
    if (!markdown.includes(comment)) failures.push(`missing comment: ${comment}`);
  }
  const idLineCount = (markdown.match(/(^|\\n)\\s*-?\\s*id:/g) || []).length;
  if (idLineCount < 2) failures.push(`expected at least 2 id lines, found ${idLineCount}`);
  if (!markdown.includes("Handoff quality:")) failures.push("missing Handoff quality");
  if (!markdown.includes("agent_protocol:")) failures.push("missing agent_protocol");
  if (failures.length > 0) throw new Error(failures.join("; "));
  return {
    idLineCount,
    promptChars: markdown.length,
  };
}

export function summarizeApps(apps) {
  return {
    totalApps: apps.length,
    passedApps: apps.filter((app) => app.status === "pass").length,
    failedApps: apps.filter((app) => app.status === "fail").length,
    deferredApps: apps.filter((app) => app.status === "deferred").length,
  };
}

export function buildReport({ strict, device = null, startedAt, finishedAt, apps }) {
  const summary = summarizeApps(apps);
  const failures = apps.flatMap((app) => (app.failures || []).map((failure) => `${app.fixtureId}: ${failure}`));
  const status = summary.failedApps > 0 ? "fail" : summary.deferredApps === summary.totalApps ? "deferred" : "pass";
  return {
    status,
    startedAt,
    finishedAt,
    strict,
    device,
    summary,
    apps,
    failures,
  };
}

export function statusForEnvironment({ strict, androidReady, reason }) {
  if (androidReady) return { status: "pass", exitCode: 0, failures: [] };
  return {
    status: strict ? "fail" : "deferred",
    exitCode: strict ? 1 : 0,
    failures: [reason || "Android environment is unavailable."],
  };
}

export function renderMarkdownReport(report) {
  const lines = [
    "# FixThis Real Copy Prompt Smoke",
    "",
    `- Status: ${report.status}`,
    `- Strict: ${report.strict}`,
    `- Device: ${report.device || "unavailable"}`,
    `- Started: ${report.startedAt}`,
    `- Finished: ${report.finishedAt}`,
    "",
    "| Fixture | Package | Status | Items | IDs | Handed off | Prompt |",
    "|---|---|---:|---:|---:|---:|---|",
  ];
  for (const app of report.apps) {
    lines.push(`| ${app.fixtureId} | ${app.packageName || ""} | ${app.status} | ${app.itemCount || 0} | ${app.idLineCount || 0} | ${app.handedOffCount || 0} | ${app.promptPath || ""} |`);
  }
  if (report.failures.length > 0) {
    lines.push("", "## Failures", "");
    for (const failure of report.failures) lines.push(`- ${failure}`);
  }
  return `${lines.join("\n")}\n`;
}

export function writeReports(report, reportDir) {
  mkdirSync(reportDir, { recursive: true });
  writeFileSync(join(reportDir, "report.json"), `${JSON.stringify(report, null, 2)}\n`);
  writeFileSync(join(reportDir, "report.md"), renderMarkdownReport(report));
}

export async function main(argv = process.argv.slice(2)) {
  parseArgs(argv);
  console.error("real-copy-prompt smoke runtime workflow is added in the following tasks.");
  return 2;
}

const invokedAsCli = process.argv[1]
  ? fileURLToPath(import.meta.url) === realpathSync(process.argv[1])
  : false;

if (invokedAsCli) {
  process.exitCode = await main();
}
```

- [ ] **Step 5: Run the unit test and verify it passes**

Run:

```bash
npm run real-copy-prompt:smoke:test
```

Expected: PASS.

- [ ] **Step 6: Commit Task 1**

```bash
git add package.json scripts/real-copy-prompt-smoke.mjs scripts/real-copy-prompt-smoke-test.mjs
git commit -m "test: add real copy prompt smoke contracts"
```

### Task 2: Share Runtime Fixture Install Helpers

**Files:**
- Modify: `scripts/source-matching-fixtures.mjs`
- Modify: `scripts/source-matching-fixtures-test.mjs`

- [ ] **Step 1: Extend the fixture test imports**

In `scripts/source-matching-fixtures-test.mjs`, add `installRuntimeFixture` and `runtimeFixtures` to the existing import list:

```js
  installRuntimeFixture,
  runtimeFixtures,
```

- [ ] **Step 2: Add failing helper tests**

Append these tests near the existing runtime fixture tests:

```js
test("runtimeFixtures returns only installable runtime-trust fixtures in manifest order", () => {
  const manifest = {
    fixtures: [
      { id: "source-only", cases: [{ id: "source", mode: "source-index" }] },
      { id: "runtime-a", cases: [{ id: "runtime", mode: "runtime-trust", runtimeTarget: { text: "A" } }] },
      { id: "runtime-b", cases: [{ id: "runtime", mode: "runtime-trust", runtimeTarget: { text: "B" } }] },
    ],
  };

  assert.deepEqual(runtimeFixtures(manifest).map((fixture) => fixture.id), ["runtime-a", "runtime-b"]);
});

test("installRuntimeFixture prepares runtime source index before installing debug app", () => {
  const calls = [];
  const fixture = {
    id: "reply",
    modulePath: ":app",
    variant: "debug",
    applicationId: "com.example.reply",
    cases: [{ id: "runtime", mode: "runtime-trust", runtimeTarget: { text: "Inbox" } }],
  };
  const paths = { projectWorkDir: "/tmp/reply" };

  const result = installRuntimeFixture(fixture, {
    prepare: (input, options) => {
      calls.push({ type: "prepare", fixtureId: input.id, addDebugRuntime: options.addDebugRuntime });
      return paths;
    },
    run: (command, args, options) => {
      calls.push({ type: "run", command, args, cwd: options.cwd, stdio: options.stdio });
    },
    stdio: "pipe",
  });

  assert.equal(result.projectWorkDir, "/tmp/reply");
  assert.deepEqual(calls, [
    { type: "prepare", fixtureId: "reply", addDebugRuntime: true },
    {
      type: "run",
      command: "./gradlew",
      args: [":app:generateDebugFixThisSourceIndex", "-Pfixthis.runtimeCompatibleSourceIndex=true", "--no-daemon"],
      cwd: "/tmp/reply",
      stdio: "pipe",
    },
    {
      type: "run",
      command: "./gradlew",
      args: [":app:installDebug", "-Pfixthis.runtimeCompatibleSourceIndex=true", "--no-daemon"],
      cwd: "/tmp/reply",
      stdio: "pipe",
    },
  ]);
});
```

- [ ] **Step 3: Run the source fixture tests and verify they fail**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: FAIL because `runtimeFixtures` and `installRuntimeFixture` are not exported yet.

- [ ] **Step 4: Add the helper exports**

In `scripts/source-matching-fixtures.mjs`, replace the private `hasRuntimeCases` function with exported helper functions:

```js
export function hasRuntimeCases(fixture) {
  return (fixture.cases || []).some((testCase) => testCase.mode === "runtime-trust");
}

export function runtimeFixtures(manifest) {
  return (manifest.fixtures || []).filter(hasRuntimeCases);
}

export function installRuntimeFixture(fixture, options = {}) {
  const prepare = options.prepare || prepareFixture;
  const run = options.run || runCommand;
  const stdio = options.stdio || "inherit";
  const paths = prepare(fixture, { stdio, addDebugRuntime: true });
  run("./gradlew", runtimeSourceIndexGradleArgs(fixture), { cwd: paths.projectWorkDir, stdio });
  run("./gradlew", runtimeInstallGradleArgs(fixture), { cwd: paths.projectWorkDir, stdio });
  return paths;
}
```

Then update `runRuntimeTrustEvaluation()` so it shares the same helper:

```js
export function runRuntimeTrustEvaluation(fixture, options = {}) {
  const strict = options.strict === true;
  const paths = installRuntimeFixture(fixture, { stdio: "inherit" });

  const inputPath = join(defaultReportDir, `${fixture.id}-runtime-input.json`);
  const outputPath = join(defaultReportDir, `${fixture.id}-runtime-output.json`);
  writeJson(inputPath, runtimeFixtureInput(fixture, paths.projectWorkDir, strict));
  const args = runtimeTrustFixtureGradleArgs(inputPath, outputPath, strict);
  runCommand("./gradlew", args, { cwd: repoRoot, stdio: "pipe" });
  const runnerOutput = JSON.parse(readFileSync(outputPath, "utf8"));
  return evaluateRuntimeTrustFixture(fixture, runnerOutput);
}
```

Finally, in `main()`, change the runtime loop to use `runtimeFixtures(manifest)`:

```js
for (const fixture of runtimeFixtures(manifest)) {
```

- [ ] **Step 5: Run the tests and verify they pass**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: PASS.

- [ ] **Step 6: Commit Task 2**

```bash
git add scripts/source-matching-fixtures.mjs scripts/source-matching-fixtures-test.mjs
git commit -m "refactor: share runtime fixture install helper"
```

### Task 3: Add Smoke Runner Orchestration Without Browser Actions

**Files:**
- Modify: `scripts/real-copy-prompt-smoke.mjs`
- Modify: `scripts/real-copy-prompt-smoke-test.mjs`

- [ ] **Step 1: Add tests for session state assertions**

Add `assertSessionHandedOffItems` to the existing import list in `scripts/real-copy-prompt-smoke-test.mjs`, then append this test:

```js
test("assertSessionHandedOffItems counts copied items with last handoff timestamps", () => {
  const session = {
    items: [
      { itemId: "ann-1", comment: "First", lastHandedOffAtEpochMillis: 100 },
      { itemId: "ann-2", comment: "Second", lastHandedOffAtEpochMillis: 101 },
      { itemId: "ann-3", comment: "Pin only" },
    ],
  };

  assert.deepEqual(assertSessionHandedOffItems(session, ["ann-1", "ann-2"]), {
    itemCount: 3,
    handedOffCount: 2,
  });
});
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
npm run real-copy-prompt:smoke:test
```

Expected: FAIL because `assertSessionHandedOffItems` is not exported.

- [ ] **Step 3: Add runtime imports and assertion helper**

Update the import block in `scripts/real-copy-prompt-smoke.mjs`:

```js
import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import { chromium } from "playwright";
import {
  installRuntimeFixture,
  loadManifest,
  runCommand,
} from "./source-matching-fixtures.mjs";
import { resolveAndroidEnvironment } from "./evidence-runner.mjs";
```

Add this helper below `assertCopiedPrompt()`:

```js
export function assertSessionHandedOffItems(session, itemIds) {
  const items = Array.isArray(session?.items) ? session.items : [];
  const selected = itemIds.length > 0
    ? items.filter((item) => itemIds.includes(item.itemId))
    : items;
  const handedOffCount = selected.filter((item) => Number.isFinite(item.lastHandedOffAtEpochMillis)).length;
  if (selected.length < 2) throw new Error(`expected at least 2 persisted items, found ${selected.length}`);
  if (handedOffCount < 2) throw new Error(`expected at least 2 copied items with lastHandedOffAtEpochMillis, found ${handedOffCount}`);
  return {
    itemCount: items.length,
    handedOffCount,
  };
}
```

- [ ] **Step 4: Add Android preflight and fixture selection orchestration**

Add these functions to `scripts/real-copy-prompt-smoke.mjs`:

```js
function androidDeviceName(environment) {
  return environment.device || environment.serial || null;
}

function relativeArtifactPath(reportDir, path) {
  return path.startsWith(reportDir) ? path.slice(reportDir.length + 1) : path;
}

async function ensureMcpDistribution() {
  runCommand("./gradlew", [":fixthis-mcp:installDist", "--no-daemon"], { cwd: repoRoot, stdio: "inherit" });
  return join(repoRoot, "fixthis-mcp/build/install/fixthis-mcp/bin/fixthis-mcp");
}

async function runSelectedFixture(fixture, options) {
  const app = {
    fixtureId: fixture.id,
    packageName: fixture.applicationId,
    status: "fail",
    failures: [],
  };
  try {
    const paths = installRuntimeFixture(fixture, { stdio: "inherit" });
    app.projectDir = paths.projectWorkDir;
    app.status = "pass";
    return app;
  } catch (error) {
    app.failures.push(error.message);
    return app;
  }
}

export async function runSmoke(options) {
  const startedAt = new Date().toISOString();
  const environment = resolveAndroidEnvironment();
  const preflight = statusForEnvironment({
    strict: options.strict,
    androidReady: environment.ready,
    reason: environment.reason,
  });
  if (!environment.ready) {
    const finishedAt = new Date().toISOString();
    const report = {
      status: preflight.status,
      startedAt,
      finishedAt,
      strict: options.strict,
      device: null,
      summary: { totalApps: 0, passedApps: 0, failedApps: preflight.status === "fail" ? 1 : 0, deferredApps: preflight.status === "deferred" ? 1 : 0 },
      apps: [],
      failures: preflight.failures,
    };
    writeReports(report, options.reportDir);
    return { report, exitCode: preflight.exitCode };
  }

  await ensureMcpDistribution();
  const manifest = loadManifest();
  const fixtures = selectRuntimeFixtures(manifest, options.fixtureIds);
  const apps = [];
  for (const fixture of fixtures) {
    apps.push(await runSelectedFixture(fixture, { ...options, environment }));
  }
  const report = buildReport({
    strict: options.strict,
    device: androidDeviceName(environment),
    startedAt,
    finishedAt: new Date().toISOString(),
    apps,
  });
  writeReports(report, options.reportDir);
  return { report, exitCode: report.status === "fail" ? 1 : 0 };
}
```

Update `main()`:

```js
export async function main(argv = process.argv.slice(2)) {
  const options = parseArgs(argv);
  const { report, exitCode } = await runSmoke(options);
  console.log(renderMarkdownReport(report));
  return exitCode;
}
```

- [ ] **Step 5: Run the unit test**

Run:

```bash
npm run real-copy-prompt:smoke:test
```

Expected: PASS.

- [ ] **Step 6: Commit Task 3**

```bash
git add scripts/real-copy-prompt-smoke.mjs scripts/real-copy-prompt-smoke-test.mjs
git commit -m "feat: add real copy prompt smoke orchestration"
```

### Task 4: Implement MCP Stdio Tool Calls

**Files:**
- Modify: `scripts/real-copy-prompt-smoke.mjs`
- Modify: `scripts/real-copy-prompt-smoke-test.mjs`

- [ ] **Step 1: Add MCP response parsing tests**

Add these exports to the import list in `scripts/real-copy-prompt-smoke-test.mjs`:

```js
  mcpToolRequest,
  parseMcpToolResponse,
```

Append tests:

```js
test("mcpToolRequest builds a tools/call JSON-RPC request", () => {
  assert.deepEqual(mcpToolRequest(7, "fixthis_open_feedback_console", { packageName: "com.example.reply", newSession: true }), {
    jsonrpc: "2.0",
    id: 7,
    method: "tools/call",
    params: {
      name: "fixthis_open_feedback_console",
      arguments: { packageName: "com.example.reply", newSession: true },
    },
  });
});

test("parseMcpToolResponse decodes JSON text content", () => {
  const response = {
    id: 2,
    result: {
      content: [{
        type: "text",
        text: JSON.stringify({ consoleUrl: "http://127.0.0.1:1234/", sessionId: "session-1" }),
      }],
    },
  };

  assert.deepEqual(parseMcpToolResponse(response), {
    consoleUrl: "http://127.0.0.1:1234/",
    sessionId: "session-1",
  });
});
```

- [ ] **Step 2: Run tests and verify failure**

Run:

```bash
npm run real-copy-prompt:smoke:test
```

Expected: FAIL because the MCP helpers are not exported.

- [ ] **Step 3: Add MCP helper functions**

Add this code to `scripts/real-copy-prompt-smoke.mjs`:

```js
export function mcpToolRequest(id, name, args) {
  return {
    jsonrpc: "2.0",
    id,
    method: "tools/call",
    params: { name, arguments: args },
  };
}

export function parseMcpToolResponse(message) {
  if (message.error) throw new Error(JSON.stringify(message.error));
  const text = message.result?.content?.find((entry) => entry.type === "text")?.text;
  if (!text) throw new Error("MCP response did not include text content");
  return JSON.parse(text);
}

async function startMcpServer({ mcpBin, projectDir, packageName }) {
  const child = spawn(mcpBin, ["--project-dir", projectDir, "--package", packageName], {
    cwd: repoRoot,
    stdio: ["pipe", "pipe", "pipe"],
  });
  const stderr = [];
  child.stderr.setEncoding("utf8");
  child.stderr.on("data", (chunk) => stderr.push(chunk));

  const rl = createInterface({ input: child.stdout });
  const pending = new Map();
  rl.on("line", (line) => {
    if (!line.trim()) return;
    let message;
    try {
      message = JSON.parse(line);
    } catch {
      return;
    }
    const slot = pending.get(message.id);
    if (!slot) return;
    pending.delete(message.id);
    slot.resolve(message);
  });

  let nextId = 1;
  function send(message) {
    child.stdin.write(`${JSON.stringify(message)}\n`);
  }
  async function request(message, timeoutMs = 30_000) {
    const id = message.id;
    const promise = new Promise((resolveMessage, reject) => {
      const timeout = setTimeout(() => {
        pending.delete(id);
        reject(new Error(`Timed out waiting for MCP response ${id}: ${stderr.join("").trim()}`));
      }, timeoutMs);
      pending.set(id, {
        resolve: (value) => {
          clearTimeout(timeout);
          resolveMessage(value);
        },
      });
    });
    send(message);
    return promise;
  }

  await request({
    jsonrpc: "2.0",
    id: nextId++,
    method: "initialize",
    params: {
      protocolVersion: "2024-11-05",
      clientInfo: { name: "real-copy-prompt-smoke", version: "0" },
      capabilities: {},
    },
  });
  send({ jsonrpc: "2.0", method: "notifications/initialized", params: {} });

  return {
    async callTool(name, args) {
      return parseMcpToolResponse(await request(mcpToolRequest(nextId++, name, args)));
    },
    async close() {
      rl.close();
      child.stdin.end();
      child.kill("SIGTERM");
      await new Promise((resolveClose) => child.once("close", resolveClose));
    },
  };
}
```

- [ ] **Step 4: Use MCP to open the real feedback console**

In `runSelectedFixture()`, after `installRuntimeFixture()` succeeds and before marking the row pass, add:

```js
let mcp = null;
try {
  const paths = installRuntimeFixture(fixture, { stdio: "inherit" });
  app.projectDir = paths.projectWorkDir;
  mcp = await startMcpServer({
    mcpBin: options.mcpBin,
    projectDir: paths.projectWorkDir,
    packageName: fixture.applicationId,
  });
  const opened = await mcp.callTool("fixthis_open_feedback_console", {
    packageName: fixture.applicationId,
    newSession: true,
  });
  app.sessionId = opened.sessionId;
  app.consoleUrl = opened.consoleUrl;
  app.status = "pass";
  return app;
} catch (error) {
  app.failures.push(error.message);
  return app;
} finally {
  await mcp?.close?.();
}
```

In `runSmoke()`, pass the MCP binary into each app:

```js
const mcpBin = await ensureMcpDistribution();
...
apps.push(await runSelectedFixture(fixture, { ...options, environment, mcpBin }));
```

- [ ] **Step 5: Run unit tests**

Run:

```bash
npm run real-copy-prompt:smoke:test
```

Expected: PASS.

- [ ] **Step 6: Commit Task 4**

```bash
git add scripts/real-copy-prompt-smoke.mjs scripts/real-copy-prompt-smoke-test.mjs
git commit -m "feat: open feedback console from real copy prompt smoke"
```

### Task 5: Implement Browser Annotation And Copy Prompt Workflow

**Files:**
- Modify: `scripts/real-copy-prompt-smoke.mjs`

- [ ] **Step 1: Add artifact path helpers**

Add these helpers near `writeReports()`:

```js
function artifactPaths(reportDir, fixtureId) {
  const artifactDir = join(reportDir, "artifacts");
  mkdirSync(artifactDir, { recursive: true });
  return {
    artifactDir,
    promptPath: join(artifactDir, `${fixtureId}-prompt.md`),
    screenshotPath: join(artifactDir, `${fixtureId}-after-copy.png`),
    statePath: join(artifactDir, `${fixtureId}-session.json`),
  };
}

function expectedComments(fixture) {
  const label = fixture.id
    .split("-")
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
  return [
    `${label} copy prompt smoke annotation 1`,
    `${label} copy prompt smoke annotation 2`,
  ];
}
```

- [ ] **Step 2: Add Playwright workflow helpers**

Add this code:

```js
async function waitForReadyConsole(page) {
  await page.waitForSelector("#snapshotImage", { timeout: 60_000 });
  await page.waitForFunction(() => {
    const image = document.getElementById("snapshotImage");
    const annotate = document.getElementById("annotateToolButton");
    return image?.complete && image.naturalWidth > 0 && annotate && annotate.disabled === false;
  }, { timeout: 60_000 });
}

async function dragAreaAnnotation(page, box, index) {
  const x1 = box.x + 40 + index * 95;
  const y1 = box.y + 40 + index * 80;
  const x2 = x1 + 90;
  const y2 = y1 + 60;
  await page.mouse.move(x1, y1);
  await page.mouse.down();
  await page.mouse.move(x2, y2);
  await page.mouse.up();
}

async function addAnnotation(page, comment, index) {
  if (await page.getAttribute("#snapshot", "data-tool-mode") !== "annotate") {
    await page.click("#annotateToolButton");
    await page.waitForSelector("#snapshot[data-tool-mode=\"annotate\"]", { timeout: 20_000 });
  }
  const box = await page.locator("#snapshotImage").boundingBox();
  if (!box) throw new Error("Snapshot image is not visible");
  await dragAreaAnnotation(page, box, index);
  await page.waitForSelector("#annotationCommentInput", { timeout: 20_000 });
  await page.fill("#annotationCommentInput", comment);
  await page.waitForFunction(
    (expected) => document.getElementById("pendingItems")?.textContent.includes(expected),
    comment,
    { timeout: 20_000 },
  );
}

async function browserCopyPromptFlow({ consoleUrl, fixture, reportDir, headed }) {
  const comments = expectedComments(fixture);
  const paths = artifactPaths(reportDir, fixture.id);
  const browser = await chromium.launch({ headless: !headed });
  const page = await browser.newPage();
  const consoleMessages = [];
  page.on("console", (message) => consoleMessages.push(`${message.type()}: ${message.text()}`));
  page.on("pageerror", (error) => consoleMessages.push(`pageerror: ${error.message}`));
  await page.addInitScript(() => {
    Object.defineProperty(navigator, "clipboard", {
      value: {
        async writeText(text) {
          window.__fixthisCopiedText = text;
        },
      },
      configurable: true,
    });
  });

  try {
    await page.goto(consoleUrl, { waitUntil: "domcontentloaded", timeout: 60_000 });
    await waitForReadyConsole(page);
    await addAnnotation(page, comments[0], 0);
    await addAnnotation(page, comments[1], 1);
    await page.waitForFunction(() => !document.getElementById("copyPromptButton").disabled, { timeout: 20_000 });
    await page.click("#copyPromptButton");
    await page.waitForFunction(() => window.__fixthisCopiedText?.includes("agent_protocol:"), { timeout: 30_000 });
    const copiedText = await page.evaluate(() => window.__fixthisCopiedText);
    const promptStats = assertCopiedPrompt(copiedText, comments);
    const state = await page.evaluate(() => window.FixThisConsoleDebug?.getState?.()?.session);
    writeFileSync(paths.promptPath, copiedText);
    writeFileSync(paths.statePath, `${JSON.stringify(state, null, 2)}\n`);
    await page.screenshot({ path: paths.screenshotPath, fullPage: true });
    const itemIds = Array.isArray(state?.items) ? state.items.map((item) => item.itemId).filter(Boolean) : [];
    const sessionStats = assertSessionHandedOffItems(state, itemIds);
    return {
      ...promptStats,
      ...sessionStats,
      promptPath: relativeArtifactPath(reportDir, paths.promptPath),
      screenshotPath: relativeArtifactPath(reportDir, paths.screenshotPath),
      statePath: relativeArtifactPath(reportDir, paths.statePath),
    };
  } catch (error) {
    try {
      await page.screenshot({ path: paths.screenshotPath, fullPage: true });
    } catch {}
    const detail = consoleMessages.length > 0 ? `${error.message}; console=${consoleMessages.join(" | ")}` : error.message;
    throw new Error(detail);
  } finally {
    await browser.close();
  }
}
```

- [ ] **Step 3: Call the browser flow from each app row**

In `runSelectedFixture()`, after `opened` is available, replace the temporary pass status with:

```js
const browserStats = await browserCopyPromptFlow({
  consoleUrl: opened.consoleUrl,
  fixture,
  reportDir: options.reportDir,
  headed: options.headed,
});
Object.assign(app, browserStats);
app.status = "pass";
return app;
```

- [ ] **Step 4: Run unit tests**

Run:

```bash
npm run real-copy-prompt:smoke:test
```

Expected: PASS.

- [ ] **Step 5: Run the connected smoke on one app**

Run with an emulator already booted and unlocked:

```bash
npm run real-copy-prompt:smoke -- --fixtures fixthis-sample --strict
```

Expected: PASS with `build/reports/fixthis-real-copy-prompt/report.json` containing one app row, `itemCount >= 2`, `idLineCount >= 2`, and `handedOffCount >= 2`.

- [ ] **Step 6: Commit Task 5**

```bash
git add scripts/real-copy-prompt-smoke.mjs
git commit -m "feat: drive real copy prompt browser smoke"
```

### Task 6: Register Evidence Profile Step

**Files:**
- Modify: `scripts/evidence-runner.mjs`
- Modify: `scripts/evidence-runner-test.mjs`

- [ ] **Step 1: Update the trust profile test**

Replace the existing trust profile test in `scripts/evidence-runner-test.mjs` with:

```js
test("trust profile includes runtime strict and real copy prompt as deferrable Android checks", () => {
  const steps = expandProfile("trust");
  assert.ok(steps.some((step) => step.command === "npm run source-matching:fixtures:test"));

  const runtime = steps.find((step) => step.command === "npm run source-matching:fixtures:runtime -- --strict");
  assert.equal(runtime.deferrable, true);
  assert.equal(runtime.requiresAndroid, true);

  const copyPrompt = steps.find((step) => step.command === "npm run real-copy-prompt:smoke -- --strict");
  assert.equal(copyPrompt.deferrable, true);
  assert.equal(copyPrompt.requiresAndroid, true);
});
```

- [ ] **Step 2: Run the evidence test and verify it fails**

Run:

```bash
npm run evidence:test
```

Expected: FAIL because the new trust step is not present.

- [ ] **Step 3: Add the evidence step**

In `scripts/evidence-runner.mjs`, add this step after `Runtime trust strict`:

```js
    step("Real copy prompt smoke", "npm run real-copy-prompt:smoke -- --strict", {
      deferrable: true,
      requiresAndroid: true,
    }),
```

- [ ] **Step 4: Run the evidence tests**

Run:

```bash
npm run evidence:test
```

Expected: PASS.

- [ ] **Step 5: Commit Task 6**

```bash
git add scripts/evidence-runner.mjs scripts/evidence-runner-test.mjs
git commit -m "chore: add real copy prompt smoke to trust evidence"
```

### Task 7: Update Documentation

**Files:**
- Modify: `CONTRIBUTING.md`
- Modify: `docs/reference/feedback-console-contract.md`
- Modify: `docs/reference/mcp-tools.md`

- [ ] **Step 1: Update local evidence docs**

In `CONTRIBUTING.md`, in `### Local Evidence Profiles`, replace the runtime trust sentence with:

```md
The runner is a convenience layer over canonical commands. If a profile fails,
rerun the failed command printed in the summary. Runtime trust checks and the
real Copy Prompt smoke are reported as deferred when Android SDK or a ready
emulator is unavailable unless the profile is run with `--strict-runtime`.
```

- [ ] **Step 2: Update connected-device docs**

In `CONTRIBUTING.md`, under `## Connected Device Checks`, add this paragraph after the existing `fixthis-smoke.sh` command:

````md
When validating the full feedback-console Copy Prompt path across the real
sample apps, run:

```bash
npm run real-copy-prompt:smoke -- --strict
```

This installs and launches Reply, Jetsnack, and the bundled FixThis sample,
creates two browser annotations per app, clicks Copy Prompt, and writes reports
under `build/reports/fixthis-real-copy-prompt/`. The command is also part of
`npm run evidence:trust` as a deferrable Android step.
````

- [ ] **Step 3: Clarify Copy Prompt persistence semantics**

In `docs/reference/feedback-console-contract.md`, in `## Persistence Semantics`, replace:

```md
- `Copy Prompt` persists written pending annotations when needed, then copies compact agent-facing prompt text.
```

with:

```md
- `Copy Prompt` persists written pending annotations when needed, copies compact agent-facing prompt text, and marks copied items with `lastHandedOffAtEpochMillis`. It does not require `delivery=sent`; that delivery state belongs to queue-style handoff flows.
```

- [ ] **Step 4: Clarify MCP tool reference**

In `docs/reference/mcp-tools.md`, in the paragraph that begins `` `Copy Prompt` and `Save to MCP` persist written pending annotations``, replace the first sentence with:

```md
`Copy Prompt` and `Save to MCP` persist written pending annotations when needed, promote the frozen preview into one persisted evidence snapshot, and connect those items to the same `screenId`; Copy Prompt then copies compact Markdown and records `lastHandedOffAtEpochMillis`, while Save to MCP creates the local MCP handoff batch.
```

- [ ] **Step 5: Run documentation-adjacent checks**

Run:

```bash
npm run docs:agent-bootstrap:test
npm run evidence:test
```

Expected: PASS.

- [ ] **Step 6: Commit Task 7**

```bash
git add CONTRIBUTING.md docs/reference/feedback-console-contract.md docs/reference/mcp-tools.md
git commit -m "docs: clarify real copy prompt evidence"
```

### Task 8: Run Local Unit Verification

**Files:**
- No source edits expected.

- [ ] **Step 1: Run the focused Node tests**

Run:

```bash
npm run real-copy-prompt:smoke:test
npm run source-matching:fixtures:test
npm run evidence:test
```

Expected: all PASS.

- [ ] **Step 2: Run broader local tests affected by docs and scripts**

Run:

```bash
npm run console:smoke
```

Expected: PASS.

- [ ] **Step 3: Check git status before connected smoke**

Run:

```bash
git status --short
```

Expected: clean except ignored build reports that do not appear in status.

### Task 9: Run Connected-Device Verification

**Files:**
- No source edits expected.

- [ ] **Step 1: Confirm Android device availability**

Run:

```bash
adb devices
```

Expected: at least one `device` row, for example `emulator-5554 device`.

- [ ] **Step 2: Run runtime source matching first**

Run:

```bash
npm run source-matching:fixtures:runtime -- --strict
```

Expected: PASS with report under `build/reports/fixthis-source-matching/`.

- [ ] **Step 3: Run the new real Copy Prompt smoke**

Run:

```bash
npm run real-copy-prompt:smoke -- --strict
```

Expected: PASS. Confirm these fields in `build/reports/fixthis-real-copy-prompt/report.json`:

```json
{
  "status": "pass",
  "summary": {
    "totalApps": 3,
    "passedApps": 3,
    "failedApps": 0,
    "deferredApps": 0
  }
}
```

Each app row must include:

```json
{
  "itemCount": 2,
  "idLineCount": 2,
  "handedOffCount": 2
}
```

Values may be larger than `2`; they must not be smaller.

- [ ] **Step 4: Run the trust evidence profile**

Run:

```bash
npm run evidence:trust -- --strict-runtime
```

Expected: PASS. The report under `build/reports/fixthis-evidence/` includes `Runtime trust strict` and `Real copy prompt smoke` as passed Android steps.

### Task 10: Refresh Graphify And Final Hygiene

**Files:**
- Generated graph files are not committed.

- [ ] **Step 1: Refresh graphify**

Run:

```bash
graphify update .
```

Expected: command completes. Dirty `graphify-out/` files are ignored and not committed.

- [ ] **Step 2: Verify ignored artifacts are not staged**

Run:

```bash
git status --short
```

Expected: source/docs changes only. Do not stage `.fixthis/`, `graphify-out/`, `build/`, fixture workspaces, screenshots, or prompt artifacts.

- [ ] **Step 3: Commit final verification docs or fixes if any**

If Tasks 8 through 10 required source or documentation fixes, commit them:

```bash
git add package.json scripts/real-copy-prompt-smoke.mjs scripts/real-copy-prompt-smoke-test.mjs scripts/source-matching-fixtures.mjs scripts/source-matching-fixtures-test.mjs scripts/evidence-runner.mjs scripts/evidence-runner-test.mjs CONTRIBUTING.md docs/reference/feedback-console-contract.md docs/reference/mcp-tools.md
git commit -m "chore: finalize real copy prompt evidence smoke"
```

If no files changed after the previous task commits, skip this commit and record that the tree is already clean.

## Self-Review Checklist

- Spec coverage:
  - Real 3-app default set: Task 1 and Task 9.
  - `nowinandroid` exclusion: Task 1.
  - Runtime fixture reuse: Task 2.
  - MCP console session per app: Task 4.
  - Two annotations and Copy Prompt: Task 5.
  - Prompt markers and comments: Task 1 and Task 5.
  - `lastHandedOffAtEpochMillis` instead of `delivery=sent`: Task 3, Task 5, Task 7.
  - Reports under `build/reports/fixthis-real-copy-prompt/`: Task 1 and Task 5.
  - `evidence:trust` deferrable step: Task 6.
  - Docs: Task 7.
  - Connected verification: Task 9.
  - `graphify update .`: Task 10.
- Placeholder scan:
  - Placeholder scan passes with zero matches for banned placeholder phrases.
  - Every code-changing task includes exact file paths, snippets, commands, and expected outcomes.
- Type consistency:
  - `parseArgs`, `selectRuntimeFixtures`, `assertCopiedPrompt`, `assertSessionHandedOffItems`, `summarizeApps`, `buildReport`, `renderMarkdownReport`, and `runSmoke` are defined before later tasks use them.
  - `installRuntimeFixture` and `runtimeFixtures` are exported before the smoke imports them.
  - Evidence command string is consistently `npm run real-copy-prompt:smoke -- --strict`.
