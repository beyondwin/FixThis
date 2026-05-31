# v1.1 Trust Loop Umbrella Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the post-v1.0 FixThis trust loop across release reality, external agent lifecycle completion, and runtime source-trust calibration.

**Architecture:** Extend the existing evidence runner, release-readiness checker, runtime fixture lab, and real Copy Prompt smoke patterns. Add one shared MCP JSON-RPC client helper so connected smoke scripts reuse the same stdio lifecycle. Keep lifecycle smoke and source-trust fixtures separate, then aggregate them through evidence profiles and release-readiness docs.

**Tech Stack:** Node.js 20 ESM (`node:test`, Playwright), Kotlin/JVM + kotlinx.serialization, Gradle, Jetpack Compose sample/runtime fixtures, Markdown docs.

Design: [`docs/superpowers/specs/2026-05-31-v11-trust-loop-umbrella-design.md`](../specs/2026-05-31-v11-trust-loop-umbrella-design.md).

---

## File Structure

### Track A - Release Reality

- Create: `scripts/release-reality-check.mjs`
  - Pure release surface classification plus CLI entrypoint.
  - Writes `build/reports/fixthis-release-reality/report.json` and `.md`.
- Create: `scripts/release-reality-check-test.mjs`
  - Contract tests for verified/deferred/mismatch classification and report rendering.
- Modify: `scripts/evidence-runner.mjs`
  - Add release reality as the first `release` profile step.
- Modify: `scripts/evidence-runner-test.mjs`
  - Pin the new release profile command.
- Modify: `scripts/check-release-readiness.mjs`
  - Add a rule requiring the v1.1 Trust Loop manifest.
- Modify: `docs/contributing/release-readiness.md`
  - Add the v1.1 Trust Loop Evidence manifest.
- Modify: `docs/releases/unreleased.md`
  - Mention the planned v1.1 trust-loop evidence line without claiming implementation.

### Track B - External Agent Loop E2E

- Create: `scripts/mcp-json-rpc-client.mjs`
  - Shared stdio MCP client used by connected smoke scripts.
- Create: `scripts/mcp-json-rpc-client-test.mjs`
  - Tests request building, text JSON parsing, timeout handling, and close semantics with fakes.
- Modify: `scripts/real-copy-prompt-smoke.mjs`
  - Replace local MCP request/client helpers with the shared helper.
- Modify: `scripts/real-copy-prompt-smoke-test.mjs`
  - Remove duplicated request/parse helper assertions after they move to the shared helper test.
- Create: `scripts/agent-loop-smoke.mjs`
  - Connected smoke for Save to MCP / Copy Prompt protocol tokens / claim / resolve / console reflection.
- Create: `scripts/agent-loop-smoke-test.mjs`
  - Pure tests for args, fixture selection, status assertions, report rendering, and environment strictness.
- Modify: `package.json`
  - Add `agent-loop:smoke`, `agent-loop:smoke:test`, and include the new smoke in trust evidence.

### Track C - Runtime Source Trust

- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustFixtureModels.kt`
  - Add observed call-site output and visual-area selector support.
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustFixtureRunner.kt`
  - Route visual-area selectors to `addAreaFeedback`; node selectors continue through `RuntimeTargetResolver`.
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustObservationMapperTest.kt`
  - Lock observed call-site serialization.
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustFixtureRunnerTest.kt`
  - Lock visual-area case execution.
- Modify: `scripts/source-matching-fixtures.mjs`
  - Validate visual-area runtime targets and make missing trust observations fail in strict runtime output.
- Modify: `scripts/source-matching-fixtures-test.mjs`
  - Add manifest/schema/classification tests for visual-area and overclaim cases.
- Modify: `fixtures/source-matching/manifest.json`
  - Add runtime trust cases for FixThis sample shared-component, interop-risk, and visual-area scenarios.
- Modify: `docs/guides/source-matching-fixture-lab.md`
  - Document runtime trust purposes, visual-area cases, and strict observation failures.
- Modify: `docs/product/roadmap.md`
  - Add a short v1.1 Trust Loop roadmap note.

---

## Task 1: Add Release Reality Checker Contracts

**Files:**
- Create: `scripts/release-reality-check-test.mjs`
- Create: `scripts/release-reality-check.mjs`

- [ ] **Step 1: Write the failing release reality tests**

Create `scripts/release-reality-check-test.mjs`:

```js
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  buildReleaseRealityReport,
  classifySurface,
  renderMarkdownReport,
  runReleaseRealityCheck,
  writeReports,
} from "./release-reality-check.mjs";

test("classifySurface distinguishes verified deferred and mismatch", () => {
  assert.deepEqual(
    classifySurface({ name: "npm", expected: "1.0.0", actual: "1.0.0" }),
    { name: "npm", status: "verified", expected: "1.0.0", actual: "1.0.0" },
  );
  assert.deepEqual(
    classifySurface({ name: "homebrew", expected: "1.0.0", actual: null, reason: "network unavailable" }),
    { name: "homebrew", status: "deferred", expected: "1.0.0", actual: null, reason: "network unavailable" },
  );
  assert.deepEqual(
    classifySurface({ name: "tag", expected: "v1.0.0", actual: "missing" }),
    { name: "tag", status: "mismatch", expected: "v1.0.0", actual: "missing", reason: "expected v1.0.0 but observed missing" },
  );
});

test("strict report fails deferred and mismatch while non-strict fails mismatch only", () => {
  const surfaces = [
    { name: "tag", status: "verified" },
    { name: "npm", status: "deferred" },
    { name: "mcp-registry", status: "mismatch" },
  ];

  assert.equal(buildReleaseRealityReport({ strict: false, surfaces }).status, "fail");
  assert.equal(buildReleaseRealityReport({ strict: true, surfaces: surfaces.slice(0, 2) }).status, "fail");
  assert.equal(buildReleaseRealityReport({ strict: false, surfaces: surfaces.slice(0, 2) }).status, "pass_with_deferred");
});

test("runReleaseRealityCheck uses injected probes and current version", () => {
  const report = runReleaseRealityCheck({
    strict: false,
    version: "1.0.0",
    probes: {
      gitTag: () => "v1.0.0",
      githubRelease: () => null,
      homebrew: () => "1.0.0",
      npm: () => "1.0.0",
      mcpRegistry: () => "1.0.0",
      gradlePluginPortal: () => "1.0.0",
      mavenCentral: () => "1.0.0",
    },
  });

  assert.equal(report.status, "pass_with_deferred");
  assert.equal(report.surfaces.find((surface) => surface.name === "github-release").status, "deferred");
});

test("markdown report renders every release surface", () => {
  const text = renderMarkdownReport({
    status: "pass_with_deferred",
    strict: false,
    version: "1.0.0",
    generatedAt: "2026-05-31T00:00:00.000Z",
    surfaces: [
      { name: "git-tag", status: "verified", expected: "v1.0.0", actual: "v1.0.0" },
      { name: "github-release", status: "deferred", expected: "v1.0.0", actual: null, reason: "network unavailable" },
    ],
  });

  assert.match(text, /# FixThis Release Reality Report/);
  assert.match(text, /\| git-tag \| verified \| v1\.0\.0 \| v1\.0\.0 \| - \|/);
  assert.match(text, /\| github-release \| deferred \| v1\.0\.0 \| - \| network unavailable \|/);
});

test("writeReports writes json and markdown artifacts", () => {
  const root = mkdtempSync(join(tmpdir(), "fixthis-release-reality-"));
  try {
    const paths = writeReports({
      status: "pass",
      strict: false,
      version: "1.0.0",
      generatedAt: "2026-05-31T00:00:00.000Z",
      surfaces: [],
    }, root);
    assert.match(readFileSync(paths.json, "utf8"), /"version": "1.0.0"/);
    assert.match(readFileSync(paths.markdown, "utf8"), /FixThis Release Reality Report/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
node --test scripts/release-reality-check-test.mjs
```

Expected: FAIL with `Cannot find module` for `scripts/release-reality-check.mjs`.

- [ ] **Step 3: Add the checker implementation**

Create `scripts/release-reality-check.mjs`:

```js
#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { readFixThisVersion } from "./release-version.mjs";

const scriptPath = fileURLToPath(import.meta.url);
const repoRoot = resolve(dirname(scriptPath), "..");
const defaultReportDir = join(repoRoot, "build/reports/fixthis-release-reality");

function execText(command, args, options = {}) {
  try {
    return execFileSync(command, args, {
      cwd: options.cwd || repoRoot,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
      timeout: options.timeoutMs || 20_000,
    }).trim();
  } catch (error) {
    return null;
  }
}

function jsonFromUrl(url) {
  const text = execText("curl", ["-fsSL", url], { timeoutMs: 20_000 });
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

export function classifySurface({ name, expected, actual, reason = null }) {
  if (actual === expected) {
    return { name, status: "verified", expected, actual };
  }
  if (actual === null || actual === undefined || actual === "") {
    return { name, status: "deferred", expected, actual: null, reason: reason || "surface unavailable" };
  }
  return {
    name,
    status: "mismatch",
    expected,
    actual,
    reason: reason || `expected ${expected} but observed ${actual}`,
  };
}

function statusForSurfaces(surfaces, strict) {
  if (surfaces.some((surface) => surface.status === "mismatch")) return "fail";
  if (strict && surfaces.some((surface) => surface.status === "deferred")) return "fail";
  if (surfaces.some((surface) => surface.status === "deferred")) return "pass_with_deferred";
  return "pass";
}

export function buildReleaseRealityReport({ strict = false, version, surfaces, generatedAt = new Date().toISOString() }) {
  return {
    schemaVersion: "1.0",
    status: statusForSurfaces(surfaces, strict),
    strict,
    version,
    generatedAt,
    surfaces,
  };
}

function defaultProbes(root = repoRoot) {
  return {
    gitTag: (version) => execText("git", ["tag", "--list", `v${version}`]) || "missing",
    githubRelease: (version) => {
      const json = jsonFromUrl(`https://api.github.com/repos/beyondwin/FixThis/releases/tags/v${version}`);
      return json?.tag_name || null;
    },
    homebrew: (version) => execText("bash", ["-lc", "brew info --json=v2 beyondwin/tools/fixthis 2>/dev/null | node -e 'let s=\"\";process.stdin.on(\"data\",d=>s+=d);process.stdin.on(\"end\",()=>{const j=JSON.parse(s);console.log(j.formulae?.[0]?.versions?.stable||\"\")})'"]) || null,
    npm: (version) => execText("npm", ["view", "@beyondwin/fixthis", "version"]) || null,
    mcpRegistry: (version) => {
      const text = existsSync(join(root, "server.json")) ? execText("node", ["-e", "const j=require('./server.json'); console.log(j.packages?.[0]?.version || j.version || '')"]) : null;
      return text || null;
    },
    gradlePluginPortal: (version) => {
      const json = jsonFromUrl(`https://plugins.gradle.org/api/gradle/${version}/plugin/use/io.github.beyondwin.fixthis.compose/${version}`);
      return json ? version : null;
    },
    mavenCentral: (version) => {
      const json = jsonFromUrl(`https://search.maven.org/solrsearch/select?q=g:%22io.github.beyondwin%22+AND+a:%22fixthis-compose-sidekick%22+AND+v:%22${version}%22&rows=1&wt=json`);
      return json?.response?.numFound > 0 ? version : null;
    },
  };
}

export function runReleaseRealityCheck({ strict = false, version = readFixThisVersion(repoRoot), probes = defaultProbes() } = {}) {
  const surfaces = [
    classifySurface({ name: "git-tag", expected: `v${version}`, actual: probes.gitTag(version) }),
    classifySurface({ name: "github-release", expected: `v${version}`, actual: probes.githubRelease(version), reason: "GitHub release API unavailable or release not published" }),
    classifySurface({ name: "homebrew", expected: version, actual: probes.homebrew(version), reason: "Homebrew formula unavailable" }),
    classifySurface({ name: "npm", expected: version, actual: probes.npm(version), reason: "npm package unavailable" }),
    classifySurface({ name: "mcp-registry", expected: version, actual: probes.mcpRegistry(version), reason: "MCP registry metadata unavailable" }),
    classifySurface({ name: "gradle-plugin-portal", expected: version, actual: probes.gradlePluginPortal(version), reason: "Gradle Plugin Portal metadata unavailable" }),
    classifySurface({ name: "maven-central", expected: version, actual: probes.mavenCentral(version), reason: "Maven Central metadata unavailable" }),
  ];
  return buildReleaseRealityReport({ strict, version, surfaces });
}

function cell(value) {
  return value === null || value === undefined || value === "" ? "-" : String(value).replaceAll("|", "\\|");
}

export function renderMarkdownReport(report) {
  const lines = [
    "# FixThis Release Reality Report",
    "",
    `- Status: ${report.status}`,
    `- Strict: ${report.strict}`,
    `- Version: ${report.version}`,
    `- Generated: ${report.generatedAt}`,
    "",
    "| Surface | Status | Expected | Actual | Reason |",
    "| --- | --- | --- | --- | --- |",
  ];
  for (const surface of report.surfaces || []) {
    lines.push(`| ${cell(surface.name)} | ${cell(surface.status)} | ${cell(surface.expected)} | ${cell(surface.actual)} | ${cell(surface.reason)} |`);
  }
  return `${lines.join("\n")}\n`;
}

export function writeReports(report, reportDir = defaultReportDir) {
  mkdirSync(reportDir, { recursive: true });
  const json = join(reportDir, "report.json");
  const markdown = join(reportDir, "report.md");
  writeFileSync(json, `${JSON.stringify(report, null, 2)}\n`);
  writeFileSync(markdown, renderMarkdownReport(report));
  return { json, markdown };
}

function parseArgs(argv) {
  const args = { strict: false };
  for (const arg of argv) {
    if (arg === "--strict") args.strict = true;
    else if (arg === "-h" || arg === "--help") {
      console.log("Usage: node scripts/release-reality-check.mjs [--strict]");
      process.exit(0);
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  return args;
}

if (process.argv[1] && resolve(process.argv[1]) === scriptPath) {
  const args = parseArgs(process.argv.slice(2));
  const report = runReleaseRealityCheck(args);
  const paths = writeReports(report);
  console.log(`Release reality: ${report.status}`);
  console.log(`JSON: ${paths.json}`);
  console.log(`Markdown: ${paths.markdown}`);
  if (report.status === "fail") process.exit(1);
}
```

- [ ] **Step 4: Run the release reality tests**

Run:

```bash
node --test scripts/release-reality-check-test.mjs
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/release-reality-check.mjs scripts/release-reality-check-test.mjs
git commit -m "test(release): add release reality checker contracts"
```

---

## Task 2: Wire Release Reality Into Evidence And Docs

**Files:**
- Modify: `package.json`
- Modify: `scripts/evidence-runner.mjs`
- Modify: `scripts/evidence-runner-test.mjs`
- Modify: `scripts/check-release-readiness.mjs`
- Modify: `docs/contributing/release-readiness.md`
- Modify: `docs/releases/unreleased.md`

- [ ] **Step 1: Write failing evidence-runner assertions**

In `scripts/evidence-runner-test.mjs`, add after `dry run renders commands without executing`:

```js
test("release profile starts with release reality and includes agent loop contracts", () => {
  const commands = expandProfile("release").map((step) => step.command);
  assert.equal(commands[0], "npm run release:reality");
  assert.ok(commands.includes("npm run agent-loop:smoke:test"));
});
```

- [ ] **Step 2: Write failing package script assertions**

In `scripts/source-matching-fixtures-test.mjs`, extend `package.json exposes local fixture scripts`:

```js
  assert.equal(pkg.scripts["release:reality"], "node scripts/release-reality-check.mjs");
  assert.equal(pkg.scripts["agent-loop:smoke"], "node scripts/agent-loop-smoke.mjs");
  assert.equal(pkg.scripts["agent-loop:smoke:test"], "node --test scripts/agent-loop-smoke-test.mjs");
```

- [ ] **Step 3: Run tests to verify failure**

Run:

```bash
node --test scripts/evidence-runner-test.mjs scripts/source-matching-fixtures-test.mjs
```

Expected: FAIL because `release:reality` and `agent-loop:*` scripts are not present and the release profile does not include them.

- [ ] **Step 4: Add package scripts**

Modify `package.json` scripts:

```json
"release:reality": "node scripts/release-reality-check.mjs",
"agent-loop:smoke": "node scripts/agent-loop-smoke.mjs",
"agent-loop:smoke:test": "node --test scripts/agent-loop-smoke-test.mjs"
```

Place `release:reality` next to `release:check`. Place `agent-loop:*` next to `real-copy-prompt:*`.

- [ ] **Step 5: Update evidence release profile**

In `scripts/evidence-runner.mjs`, change the `release` profile to:

```js
  release: [
    step("Release reality", "npm run release:reality"),
    step("Agent loop smoke contracts", "npm run agent-loop:smoke:test"),
    step("Release readiness", "node scripts/check-release-readiness.mjs"),
    step("Docs CLI surface", "bash scripts/check-docs-cli-surface.sh"),
    step("Agent bootstrap docs", "npm run docs:agent-bootstrap:test"),
    step("Version metadata", "npm run release:version:check"),
    step("Package tests", "npm run release:package:test"),
    step("npm and MCP registry tests", "npm run release:npm:test"),
  ],
```

- [ ] **Step 6: Add release-readiness rule**

In `scripts/check-release-readiness.mjs`, append after `R32.v1-0-release-claim-manifest`:

```js
requireIncludes(
  'R33.v11-trust-loop-evidence-manifest',
  'docs/contributing/release-readiness.md',
  '## v1.1 Trust Loop Evidence',
);
requireIncludes(
  'R34.v11-release-reality-command',
  'docs/contributing/release-readiness.md',
  '`npm run release:reality`',
);
requireIncludes(
  'R35.v11-agent-loop-command',
  'docs/contributing/release-readiness.md',
  '`npm run agent-loop:smoke -- --strict`',
);
```

- [ ] **Step 7: Add v1.1 evidence manifest documentation**

In `docs/contributing/release-readiness.md`, add after the v1.0 manifest:

```markdown
## v1.1 Trust Loop Evidence

The v1.1 trust-loop line may be claimed only when each area below has matching
local evidence from the release commit. This evidence pack does not tag or
publish by itself.

| Claim | Required evidence |
| --- | --- |
| Release/install claims match observable package, tag, and registry state or are explicitly deferred with a reason. | `npm run release:reality`, `npm run evidence:release`, and `node scripts/check-release-readiness.mjs`. |
| External Android agent lifecycle completes from handoff through claim, resolution, persistence, and console reflection. | `npm run agent-loop:smoke:test` and `npm run agent-loop:smoke -- --strict`. |
| Runtime source trust keeps shared-component, interop-risk, and visual-area guidance caveated instead of overclaiming exact ownership. | `npm run source-matching:fixtures:test`, `npm run source-matching:fixtures:runtime -- --strict`, and `npm run handoff:eval:test`. |

When Android SDK or an unlocked emulator is unavailable, record the connected
commands as deferred rather than implying they passed.
```

- [ ] **Step 8: Update unreleased release notes**

In `docs/releases/unreleased.md`, add under Highlights:

```markdown
The next planned evidence line is the v1.1 Trust Loop: release reality checks,
external agent lifecycle smoke, and runtime source-trust calibration. It is a
post-v1.0 hardening line and does not claim a new tagged release until the
evidence commands pass or are explicitly deferred in the release issue.
```

- [ ] **Step 9: Run docs and evidence tests**

Run:

```bash
node --test scripts/evidence-runner-test.mjs scripts/source-matching-fixtures-test.mjs
node scripts/check-release-readiness.mjs
node scripts/check-doc-consistency.mjs
```

Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add package.json scripts/evidence-runner.mjs scripts/evidence-runner-test.mjs scripts/check-release-readiness.mjs docs/contributing/release-readiness.md docs/releases/unreleased.md scripts/source-matching-fixtures-test.mjs
git commit -m "docs(release): add v1.1 trust loop evidence gate"
```

---

## Task 3: Extract Shared MCP JSON-RPC Client

**Files:**
- Create: `scripts/mcp-json-rpc-client.mjs`
- Create: `scripts/mcp-json-rpc-client-test.mjs`
- Modify: `scripts/real-copy-prompt-smoke.mjs`
- Modify: `scripts/real-copy-prompt-smoke-test.mjs`

- [ ] **Step 1: Write shared MCP client tests**

Create `scripts/mcp-json-rpc-client-test.mjs`:

```js
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import test from "node:test";
import { mcpToolRequest, parseMcpToolResponse, createMcpJsonRpcClient } from "./mcp-json-rpc-client.mjs";

test("mcpToolRequest builds a tools/call JSON-RPC request", () => {
  assert.deepEqual(mcpToolRequest(7, "fixthis_open_feedback_console", { packageName: "com.example.reply" }), {
    jsonrpc: "2.0",
    id: 7,
    method: "tools/call",
    params: {
      name: "fixthis_open_feedback_console",
      arguments: { packageName: "com.example.reply" },
    },
  });
});

test("parseMcpToolResponse decodes JSON text content", () => {
  assert.deepEqual(parseMcpToolResponse({
    id: 1,
    result: { content: [{ type: "text", text: "{\"sessionId\":\"session-1\"}" }] },
  }), { sessionId: "session-1" });
});

test("parseMcpToolResponse throws MCP errors and missing text", () => {
  assert.throws(() => parseMcpToolResponse({ error: { code: -32602, message: "bad" } }), /bad/);
  assert.throws(() => parseMcpToolResponse({ result: { content: [] } }), /text content/);
});

test("createMcpJsonRpcClient initializes, calls tools, and closes child process", async () => {
  const writes = [];
  const stdout = new EventEmitter();
  stdout.setEncoding = () => {};
  const stderr = new EventEmitter();
  stderr.setEncoding = () => {};
  const child = new EventEmitter();
  child.stdout = stdout;
  child.stderr = stderr;
  child.stdin = {
    write: (line) => {
      writes.push(JSON.parse(line));
      const request = writes.at(-1);
      if (request.method === "initialize") {
        queueMicrotask(() => stdout.emit("line", JSON.stringify({ id: request.id, result: {} })));
      }
      if (request.method === "tools/call") {
        queueMicrotask(() => stdout.emit("line", JSON.stringify({
          id: request.id,
          result: { content: [{ type: "text", text: "{\"ok\":true}" }] },
        })));
      }
    },
    end: () => { child.stdinEnded = true; },
  };
  child.kill = (signal) => { child.killedWith = signal; child.emit("close", 0); };

  const client = await createMcpJsonRpcClient({
    command: "fixthis-mcp",
    args: ["--project-dir", "."],
    spawnFn: () => child,
    readlineFactory: ({ input }) => input,
    clientInfo: { name: "test-client", version: "0" },
  });

  assert.equal(writes[1].method, "notifications/initialized");
  assert.deepEqual(await client.callTool("fixthis_status", {}), { ok: true });
  await client.close();
  assert.equal(child.stdinEnded, true);
  assert.equal(child.killedWith, "SIGTERM");
});
```

- [ ] **Step 2: Run the test to verify failure**

Run:

```bash
node --test scripts/mcp-json-rpc-client-test.mjs
```

Expected: FAIL with `Cannot find module` for `scripts/mcp-json-rpc-client.mjs`.

- [ ] **Step 3: Create the shared helper**

Create `scripts/mcp-json-rpc-client.mjs`:

```js
import { spawn } from "node:child_process";
import { createInterface } from "node:readline";

export function mcpToolRequest(id, name, args) {
  return {
    jsonrpc: "2.0",
    id,
    method: "tools/call",
    params: { name, arguments: args },
  };
}

export function parseMcpToolResponse(message) {
  if (message.error) {
    throw new Error(message.error.message || JSON.stringify(message.error));
  }
  const text = message.result?.content?.find((entry) => entry.type === "text")?.text;
  if (!text) throw new Error("MCP response did not include text content");
  return JSON.parse(text);
}

export async function createMcpJsonRpcClient({
  command,
  args = [],
  cwd,
  env = process.env,
  spawnFn = spawn,
  readlineFactory = createInterface,
  clientInfo = { name: "fixthis-smoke", version: "0" },
  initializeTimeoutMs = 30_000,
} = {}) {
  if (!command) throw new Error("MCP command is required");
  const child = spawnFn(command, args, {
    cwd,
    env,
    stdio: ["pipe", "pipe", "pipe"],
  });
  const stderr = [];
  child.stderr?.setEncoding?.("utf8");
  child.stderr?.on?.("data", (chunk) => stderr.push(chunk));
  child.stdout?.setEncoding?.("utf8");
  const rl = readlineFactory({ input: child.stdout });
  const pending = new Map();
  let nextId = 1;
  const closed = new Promise((resolve) => child.once?.("close", resolve));

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

  function send(message) {
    child.stdin.write(`${JSON.stringify(message)}\n`);
  }

  async function request(message, timeoutMs = 30_000) {
    const id = message.id;
    const promise = new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        pending.delete(id);
        reject(new Error(`Timed out waiting for MCP response ${id}: ${stderr.join("").trim()}`));
      }, timeoutMs);
      pending.set(id, {
        resolve: (value) => {
          clearTimeout(timeout);
          resolve(value);
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
      clientInfo,
      capabilities: {},
    },
  }, initializeTimeoutMs);
  send({ jsonrpc: "2.0", method: "notifications/initialized", params: {} });

  return {
    async callTool(name, toolArgs, timeoutMs) {
      return parseMcpToolResponse(await request(mcpToolRequest(nextId++, name, toolArgs), timeoutMs));
    },
    async close() {
      rl.close?.();
      child.stdin.end();
      child.kill?.("SIGTERM");
      await Promise.race([
        closed,
        new Promise((resolve) => setTimeout(resolve, 5_000)),
      ]);
      if (!child.killed) child.kill?.("SIGKILL");
    },
  };
}
```

- [ ] **Step 4: Refactor real Copy Prompt smoke to use the helper**

In `scripts/real-copy-prompt-smoke.mjs`, replace imports and remove local `mcpToolRequest`, `parseMcpToolResponse`, and `startMcpServer`. Add:

```js
import { createMcpJsonRpcClient } from "./mcp-json-rpc-client.mjs";
```

Replace `startMcpServer` call inside `runSelectedFixture` with:

```js
    mcp = await createMcpJsonRpcClient({
      command: options.mcpBin,
      args: ["--project-dir", paths.projectWorkDir, "--package", fixture.applicationId],
      cwd: repoRoot,
      env: {
        ...process.env,
        ...withAndroidEnvironment({}, options.environment).env,
      },
      clientInfo: { name: "real-copy-prompt-smoke", version: "0" },
    });
```

- [ ] **Step 5: Move duplicated helper tests**

In `scripts/real-copy-prompt-smoke-test.mjs`, remove imports and tests for `mcpToolRequest` and `parseMcpToolResponse`. Those assertions now live in `scripts/mcp-json-rpc-client-test.mjs`.

- [ ] **Step 6: Run tests**

Run:

```bash
node --test scripts/mcp-json-rpc-client-test.mjs scripts/real-copy-prompt-smoke-test.mjs
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add scripts/mcp-json-rpc-client.mjs scripts/mcp-json-rpc-client-test.mjs scripts/real-copy-prompt-smoke.mjs scripts/real-copy-prompt-smoke-test.mjs
git commit -m "refactor(smoke): share MCP JSON-RPC client"
```

---

## Task 4: Add Agent Loop Smoke Contracts

**Files:**
- Create: `scripts/agent-loop-smoke-test.mjs`
- Create: `scripts/agent-loop-smoke.mjs`

- [ ] **Step 1: Write failing pure smoke tests**

Create `scripts/agent-loop-smoke-test.mjs`:

```js
import assert from "node:assert/strict";
import test from "node:test";
import {
  assertCopiedPromptProtocol,
  assertLifecycleSessionState,
  buildReport,
  expectedResolutionPlan,
  parseArgs,
  renderMarkdownReport,
  selectAgentLoopFixture,
  statusForEnvironment,
} from "./agent-loop-smoke.mjs";

const manifest = {
  fixtures: [
    {
      id: "reply",
      applicationId: "com.example.reply",
      cases: [{ id: "reply-runtime", mode: "runtime-trust", runtimeTarget: { text: "Inbox" } }],
    },
    {
      id: "fixthis-sample",
      applicationId: "io.github.beyondwin.fixthis.sample",
      cases: [{ id: "sample-runtime", mode: "runtime-trust", runtimeTarget: { text: "FixThis" } }],
    },
  ],
};

test("parseArgs supports strict fixture report dir and headed mode", () => {
  assert.deepEqual(parseArgs(["--strict", "--fixture", "fixthis-sample", "--report-dir", "out/agent-loop", "--headed"]), {
    strict: true,
    fixtureId: "fixthis-sample",
    reportDir: "out/agent-loop",
    headed: true,
  });
});

test("selectAgentLoopFixture defaults to Reply and rejects source-only fixtures", () => {
  assert.equal(selectAgentLoopFixture(manifest, null).id, "reply");
  assert.equal(selectAgentLoopFixture(manifest, "fixthis-sample").id, "fixthis-sample");
  assert.throws(() => selectAgentLoopFixture({ fixtures: [{ id: "source-only", cases: [{ mode: "source-index" }] }] }, "source-only"), /not an installable runtime agent-loop fixture/);
});

test("expectedResolutionPlan covers terminal and clarification statuses", () => {
  assert.deepEqual(expectedResolutionPlan(["item-1", "item-2", "item-3"]), [
    { itemId: "item-1", status: "resolved", summary: "Agent loop smoke resolved item-1" },
    { itemId: "item-2", status: "needs_clarification", summary: "Agent loop smoke needs clarification for item-2" },
    { itemId: "item-3", status: "wont_fix", summary: "Agent loop smoke will not fix item-3" },
  ]);
});

test("assertCopiedPromptProtocol requires session id item ids and agent protocol", () => {
  const prompt = [
    "session_id: session-1",
    "- id: item-1",
    "- id: item-2",
    "agent_protocol: fixthis-feedback/v1",
  ].join("\n");

  assert.deepEqual(assertCopiedPromptProtocol(prompt), {
    sessionId: "session-1",
    itemIds: ["item-1", "item-2"],
  });

  assert.throws(() => assertCopiedPromptProtocol("id: item-1"), /missing session_id.*missing agent_protocol/s);
});

test("assertLifecycleSessionState validates item statuses and summaries", () => {
  const session = {
    items: [
      { itemId: "item-1", status: "resolved", agentSummary: "Agent loop smoke resolved item-1" },
      { itemId: "item-2", status: "needs_clarification", agentSummary: "Agent loop smoke needs clarification for item-2" },
      { itemId: "item-3", status: "wont_fix", agentSummary: "Agent loop smoke will not fix item-3" },
    ],
  };
  const plan = expectedResolutionPlan(["item-1", "item-2", "item-3"]);

  assert.deepEqual(assertLifecycleSessionState(session, plan), {
    resolved: 1,
    needsClarification: 1,
    wontFix: 1,
  });
});

test("environment status distinguishes strict failure and non-strict deferral", () => {
  assert.deepEqual(statusForEnvironment({ strict: false, androidReady: false, reason: "No Android device" }), {
    status: "deferred",
    exitCode: 0,
    failures: ["No Android device"],
  });
  assert.deepEqual(statusForEnvironment({ strict: true, androidReady: false, reason: "No Android device" }), {
    status: "fail",
    exitCode: 1,
    failures: ["No Android device"],
  });
});

test("buildReport and markdown summarize lifecycle counts", () => {
  const report = buildReport({
    strict: true,
    device: "emulator-5554",
    startedAt: "2026-05-31T00:00:00.000Z",
    finishedAt: "2026-05-31T00:01:00.000Z",
    fixture: {
      fixtureId: "reply",
      packageName: "com.example.reply",
      status: "pass",
      savedItemCount: 3,
      resolved: 1,
      needsClarification: 1,
      wontFix: 1,
    },
  });

  assert.equal(report.status, "pass");
  assert.match(renderMarkdownReport(report), /\| reply \| com\.example\.reply \| pass \| 3 \| 1 \| 1 \| 1 \|/);
});
```

- [ ] **Step 2: Run test to verify failure**

Run:

```bash
node --test scripts/agent-loop-smoke-test.mjs
```

Expected: FAIL with `Cannot find module` for `scripts/agent-loop-smoke.mjs`.

- [ ] **Step 3: Add smoke skeleton and pure helpers**

Create `scripts/agent-loop-smoke.mjs` with the pure helpers:

```js
#!/usr/bin/env node
import { mkdirSync, realpathSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { loadManifest } from "./source-matching-fixtures.mjs";

export const repoRoot = fileURLToPath(new URL("..", import.meta.url));
export const defaultReportDir = join(repoRoot, "build/reports/fixthis-agent-loop");
export const defaultFixtureId = "reply";

export function parseArgs(argv = process.argv.slice(2)) {
  const result = {
    strict: false,
    fixtureId: null,
    reportDir: defaultReportDir,
    headed: false,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--strict") result.strict = true;
    else if (arg === "--headed") result.headed = true;
    else if (arg === "--fixture") {
      const value = argv[++index];
      if (!value) throw new Error("--fixture requires a fixture id");
      result.fixtureId = value;
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

export function selectAgentLoopFixture(manifest, fixtureId = null) {
  const id = fixtureId || defaultFixtureId;
  const fixture = (manifest.fixtures || []).find((candidate) => candidate.id === id);
  if (!fixture) throw new Error(`Unknown fixture id: ${id}`);
  if (!hasRuntimeTrustCase(fixture)) throw new Error(`${id} is not an installable runtime agent-loop fixture`);
  return fixture;
}

export function expectedResolutionPlan(itemIds) {
  const statuses = ["resolved", "needs_clarification", "wont_fix"];
  return itemIds.slice(0, 3).map((itemId, index) => {
    const status = statuses[index];
    const label = status === "needs_clarification" ? "needs clarification for" : status === "wont_fix" ? "will not fix" : "resolved";
    return {
      itemId,
      status,
      summary: `Agent loop smoke ${label} ${itemId}`,
    };
  });
}

export function assertCopiedPromptProtocol(markdown) {
  const failures = [];
  const sessionId = markdown.match(/(^|\n)\s*session_id:\s*["']?([^"'\n\r]+)/)?.[2]?.trim() || null;
  const itemIds = [...markdown.matchAll(/(^|\n)\s*-?\s*id:\s*["']?([^"'\n\r]+)/g)].map((match) => match[2].trim()).filter(Boolean);
  if (!sessionId) failures.push("missing session_id");
  if (itemIds.length === 0) failures.push("missing item id");
  if (!markdown.includes("agent_protocol:")) failures.push("missing agent_protocol");
  if (failures.length > 0) throw new Error(failures.join("; "));
  return { sessionId, itemIds };
}

export function assertLifecycleSessionState(session, resolutionPlan) {
  const items = Array.isArray(session?.items) ? session.items : [];
  const counts = { resolved: 0, needsClarification: 0, wontFix: 0 };
  for (const expected of resolutionPlan) {
    const item = items.find((candidate) => candidate.itemId === expected.itemId);
    if (!item) throw new Error(`missing item ${expected.itemId}`);
    if (item.status !== expected.status) throw new Error(`${expected.itemId} status expected ${expected.status} but was ${item.status}`);
    if (item.agentSummary !== expected.summary) throw new Error(`${expected.itemId} summary expected ${expected.summary} but was ${item.agentSummary}`);
    if (expected.status === "resolved") counts.resolved += 1;
    if (expected.status === "needs_clarification") counts.needsClarification += 1;
    if (expected.status === "wont_fix") counts.wontFix += 1;
  }
  return counts;
}

export function statusForEnvironment({ strict, androidReady, reason }) {
  if (androidReady) return { status: "pass", exitCode: 0, failures: [] };
  return {
    status: strict ? "fail" : "deferred",
    exitCode: strict ? 1 : 0,
    failures: [reason || "Android environment is unavailable."],
  };
}

export function buildReport({ strict, device = null, startedAt, finishedAt, fixture }) {
  const status = fixture.status === "pass" ? "pass" : fixture.status === "deferred" ? "deferred" : "fail";
  return {
    status,
    strict,
    device,
    startedAt,
    finishedAt,
    fixture,
    failures: fixture.failures || [],
  };
}

export function renderMarkdownReport(report) {
  const lines = [
    "# FixThis Agent Loop Smoke",
    "",
    `- Status: ${report.status}`,
    `- Strict: ${report.strict}`,
    `- Device: ${report.device || "unavailable"}`,
    `- Started: ${report.startedAt}`,
    `- Finished: ${report.finishedAt}`,
    "",
    "| Fixture | Package | Status | Saved | Resolved | Needs clarification | Won't fix |",
    "|---|---|---:|---:|---:|---:|---:|",
  ];
  const fixture = report.fixture || {};
  lines.push(`| ${fixture.fixtureId || ""} | ${fixture.packageName || ""} | ${fixture.status || ""} | ${fixture.savedItemCount || 0} | ${fixture.resolved || 0} | ${fixture.needsClarification || 0} | ${fixture.wontFix || 0} |`);
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

export async function runSmoke(options) {
  const fixture = selectAgentLoopFixture(loadManifest(), options.fixtureId);
  return {
    report: buildReport({
      strict: options.strict,
      startedAt: new Date().toISOString(),
      finishedAt: new Date().toISOString(),
      fixture: {
        fixtureId: fixture.id,
        packageName: fixture.applicationId,
        status: "fail",
        failures: ["agent_loop_runtime_not_implemented"],
      },
    }),
    exitCode: 1,
  };
}

export async function main(argv = process.argv.slice(2)) {
  const options = parseArgs(argv);
  const { report, exitCode } = await runSmoke(options);
  writeReports(report, options.reportDir);
  console.log(renderMarkdownReport(report));
  return exitCode;
}

const invokedAsCli = process.argv[1] ? fileURLToPath(import.meta.url) === realpathSync(process.argv[1]) : false;
if (invokedAsCli) {
  process.exitCode = await main();
}
```

- [ ] **Step 4: Run pure tests**

Run:

```bash
node --test scripts/agent-loop-smoke-test.mjs
```

Expected: PASS. The connected CLI still exits fail because runtime is intentionally not implemented until Task 5.

- [ ] **Step 5: Commit**

```bash
git add scripts/agent-loop-smoke.mjs scripts/agent-loop-smoke-test.mjs
git commit -m "test(smoke): add agent loop smoke contracts"
```

---

## Task 5: Implement Connected Agent Loop Smoke

**Files:**
- Modify: `scripts/agent-loop-smoke.mjs`
- Modify: `scripts/agent-loop-smoke-test.mjs`
- Modify: `scripts/evidence-runner.mjs`
- Modify: `scripts/evidence-runner-test.mjs`

- [ ] **Step 1: Add focused tests for lifecycle report shape**

In `scripts/agent-loop-smoke-test.mjs`, add:

```js
test("buildReport fails when lifecycle assertions fail", () => {
  const report = buildReport({
    strict: true,
    device: "emulator-5554",
    startedAt: "2026-05-31T00:00:00.000Z",
    finishedAt: "2026-05-31T00:01:00.000Z",
    fixture: {
      fixtureId: "reply",
      packageName: "com.example.reply",
      status: "fail",
      failures: ["item-1 status expected resolved but was in_progress"],
    },
  });

  assert.equal(report.status, "fail");
  assert.match(renderMarkdownReport(report), /item-1 status expected resolved/);
});
```

- [ ] **Step 2: Run tests**

Run:

```bash
node --test scripts/agent-loop-smoke-test.mjs
```

Expected: PASS before runtime implementation.

- [ ] **Step 3: Implement Android preflight and MCP launch**

In `scripts/agent-loop-smoke.mjs`, add imports:

```js
import { resolveAndroidEnvironment } from "./evidence-runner.mjs";
import { createMcpJsonRpcClient } from "./mcp-json-rpc-client.mjs";
import { installRuntimeFixture, runCommand } from "./source-matching-fixtures.mjs";
```

Add helpers:

```js
function withAndroidEnvironment(options = {}, environment = {}) {
  return {
    ...options,
    env: {
      ...(options.env || {}),
      ...(environment.envPatch || {}),
    },
  };
}

function ensureMcpDistribution() {
  runCommand("./gradlew", [":fixthis-mcp:installDist", "--no-daemon"], { cwd: repoRoot, stdio: "inherit" });
  return join(repoRoot, "fixthis-mcp/build/install/fixthis-mcp/bin/fixthis-mcp");
}
```

- [ ] **Step 4: Add browser lifecycle flow**

Add these functions to `scripts/agent-loop-smoke.mjs`:

```js
async function waitForReadyConsole(page) {
  await page.waitForSelector("#connectionPrimaryAction", { timeout: 60_000 });
  const connectionState = await page.getAttribute("#connectionCard", "data-connection-state");
  if (connectionState !== "ready") {
    await page.evaluate(() => document.getElementById("connectionPrimaryAction")?.click());
  }
  await page.waitForFunction(
    () => document.getElementById("connectionCard")?.dataset.connectionState === "ready",
    undefined,
    { timeout: 60_000 },
  );
  await page.waitForFunction(
    () => {
      const image = document.getElementById("snapshotImage");
      return Boolean(image?.complete && image.naturalWidth > 0 && image.naturalHeight > 0);
    },
    undefined,
    { timeout: 90_000 },
  );
}

async function addAreaAnnotation(page, comment, index) {
  if (await page.getAttribute("#snapshot", "data-tool-mode") !== "annotate") {
    const startAnnotating = page.locator("[data-start-annotating]").first();
    if (await startAnnotating.count() > 0 && await startAnnotating.isVisible()) {
      await startAnnotating.click();
    } else {
      await page.click("#annotateToolButton");
    }
    await page.waitForSelector("#snapshot[data-tool-mode=\"annotate\"]", { timeout: 20_000 });
  }
  const box = await page.locator("#snapshotImage").boundingBox();
  if (!box) throw new Error("Snapshot image is not visible");
  const startX = box.x + box.width * (0.18 + index * 0.18);
  const startY = box.y + box.height * (0.22 + index * 0.12);
  await page.mouse.move(startX, startY);
  await page.mouse.down();
  await page.mouse.move(startX + box.width * 0.24, startY + box.height * 0.16, { steps: 5 });
  await page.mouse.up();
  await page.waitForSelector("#annotationCommentInput", { timeout: 20_000 });
  await page.fill("#annotationCommentInput", comment);
  await page.waitForFunction(
    (expected) => window.FixThisConsoleDebug?.getDraftWorkspace?.()?.items?.some((item) => item.comment === expected),
    comment,
    { timeout: 20_000 },
  );
  const backToAnnotations = page.locator("[data-back-annotations]").first();
  if (await backToAnnotations.count() > 0) {
    await backToAnnotations.click();
  }
}

async function browserSaveAndCopyFlow({ consoleUrl, headed, comments }) {
  const { chromium } = await import("playwright");
  const browser = await chromium.launch({ headless: !headed });
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
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
    for (let index = 0; index < comments.length; index += 1) {
      await addAreaAnnotation(page, comments[index], index);
    }
    await page.waitForFunction(() => !document.querySelector('[data-testid="save-to-mcp-button"]')?.disabled, undefined, { timeout: 20_000 });
    await page.getByTestId("save-to-mcp-button").click();
    await page.waitForFunction(() => document.querySelector('[data-testid="global-status"]')?.textContent?.includes("Saved to MCP"), undefined, { timeout: 20_000 });
    await page.waitForFunction(() => !document.getElementById("copyPromptButton").disabled, undefined, { timeout: 20_000 });
    await page.click("#copyPromptButton");
    await page.waitForFunction(() => window.__fixthisCopiedText?.includes("agent_protocol:"), undefined, { timeout: 30_000 });
    const copiedText = await page.evaluate(() => window.__fixthisCopiedText);
    return {
      copiedText,
      session: await page.evaluate(() => window.FixThisConsoleDebug?.getState?.()?.session),
      async waitForResolvedStatuses(expectedPlan) {
        await page.waitForFunction(
          (plan) => {
            const items = window.FixThisConsoleDebug?.getState?.()?.session?.items || [];
            return plan.every((expected) => {
              const item = items.find((candidate) => candidate.itemId === expected.itemId);
              return item?.status === expected.status && item?.agentSummary === expected.summary;
            });
          },
          expectedPlan,
          { timeout: 30_000 },
        );
        const bodyText = await page.locator("#pendingItems, #draftItems").first().textContent();
        return {
          session: await page.evaluate(() => window.FixThisConsoleDebug?.getState?.()?.session),
          bodyText,
        };
      },
      close: () => browser.close(),
    };
  } catch (error) {
    await browser.close();
    throw error;
  }
}
```

- [ ] **Step 5: Implement runtime smoke orchestration**

Replace the `runSmoke` skeleton with:

```js
export async function runSmoke(options) {
  const startedAt = new Date().toISOString();
  const environment = resolveAndroidEnvironment();
  const preflight = statusForEnvironment({
    strict: options.strict,
    androidReady: environment.ready,
    reason: environment.reason,
  });
  if (!environment.ready) {
    const report = buildReport({
      strict: options.strict,
      device: null,
      startedAt,
      finishedAt: new Date().toISOString(),
      fixture: {
        fixtureId: options.fixtureId || defaultFixtureId,
        status: preflight.status,
        failures: preflight.failures,
      },
    });
    writeReports(report, options.reportDir);
    return { report, exitCode: preflight.exitCode };
  }

  const fixture = selectAgentLoopFixture(loadManifest(), options.fixtureId);
  const mcpBin = ensureMcpDistribution();
  const runWithAndroidEnvironment = (command, args, runOptions = {}) =>
    runCommand(command, args, withAndroidEnvironment(runOptions, environment));
  const paths = installRuntimeFixture(fixture, {
    stdio: "inherit",
    run: runWithAndroidEnvironment,
  });
  let mcp = null;
  let browserFlow = null;
  const fixtureResult = {
    fixtureId: fixture.id,
    packageName: fixture.applicationId,
    status: "fail",
    failures: [],
  };
  try {
    mcp = await createMcpJsonRpcClient({
      command: mcpBin,
      args: ["--project-dir", paths.projectWorkDir, "--package", fixture.applicationId],
      cwd: repoRoot,
      env: { ...process.env, ...environment.envPatch },
      clientInfo: { name: "agent-loop-smoke", version: "0" },
    });
    const opened = await mcp.callTool("fixthis_open_feedback_console", {
      packageName: fixture.applicationId,
      newSession: true,
    });
    const comments = ["Agent loop smoke resolved", "Agent loop smoke question", "Agent loop smoke not fixed"];
    browserFlow = await browserSaveAndCopyFlow({
      consoleUrl: opened.consoleUrl,
      headed: options.headed,
      comments,
    });
    const protocol = assertCopiedPromptProtocol(browserFlow.copiedText);
    const plan = expectedResolutionPlan(protocol.itemIds);
    for (const item of plan) {
      await mcp.callTool("fixthis_claim_feedback", {
        sessionId: protocol.sessionId,
        itemId: item.itemId,
        agentNote: `Agent loop smoke claiming ${item.itemId}`,
      });
      await mcp.callTool("fixthis_resolve_feedback", {
        sessionId: protocol.sessionId,
        itemId: item.itemId,
        status: item.status,
        summary: item.summary,
      });
    }
    const reflected = await browserFlow.waitForResolvedStatuses(plan);
    const counts = assertLifecycleSessionState(reflected.session, plan);
    if (!reflected.bodyText.includes("Resolved") || !reflected.bodyText.includes("Needs Clarification") || !reflected.bodyText.includes("Won't Fix")) {
      throw new Error(`Console did not render all terminal statuses: ${reflected.bodyText}`);
    }
    Object.assign(fixtureResult, {
      status: "pass",
      savedItemCount: protocol.itemIds.length,
      ...counts,
    });
  } catch (error) {
    fixtureResult.failures.push(error.message);
  } finally {
    await browserFlow?.close?.();
    await mcp?.close?.();
  }

  const report = buildReport({
    strict: options.strict,
    device: environment.device || null,
    startedAt,
    finishedAt: new Date().toISOString(),
    fixture: fixtureResult,
  });
  writeReports(report, options.reportDir);
  return { report, exitCode: report.status === "fail" ? 1 : 0 };
}
```

- [ ] **Step 6: Add trust profile step**

In `scripts/evidence-runner.mjs`, add to the `trust` profile after real Copy Prompt smoke:

```js
    step("Agent loop smoke", "npm run agent-loop:smoke -- --strict", {
      deferrable: true,
      requiresAndroid: true,
    }),
```

In `scripts/evidence-runner-test.mjs`, extend `trust profile includes source matching and runtime strict as deferrable`:

```js
  const agentLoop = steps.find((step) => step.command === "npm run agent-loop:smoke -- --strict");
  assert.equal(agentLoop.deferrable, true);
  assert.equal(agentLoop.requiresAndroid, true);
```

- [ ] **Step 7: Run local tests**

Run:

```bash
node --test scripts/agent-loop-smoke-test.mjs scripts/mcp-json-rpc-client-test.mjs scripts/evidence-runner-test.mjs
npm run agent-loop:smoke -- --strict
```

Expected: Node tests PASS. Strict smoke PASS when a ready emulator/device is available; otherwise FAIL with a clear Android environment reason.

- [ ] **Step 8: Commit**

```bash
git add scripts/agent-loop-smoke.mjs scripts/agent-loop-smoke-test.mjs scripts/evidence-runner.mjs scripts/evidence-runner-test.mjs
git commit -m "test(smoke): verify external agent lifecycle loop"
```

---

## Task 6: Emit Runtime Trust Call-Site Observations

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustFixtureModels.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustObservationMapperTest.kt`
- Modify: `scripts/source-matching-fixtures.mjs`
- Modify: `scripts/source-matching-fixtures-test.mjs`

- [ ] **Step 1: Write failing Kotlin mapper test**

In `RuntimeTrustObservationMapperTest.kt`, add:

```kotlin
    @Test
    fun mapsRecommendedCallSitesToObservedOutput() {
        val observed = RuntimeTrustObservationMapper.fromAnnotation(
            AnnotationDto(
                itemId = "item-1",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Node("header", FixThisRect(0f, 0f, 100f, 100f)),
                comment = "runtime fixture",
                sourceCandidates = listOf(
                    SourceCandidate(
                        file = "sample/components/StudioHeader.kt",
                        score = 0.8,
                        confidence = SelectionConfidence.MEDIUM,
                        callSites = listOf(
                            io.github.beyondwin.fixthis.compose.core.model.SourceLocationRef(
                                file = "sample/screens/DiagnosticsScreen.kt",
                                line = 42,
                                mostLikely = true,
                                recommendedEditSite = true,
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(RuntimeTrustCallSite("sample/screens/DiagnosticsScreen.kt", 42, mostLikely = true, recommendedEditSite = true)),
            observed.callSites,
        )
    }
```

- [ ] **Step 2: Run Kotlin test to verify failure**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*RuntimeTrustObservationMapperTest" --no-daemon
```

Expected: FAIL because `RuntimeTrustCallSite` and `RuntimeTrustObserved.callSites` do not exist.

- [ ] **Step 3: Add observed call-site model and mapper**

In `RuntimeTrustFixtureModels.kt`, update `RuntimeTrustObserved` and add `RuntimeTrustCallSite`:

```kotlin
@Serializable
data class RuntimeTrustObserved(
    val candidates: List<RuntimeTrustCandidate> = emptyList(),
    val confidence: String? = null,
    val sourceConfidence: String? = null,
    val riskFlags: List<String>? = null,
    val warnings: List<String>? = null,
    val callSites: List<RuntimeTrustCallSite>? = null,
)

@Serializable
data class RuntimeTrustCallSite(
    val file: String,
    val line: Int? = null,
    val mostLikely: Boolean = false,
    val recommendedEditSite: Boolean = false,
)
```

In `RuntimeTrustObservationMapper.fromAnnotation`, add `callSites`:

```kotlin
            callSites = top?.callSites?.map { site ->
                RuntimeTrustCallSite(
                    file = site.file,
                    line = site.line,
                    mostLikely = site.mostLikely,
                    recommendedEditSite = site.recommendedEditSite,
                )
            },
```

- [ ] **Step 4: Make missing recommended edit-site observation fail**

In `scripts/source-matching-fixtures.mjs`, replace this block in `classifyRuntimeTrustOutcome`:

```js
    if (!hasOwn(observed, "callSites")) {
      addUnique(outcome.environment, trustObservationNotConfigured);
```

with:

```js
    if (!hasOwn(observed, "callSites")) {
      addUnique(outcome.failures, "missing_call_site_observation");
```

- [ ] **Step 5: Add Node classification test**

In `scripts/source-matching-fixtures-test.mjs`, add:

```js
test("classifyRuntimeTrustOutcome fails missing recommended edit-site observations", () => {
  const result = classifyRuntimeTrustOutcome({
    expectedRecommendedEditSiteContains: "DiagnosticsScreen.kt",
  }, {
    candidates: [{ path: "sample/components/StudioHeader.kt" }],
  });

  assert.deepEqual(result.failures, ["missing_call_site_observation"]);
  assert.deepEqual(result.environment, []);
});
```

- [ ] **Step 6: Run tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*RuntimeTrustObservationMapperTest" --no-daemon
node --test scripts/source-matching-fixtures-test.mjs
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustFixtureModels.kt fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustObservationMapperTest.kt scripts/source-matching-fixtures.mjs scripts/source-matching-fixtures-test.mjs
git commit -m "test(trust): observe runtime recommended call sites"
```

---

## Task 7: Add Visual-Area Runtime Trust Cases

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustFixtureModels.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustFixtureRunner.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustFixtureRunnerTest.kt`
- Modify: `scripts/source-matching-fixtures.mjs`
- Modify: `scripts/source-matching-fixtures-test.mjs`
- Modify: `fixtures/source-matching/manifest.json`

- [ ] **Step 1: Add failing visual-area runner test**

In `RuntimeTrustFixtureRunnerTest.kt`, add:

```kotlin
    @Test
    fun visualAreaRuntimeTargetCreatesAreaFeedback() {
        val bridge = FakeFixThisBridge()
        val runner = RuntimeTrustFixtureRunner(
            bridgeFactory = { bridge },
            captureRetryPolicy = RuntimeCaptureRetryPolicy(maxAttempts = 1, retryDelayMillis = 0),
            delay = {},
        )

        val output = runner.run(
            RuntimeTrustFixtureInput(
                projectDir = "/repo",
                packageName = "io.github.beyondwin.fixthis.sample",
                cases = listOf(
                    RuntimeTrustCaseInput(
                        caseId = "visual-area",
                        runtimeTarget = RuntimeTargetSelector(
                            visualArea = RuntimeVisualAreaSelector(left = 12f, top = 24f, right = 180f, bottom = 96f),
                        ),
                    ),
                ),
                strict = true,
            ),
        )

        assertEquals("evaluated", output.status)
        assertEquals("medium", output.cases.single().observed?.confidence)
        assertEquals(listOf("VISUAL_AREA_ONLY"), output.cases.single().observed?.warnings)
    }
```

- [ ] **Step 2: Run Kotlin test to verify failure**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*RuntimeTrustFixtureRunnerTest" --no-daemon
```

Expected: FAIL because `RuntimeVisualAreaSelector` and `visualArea` do not exist.

- [ ] **Step 3: Add runtime visual-area model**

In `RuntimeTrustFixtureModels.kt`, update `RuntimeTargetSelector` and add the selector:

```kotlin
@Serializable
data class RuntimeTargetSelector(
    val text: String? = null,
    val testTag: String? = null,
    val contentDescription: String? = null,
    val role: String? = null,
    val visualArea: RuntimeVisualAreaSelector? = null,
)

@Serializable
data class RuntimeVisualAreaSelector(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun rect(): FixThisRect = FixThisRect(left, top, right, bottom)
}
```

Add import:

```kotlin
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
```

- [ ] **Step 4: Route visual-area cases in runner**

In `RuntimeTrustFixtureRunner.run`, replace the per-case body with:

```kotlin
            try {
                val item = if (testCase.runtimeTarget.visualArea != null) {
                    service.addAreaFeedback(
                        sessionId = session.sessionId,
                        screenId = screen.screenId,
                        bounds = testCase.runtimeTarget.visualArea.rect(),
                        comment = "Runtime trust fixture ${testCase.caseId}",
                    )
                } else {
                    val node = RuntimeTargetResolver.resolve(screen, testCase.runtimeTarget)
                    service.addFeedbackItem(
                        sessionId = session.sessionId,
                        screenId = screen.screenId,
                        targetType = FeedbackTargetType.NODE,
                        bounds = node.boundsInWindow,
                        nodeUid = node.uid,
                        comment = "Runtime trust fixture ${testCase.caseId}",
                    )
                }
                RuntimeTrustCaseOutput(
                    caseId = testCase.caseId,
                    observed = RuntimeTrustObservationMapper.fromAnnotation(item),
                )
            } catch (error: RuntimeTargetResolutionException) {
                RuntimeTrustCaseOutput(caseId = testCase.caseId, failures = listOf(error.code))
            }
```

- [ ] **Step 5: Extend manifest validation for visual areas**

In `scripts/source-matching-fixtures.mjs`, add `visualArea`:

```js
const runtimeTargetFields = new Set(["text", "testTag", "contentDescription", "role", "visualArea"]);
const visualAreaFields = new Set(["left", "top", "right", "bottom"]);
```

Inside `validateRuntimeTarget`, add:

```js
  if (target.visualArea !== undefined) {
    if (!target.visualArea || typeof target.visualArea !== "object" || Array.isArray(target.visualArea)) {
      errors.push(`${label} runtimeTarget.visualArea must be an object`);
    } else {
      for (const key of Object.keys(target.visualArea)) {
        if (!visualAreaFields.has(key)) errors.push(`${label} runtimeTarget.visualArea contains unsupported field ${key}`);
      }
      for (const key of visualAreaFields) {
        if (typeof target.visualArea[key] !== "number") errors.push(`${label} runtimeTarget.visualArea.${key} must be a number`);
      }
      if (target.visualArea.right <= target.visualArea.left || target.visualArea.bottom <= target.visualArea.top) {
        errors.push(`${label} runtimeTarget.visualArea must have positive width and height`);
      }
    }
  }
```

Change string validation loop to skip `visualArea`:

```js
    } else if (key !== "visualArea" && (typeof target[key] !== "string" || target[key].length === 0)) {
```

- [ ] **Step 6: Add Node validation tests**

In `scripts/source-matching-fixtures-test.mjs`, add:

```js
test("validateManifest accepts visual-area runtime targets and rejects malformed bounds", () => {
  assert.doesNotThrow(() => validateManifest({
    schemaVersion: 2,
    fixtures: [{
      id: "visual",
      source: "local-project",
      projectDir: ".",
      modulePath: ":app",
      moduleDir: "sample",
      variant: "debug",
      applicationId: "io.github.beyondwin.fixthis.sample",
      cases: [{
        id: "visual-area-runtime",
        mode: "runtime-trust",
        trustPurpose: "visual area target stays caveated",
        runtimeTarget: { visualArea: { left: 10, top: 20, right: 110, bottom: 120 } },
        expectedConfidence: "low-or-medium",
        mustWarn: ["VISUAL_AREA_ONLY"],
        mustNotHighConfidence: true,
      }],
    }],
  }));

  assert.throws(() => validateManifest({
    schemaVersion: 2,
    fixtures: [{
      id: "bad-visual",
      source: "local-project",
      projectDir: ".",
      modulePath: ":app",
      moduleDir: "sample",
      variant: "debug",
      applicationId: "io.github.beyondwin.fixthis.sample",
      cases: [{
        id: "bad-visual-area-runtime",
        mode: "runtime-trust",
        trustPurpose: "bad visual area",
        runtimeTarget: { visualArea: { left: 10, top: 20, right: 9, bottom: 120 } },
      }],
    }],
  }), /runtimeTarget.visualArea must have positive width and height/);
});
```

- [ ] **Step 7: Add manifest runtime trust cases**

In `fixtures/source-matching/manifest.json`, add to the `fixthis-sample` fixture cases:

```json
        {
          "id": "fixthis-sample-diagnostics-visual-area",
          "mode": "runtime-trust",
          "trustPurpose": "visual-only Diagnostics sparkline area remains caveated and never promotes nearby source context to high confidence",
          "runtimeTarget": { "visualArea": { "left": 24, "top": 160, "right": 360, "bottom": 260 } },
          "expectedConfidence": "low-or-medium",
          "mustWarn": ["VISUAL_AREA_ONLY"],
          "mustNotHighConfidence": true
        },
        {
          "id": "fixthis-sample-diagnostics-androidview-interop",
          "mode": "runtime-trust",
          "trustPurpose": "AndroidView interop target stays caveated as possible View ownership rather than exact Compose source ownership",
          "runtimeTarget": { "contentDescription": "Native AndroidView target" },
          "expectedTop3PathContains": "sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/DiagnosticsScreen.kt",
          "expectedConfidence": "low-or-medium",
          "mustWarn": ["POSSIBLE_VIEW_INTEROP"],
          "mustNotHighConfidence": true
        }
```

- [ ] **Step 8: Run tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*RuntimeTrustFixtureRunnerTest" --tests "*RuntimeTrustObservationMapperTest" --no-daemon
node --test scripts/source-matching-fixtures-test.mjs
```

Expected: PASS.

- [ ] **Step 9: Run runtime strict when a device is available**

Run:

```bash
npm run source-matching:fixtures:runtime -- --strict
```

Expected: PASS with a ready emulator/device. If no device is available, record the exact Android environment failure in the task notes and continue only if the user accepts deferred connected evidence.

- [ ] **Step 10: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustFixtureModels.kt fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustFixtureRunner.kt fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustFixtureRunnerTest.kt scripts/source-matching-fixtures.mjs scripts/source-matching-fixtures-test.mjs fixtures/source-matching/manifest.json
git commit -m "test(trust): add visual-area runtime trust fixtures"
```

---

## Task 8: Document Runtime Trust And Roadmap

**Files:**
- Modify: `docs/guides/source-matching-fixture-lab.md`
- Modify: `docs/product/roadmap.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Add fixture-lab documentation**

In `docs/guides/source-matching-fixture-lab.md`, add a section under runtime trust fixtures:

````markdown
### Runtime trust case purposes

Every `runtime-trust` case must describe the trust failure mode it protects in
`trustPurpose`. Cases may assert a positive source candidate, but they may also
assert that confidence remains low or medium, a warning remains present, or an
exact-source claim is not made.

Visual-area cases use:

```json
{
  "runtimeTarget": {
    "visualArea": { "left": 24, "top": 160, "right": 360, "bottom": 260 }
  },
  "mustWarn": ["VISUAL_AREA_ONLY"],
  "mustNotHighConfidence": true
}
```

Interop-risk cases should prefer a runtime target that lands on the boundary
host and require `POSSIBLE_VIEW_INTEROP`. Shared-component cases should assert
`expectedRecommendedEditSiteContains` only when the runtime observation emits a
single `recommendedEditSite=true` call site.
````

- [ ] **Step 2: Add roadmap note**

In `docs/product/roadmap.md`, add under High-priority Work:

```markdown
### v1.1 trust loop evidence

The next post-v1.0 hardening line is the trust loop evidence pack: release
reality checks, an external agent lifecycle smoke from handoff through resolve,
and runtime source-trust fixtures for shared-component, interop-risk, and
visual-area cases. This line strengthens evidence for existing public channels;
it does not add a new package channel.
```

- [ ] **Step 3: Add changelog entry**

In `CHANGELOG.md` under `## Unreleased`, add:

```markdown
### Added

- Planned the v1.1 Trust Loop evidence line: release reality checks, an external
  agent lifecycle smoke from handoff through claim/resolve, and runtime
  source-trust fixtures for shared-component, interop-risk, and visual-area
  calibration.
```

- [ ] **Step 4: Run docs checks**

Run:

```bash
node scripts/check-doc-consistency.mjs
node scripts/check-release-readiness.mjs
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add docs/guides/source-matching-fixture-lab.md docs/product/roadmap.md CHANGELOG.md
git commit -m "docs(trust): explain v1.1 runtime trust evidence"
```

---

## Task 9: Final Evidence Integration And Graph Update

**Files:**
- Modify: `docs/superpowers/plans/2026-05-31-v11-trust-loop-umbrella.md`
- Generated but not committed: `build/reports/fixthis-*`
- Generated but not committed: `graphify-out/*`

- [ ] **Step 1: Run fast local verification**

Run:

```bash
npm run release:reality
npm run agent-loop:smoke:test
npm run source-matching:fixtures:test
npm run evidence:test
node scripts/check-release-readiness.mjs
node scripts/check-doc-consistency.mjs
git diff --check
```

Expected: PASS. `npm run release:reality` may return `pass_with_deferred` in non-strict mode; that is acceptable only when the markdown report lists the deferred surfaces and reasons.

- [ ] **Step 2: Run connected verification**

Run:

```bash
npm run real-copy-prompt:smoke -- --strict
npm run agent-loop:smoke -- --strict
npm run source-matching:fixtures:runtime -- --strict
```

Expected: PASS with a ready emulator/device. If a connected command fails because Android SDK or a device is unavailable, rerun `adb devices`, record the exact environment output, and do not claim strict connected evidence passed.

- [ ] **Step 3: Run release profile**

Run:

```bash
npm run evidence:release
```

Expected: PASS or `pass_with_deferred` from the nested release reality report. The evidence runner itself must exit 0 only when no step reports failed command status.

- [ ] **Step 4: Update Graphify**

Run:

```bash
graphify update .
```

Expected: command completes. Do not stage `graphify-out/`.

- [ ] **Step 5: Mark plan tasks complete**

Edit this file and change completed checkboxes from `- [ ]` to `- [x]` for all tasks that landed. Leave any deferred connected evidence checkbox unchecked and add the reason directly under that step.

- [ ] **Step 6: Final status and commit**

Run:

```bash
git status --short
```

Expected: only intended source/doc files are modified; `.fixthis/`, `build/reports/`, `graphify-out/`, and local fixture workspaces are not staged.

Commit:

```bash
git add docs/superpowers/plans/2026-05-31-v11-trust-loop-umbrella.md
git commit -m "docs: record v1.1 trust loop implementation progress"
```

If source/doc changes from previous tasks are already committed and only generated artifacts remain, do not create this final progress commit.

---

## Plan Self-Review

- **Spec coverage:** Track A is covered by Tasks 1-2. Track B is covered by Tasks 3-5. Track C is covered by Tasks 6-8. Cross-track evidence and Graphify are covered by Task 9.
- **Compatibility:** Persisted MCP/session fields remain additive. Runtime fixture output adds optional `callSites` and optional `visualArea` input support.
- **Strict runtime behavior:** Android/device absence remains strict failure for connected commands and non-strict deferral through evidence profiles.
- **Execution boundary:** This plan does not tag, publish, or add a new package channel.
