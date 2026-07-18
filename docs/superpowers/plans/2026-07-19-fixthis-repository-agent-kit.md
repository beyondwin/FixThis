# FixThis Repository Agent Kit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Build a repository-only Agent Kit that routes FixThis coding tasks to the right maintained context and verification, keeps guidance aligned with current commands, and standardizes completion evidence without modifying Codex configuration.

**Architecture:** Add one dependency-free Node.js route registry and read-only CLI as the executable source for task-to-doc/source/check mapping. Keep durable rules in root and narrowly nested AGENTS.md files, reusable repository workflows in .agents/skills, and enforce budgets, commands, audiences, and high-risk invariants with node:test contracts wired into local and CI gates.

**Tech Stack:** Markdown, Node.js 20 ESM, node:test, existing npm scripts, Bash, GitHub Actions.

**Spec:** docs/superpowers/specs/2026-07-19-fixthis-repository-agent-kit-design.md

## Global Constraints

- Do not create or modify ~/.codex, project .codex/config.toml, Codex hooks, global skills, installed plugins, personal memories, or global MCP servers.
- Do not add automatic commit, push, merge, release, server termination, emulator creation, or destructive Git recovery.
- Current implementation and docs/reference/* remain authoritative; historical plans never override them.
- Root AGENTS.md must be at most 130 physical lines; each first-pass nested AGENTS.md must be at most 60 physical lines.
- Add nested guidance only under fixthis-compose-core, fixthis-compose-sidekick, fixthis-mcp, and scripts.
- .agents/skills contains auto-discovered repository workflows. .codex-plugin/skills remains an installable bundle with explicit per-skill audiences.
- Plugin install, feedback, and evidence skills target external apps. fixthis-release-smoke is the explicit maintainer-only exception.
- npm run android:proof -- --strict is the canonical connected product-path proof. DEFERRED and SKIPPED never equal PASS.
- The router is read-only: it never runs returned checks, edits files, launches or stops processes, or mutates Git.
- Unknown paths return a warning plus npm run ci:local:changed; they never yield an empty success.
- Do not commit .fixthis/, reports, screenshots, generated fixtures, or personal agent state.
- Use TDD for every behavior change and keep commits scoped to the task boundaries below.

---

## File Structure

### Executable routing

- Create scripts/agent-route-registry.mjs for route data, canonical commands, selection, and ordered deduplication.
- Create scripts/agent-task-router.mjs for CLI parsing, Git preflight, report construction, and JSON/Markdown rendering.
- Create scripts/agent-task-router-test.mjs for route, fallback, state, proof, parser, and renderer tests.

### Guidance and workflow

- Create scripts/agent-guidance-contract-test.mjs for budgets, required phases, route validity, skill metadata, and audience checks.
- Refactor root AGENTS.md and create four narrow nested AGENTS.md files.
- Create .agents/skills/fixthis-repository-change/SKILL.md and .agents/skills/fixthis-release-maintenance/SKILL.md.
- Tighten .codex-plugin/skills/fixthis-release-smoke/SKILL.md and its existing contract test.

### Integration

- Modify package.json, scripts/verify-ci-local.sh, scripts/verify-ci-local-test.mjs, and .github/workflows/ci.yml.
- Update CONTRIBUTING.md, docs/index.md, docs/guides/project-map.md, docs/architecture/agent-code-compass.md, and docs/releases/unreleased.md.

---

### Task 1: Add The Read-Only Task And Verification Router

**Files:**
- Create: scripts/agent-route-registry.mjs
- Create: scripts/agent-task-router.mjs
- Create: scripts/agent-task-router-test.mjs
- Modify: package.json:7-81

**Interfaces:**
- Produces: CONNECTED_PROOF_COMMAND, SAFE_FALLBACK_COMMAND, ROUTES, orderedUnique(values), selectRoutes({ task, changedFiles }), parseArgs(argv), collectRepositoryState(options), buildAgentRouteReport(options), and renderAgentRouteMarkdown(report).
- Consumed by: Tasks 2 through 5.

- [ ] **Step 1: Write failing route and report tests**

Create scripts/agent-task-router-test.mjs:

~~~js
import assert from "node:assert/strict";
import test from "node:test";
import {
  buildAgentRouteReport,
  collectChangedFiles,
  collectRepositoryState,
  parseArgs,
  renderAgentRouteMarkdown,
} from "./agent-task-router.mjs";
import {
  CONNECTED_PROOF_COMMAND,
  SAFE_FALLBACK_COMMAND,
  selectRoutes,
} from "./agent-route-registry.mjs";

const state = {
  branch: "main",
  upstream: "origin/main",
  dirty: false,
  worktreeCount: 1,
};

test("task and paths select ordered deduplicated routes", () => {
  const routes = selectRoutes({
    task: "console",
    changedFiles: [
      "fixthis-mcp/src/main/console/app.js",
      "fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt",
      "fixthis-mcp/src/main/console/app.js",
    ],
  });
  assert.deepEqual(routes.map((route) => route.id), ["mcp-session", "console"]);
});

test("unknown paths return safe fallback and warning", () => {
  const report = buildAgentRouteReport({
    changedFiles: ["future-module/src/new.txt"],
    repositoryState: { ...state, dirty: true, worktreeCount: 2 },
  });
  assert.deepEqual(report.routes, []);
  assert.deepEqual(report.focusedChecks, []);
  assert.equal(report.broadGate, SAFE_FALLBACK_COMMAND);
  assert.match(report.warnings[0], /No route matched future-module/);
});

test("Git preflight warnings force the safe fallback", () => {
  const report = buildAgentRouteReport({
    changedFiles: [],
    repositoryState: state,
    preflightWarnings: ["Git diff is unavailable."],
  });
  assert.equal(report.broadGate, SAFE_FALLBACK_COMMAND);
  assert.deepEqual(report.warnings, ["Git diff is unavailable."]);
});

test("android and release require canonical connected proof", () => {
  for (const task of ["android-runtime", "release"]) {
    const report = buildAgentRouteReport({
      task,
      changedFiles: [],
      repositoryState: state,
    });
    assert.equal(report.connectedProof.required, true);
    assert.equal(report.connectedProof.command, CONNECTED_PROOF_COMMAND);
    assert.match(report.connectedProof.reason, new RegExp(task === "release" ? "release" : "android-runtime"));
  }
});

test("parseArgs accepts task base changed and json", () => {
  assert.deepEqual(
    parseArgs(["--task", "console", "--base", "origin/main", "--changed", "--json"]),
    { task: "console", base: "origin/main", changed: true, json: true },
  );
  assert.throws(() => parseArgs(["--task"]), /--task requires a value/);
  assert.throws(() => parseArgs(["--unknown"]), /Unknown argument: --unknown/);
});

test("repository state preserves missing upstream and worktree count", () => {
  const outputs = new Map([
    ["branch --show-current", { status: 0, stdout: "topic\n", stderr: "" }],
    ["rev-parse --abbrev-ref --symbolic-full-name @{upstream}", { status: 128, stdout: "", stderr: "no upstream" }],
    ["status --porcelain", { status: 0, stdout: " M AGENTS.md\n", stderr: "" }],
    ["worktree list --porcelain", { status: 0, stdout: "worktree /repo\nHEAD a\n\nworktree /tmp/other\nHEAD b\n", stderr: "" }],
  ]);
  const snapshot = collectRepositoryState({
    runGit: (args) => outputs.get(args.join(" ")),
  });
  assert.deepEqual(snapshot, {
    repositoryState: {
      branch: "topic",
      upstream: null,
      dirty: true,
      worktreeCount: 2,
    },
    warnings: ["Git upstream is unavailable; explicit --base is recommended."],
  });
});

test("changed files union committed staged and working paths", () => {
  const outputs = new Map([
    ["merge-base HEAD origin/main", { status: 0, stdout: "base-sha\n", stderr: "" }],
    ["diff --name-only base-sha..HEAD", { status: 0, stdout: "AGENTS.md\n", stderr: "" }],
    ["diff --name-only --cached", { status: 0, stdout: "scripts/agent-task-router.mjs\n", stderr: "" }],
    ["diff --name-only", { status: 0, stdout: "AGENTS.md\n", stderr: "" }],
  ]);
  const actual = collectChangedFiles({
    base: "origin/main",
    runGit: (args) => outputs.get(args.join(" ")),
  });
  assert.deepEqual(actual, {
    changedFiles: ["AGENTS.md", "scripts/agent-task-router.mjs"],
    warnings: [],
  });
});

test("markdown exposes explicit completion evidence states", () => {
  const report = buildAgentRouteReport({
    task: "docs",
    changedFiles: ["README.md"],
    repositoryState: state,
  });
  const markdown = renderAgentRouteMarkdown(report);
  assert.match(markdown, /PASS .* FAIL .* DEFERRED .* SKIPPED/);
  assert.match(markdown, /Residual risk/);
});
~~~

- [ ] **Step 2: Run RED**

~~~bash
node --test scripts/agent-task-router-test.mjs
~~~

Expected: FAIL with ERR_MODULE_NOT_FOUND for the two new modules.

- [ ] **Step 3: Implement the route registry**

Create scripts/agent-route-registry.mjs. Use this exact data contract and include every route below:

~~~js
export const CONNECTED_PROOF_COMMAND = "npm run android:proof -- --strict";
export const SAFE_FALLBACK_COMMAND = "npm run ci:local:changed";

export const ROUTES = Object.freeze([
  {
    id: "core-source",
    tasks: ["core", "source-matching", "target-reliability"],
    pathPrefixes: ["fixthis-compose-core/"],
    docs: [
      "docs/reference/source-matching.md",
      "docs/reference/output-schema.md",
    ],
    sources: [
      "fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt",
      "fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/target/TargetReliabilityCalculator.kt",
    ],
    focusedChecks: [
      "./gradlew :fixthis-compose-core:test --no-daemon",
      "npm run source-matching:fixtures:test",
    ],
    broadGate: SAFE_FALLBACK_COMMAND,
    connectedProof: false,
  },
  {
    id: "mcp-session",
    tasks: ["mcp", "session", "queue"],
    pathPrefixes: ["fixthis-mcp/"],
    docs: [
      "docs/reference/output-schema.md",
      "docs/reference/mcp-tools.md",
      "docs/architecture/adr/0008-session-package-decomposition.md",
    ],
    sources: [
      "fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt",
      "fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/FeedbackSessionStore.kt",
    ],
    focusedChecks: [
      "./gradlew :fixthis-mcp:test --tests '*session*' --no-daemon",
      "./gradlew :fixthis-mcp:test --tests '*architecture*' --no-daemon",
    ],
    broadGate: SAFE_FALLBACK_COMMAND,
    connectedProof: false,
  },
  {
    id: "handoff",
    tasks: ["handoff", "prompt", "output-schema"],
    pathPrefixes: [
      "fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/handoff/",
    ],
    docs: [
      "docs/reference/feedback-console-contract.md",
      "docs/design/handoff-prompt-rationale.md",
      "docs/reference/output-schema.md",
    ],
    sources: [
      "fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/handoff/CompactHandoffRenderer.kt",
      "fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatter.kt",
    ],
    focusedChecks: [
      "npm run handoff:eval:test",
      "./gradlew :fixthis-mcp:test --tests '*Handoff*' --no-daemon",
    ],
    broadGate: SAFE_FALLBACK_COMMAND,
    connectedProof: false,
  },
  {
    id: "console",
    tasks: ["console", "browser", "sse"],
    pathPrefixes: [
      "fixthis-mcp/src/main/console/",
      "fixthis-mcp/src/main/resources/console/",
      "fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/",
      "scripts/console",
    ],
    docs: [
      "docs/reference/feedback-console-contract.md",
      "docs/architecture/console-state-sync-design.md",
    ],
    sources: [
      "fixthis-mcp/src/main/console/app.js",
      "fixthis-mcp/src/main/console/events.js",
    ],
    focusedChecks: [
      "npm run console:test:fast",
      "node scripts/build-console-assets.mjs --check",
      "./gradlew :fixthis-mcp:test --tests '*console*' --no-daemon",
    ],
    broadGate: SAFE_FALLBACK_COMMAND,
    connectedProof: false,
  },
  {
    id: "cli-setup",
    tasks: ["cli", "setup", "install-agent", "doctor"],
    pathPrefixes: ["fixthis-cli/"],
    docs: [
      "docs/reference/cli.md",
      "docs/reference/agent-setup-schema.md",
      "docs/getting-started/add-to-your-app.md",
    ],
    sources: [
      "fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentCommand.kt",
      "fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommand.kt",
    ],
    focusedChecks: [
      "./gradlew :fixthis-cli:test --no-daemon",
      "bash scripts/check-docs-cli-surface.sh",
      "npm run docs:agent-bootstrap:test",
    ],
    broadGate: SAFE_FALLBACK_COMMAND,
    connectedProof: false,
  },
  {
    id: "android-runtime",
    tasks: ["android-runtime", "sidekick", "bridge", "connected-proof"],
    pathPrefixes: ["fixthis-compose-sidekick/"],
    docs: [
      "docs/reference/bridge-protocol.md",
      "docs/architecture/overview.md",
      "docs/guides/troubleshooting.md",
    ],
    sources: [
      "fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt",
      "fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/BridgeClient.kt",
    ],
    focusedChecks: [
      "./gradlew :fixthis-compose-sidekick:testDebugUnitTest :fixthis-cli:test --no-daemon",
    ],
    broadGate: SAFE_FALLBACK_COMMAND,
    connectedProof: true,
  },
  {
    id: "gradle-plugin",
    tasks: ["gradle-plugin", "source-index"],
    pathPrefixes: ["fixthis-gradle-plugin/"],
    docs: [
      "docs/reference/source-matching.md",
      "docs/reference/compatibility.md",
    ],
    sources: [
      "fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/FixThisGradlePlugin.kt",
      "fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScanner.kt",
    ],
    focusedChecks: [
      "./gradlew :fixthis-gradle-plugin:test --no-daemon",
      "npm run source-matching:fixtures:test",
    ],
    broadGate: SAFE_FALLBACK_COMMAND,
    connectedProof: false,
  },
  {
    id: "release-docs",
    tasks: ["release-docs"],
    pathPrefixes: [
      "docs/contributing/release",
      "docs/releases/",
      "CHANGELOG.md",
    ],
    docs: [
      "docs/contributing/release-readiness.md",
      "docs/contributing/release-process.md",
      "CONTRIBUTING.md",
    ],
    sources: [
      "scripts/check-release-readiness.mjs",
      "scripts/release-drift-guard.mjs",
    ],
    focusedChecks: [
      "node scripts/check-release-readiness.mjs",
      "npm run release:drift",
    ],
    broadGate: SAFE_FALLBACK_COMMAND,
    connectedProof: false,
  },
  {
    id: "release",
    tasks: ["release", "publication", "compatibility"],
    pathPrefixes: [
      ".github/workflows/publish-",
      ".github/workflows/release-",
      "gradle.properties",
    ],
    docs: [
      "docs/contributing/release-readiness.md",
      "docs/contributing/release-process.md",
      "CONTRIBUTING.md",
    ],
    sources: [
      "scripts/check-release-readiness.mjs",
      "scripts/release-reality-check.mjs",
      "scripts/release-gate.mjs",
    ],
    focusedChecks: [
      "npm run release:reality",
      "npm run evidence:release",
    ],
    broadGate: "npm run release:check",
    connectedProof: true,
  },
  {
    id: "architecture",
    tasks: ["architecture", "boundary", "adr"],
    pathPrefixes: [
      "docs/architecture/",
      "fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/",
    ],
    docs: [
      "docs/architecture/adr/README.md",
      "docs/architecture/agent-code-compass.md",
    ],
    sources: [
      "fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/ModuleBoundaryTest.kt",
      "fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/SessionPackageBoundaryTest.kt",
    ],
    focusedChecks: [
      "./gradlew :fixthis-mcp:test --tests '*architecture*' --no-daemon",
      "git diff --check",
    ],
    broadGate: SAFE_FALLBACK_COMMAND,
    connectedProof: false,
  },
  {
    id: "docs",
    tasks: ["docs", "documentation"],
    pathPrefixes: [
      "docs/",
      "README.md",
      "AGENTS.md",
      "CLAUDE.md",
      "CONTRIBUTING.md",
      ".codex-plugin/",
    ],
    docs: [
      "docs/index.md",
      "docs/guides/project-map.md",
    ],
    sources: [
      "scripts/check-doc-consistency.mjs",
      "scripts/agent-bootstrap-contract-test.mjs",
      "scripts/fixthis-plugin-contract-test.mjs",
    ],
    focusedChecks: [
      "node scripts/check-doc-consistency.mjs",
      "npm run docs:agent-bootstrap:test",
      "npm run plugin:contract:test",
    ],
    broadGate: SAFE_FALLBACK_COMMAND,
    connectedProof: false,
  },
]);

export function orderedUnique(values) {
  return [...new Set(values)];
}

function routeMatchesFile(route, file) {
  const nestedGuidance = file.endsWith("/AGENTS.md");
  if (nestedGuidance && !["agent-guidance", "docs"].includes(route.id)) {
    return false;
  }
  return route.pathPrefixes.some((prefix) => file.startsWith(prefix));
}

export function selectRoutes({ task = null, changedFiles = [] } = {}) {
  const normalizedTask = task?.trim().toLowerCase() || null;
  const normalizedFiles = orderedUnique(
    changedFiles.map((file) => file.trim()).filter(Boolean),
  );
  return ROUTES.filter((route) =>
    (normalizedTask && route.tasks.includes(normalizedTask)) ||
    normalizedFiles.some((file) => routeMatchesFile(route, file)),
  );
}
~~~

- [ ] **Step 4: Implement repository-state and report pure functions**

Create scripts/agent-task-router.mjs. Export and use these exact report behaviors:

~~~js
export function collectRepositoryState({
  cwd = process.cwd(),
  runGit = defaultRunGit,
} = {}) {
  const branchResult = runGit(["branch", "--show-current"], cwd);
  const upstreamResult = runGit(
    ["rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}"],
    cwd,
  );
  const statusResult = runGit(["status", "--porcelain"], cwd);
  const worktreeResult = runGit(["worktree", "list", "--porcelain"], cwd);
  const stdout = (result) =>
    typeof result.stdout === "string" ? result.stdout : "";
  const branch = branchResult.status === 0
    ? stdout(branchResult).trim() || null
    : null;
  const upstream = upstreamResult.status === 0
    ? stdout(upstreamResult).trim() || null
    : null;
  const warnings = [];
  if (branchResult.status !== 0) warnings.push("Git branch is unavailable.");
  if (!upstream) {
    warnings.push("Git upstream is unavailable; explicit --base is recommended.");
  }
  if (statusResult.status !== 0) {
    warnings.push("Git status is unavailable; treat the worktree as dirty.");
  }
  if (worktreeResult.status !== 0) {
    warnings.push("Git worktree inventory is unavailable.");
  }
  return {
    repositoryState: {
      branch,
      upstream,
      dirty: statusResult.status !== 0 || stdout(statusResult).trim().length > 0,
      worktreeCount: stdout(worktreeResult)
        .split("\n")
        .filter((line) => line.startsWith("worktree ")).length,
    },
    warnings,
  };
}

export function buildAgentRouteReport({
  task = null,
  changedFiles = [],
  repositoryState,
  preflightWarnings = [],
}) {
  const routes = selectRoutes({ task, changedFiles });
  const focusedChecks = orderedUnique(
    routes.flatMap((route) => route.focusedChecks),
  );
  const unmatched = changedFiles.filter((file) =>
    !routes.some((route) =>
      route.pathPrefixes.some((prefix) => file.startsWith(prefix)),
    ),
  );
  const connectedRoutes = routes.filter((route) => route.connectedProof);
  const broadGate = routes.some(
    (route) => route.broadGate === "npm run release:check",
  )
    ? "npm run release:check"
    : routes.length > 0 || unmatched.length > 0 || preflightWarnings.length > 0
      ? SAFE_FALLBACK_COMMAND
      : null;
  return {
    schemaVersion: "1.0",
    repositoryState,
    routes: routes.map(({ id, docs, sources }) => ({ id, docs, sources })),
    focusedChecks,
    broadGate,
    connectedProof: {
      required: connectedRoutes.length > 0,
      command: CONNECTED_PROOF_COMMAND,
      reason: connectedRoutes.length > 0
        ? "Required by " + connectedRoutes.map((route) => route.id).join(", ")
        : null,
    },
    warnings: [
      ...preflightWarnings,
      ...unmatched.map(
        (file) =>
          "No route matched " + file + "; use " +
          SAFE_FALLBACK_COMMAND + " and inspect the change manually.",
      ),
    ],
  };
}
~~~

- [ ] **Step 5: Implement argument parsing and changed-file collection**

Add these complete parser and Git helpers above the report functions:

~~~js
import { spawnSync } from "node:child_process";
import { pathToFileURL } from "node:url";

function defaultRunGit(args, cwd) {
  return spawnSync("git", args, { cwd, encoding: "utf8" });
}

export function parseArgs(argv) {
  const options = { task: null, base: null, changed: false, json: false };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--changed") options.changed = true;
    else if (arg === "--json") options.json = true;
    else if (arg === "--task" || arg === "--base") {
      const value = argv[index + 1];
      if (!value || value.startsWith("--")) {
        throw new Error(arg + " requires a value");
      }
      options[arg === "--task" ? "task" : "base"] = value;
      index += 1;
    } else {
      throw new Error("Unknown argument: " + arg);
    }
  }
  return options;
}

function lines(text) {
  return text.split("\n").map((line) => line.trim()).filter(Boolean);
}

export function collectChangedFiles({
  cwd = process.cwd(),
  base = null,
  upstream = null,
  runGit = defaultRunGit,
} = {}) {
  const warnings = [];
  let target = base || upstream;
  if (!target) {
    const parent = runGit(["rev-parse", "--verify", "HEAD^"], cwd);
    target = parent.status === 0 ? "HEAD^" : "HEAD";
  }
  let mergeBase = target;
  if (target !== "HEAD" && target !== "HEAD^") {
    const result = runGit(["merge-base", "HEAD", target], cwd);
    if (result.status === 0) mergeBase = result.stdout.trim();
    else warnings.push("Unable to resolve merge-base for " + target + ".");
  }
  const commands = [
    ["diff", "--name-only", mergeBase + "..HEAD"],
    ["diff", "--name-only", "--cached"],
    ["diff", "--name-only"],
  ];
  const changedFiles = [];
  for (const args of commands) {
    const result = runGit(args, cwd);
    if (result.status === 0) changedFiles.push(...lines(result.stdout));
    else warnings.push("Git command failed: git " + args.join(" "));
  }
  return { changedFiles: orderedUnique(changedFiles), warnings };
}
~~~

Run the focused parser/state tests:

~~~bash
node --test --test-name-pattern="parseArgs|repository state|changed files" scripts/agent-task-router-test.mjs
~~~

Expected: those tests PASS; report/renderer tests may still fail.

- [ ] **Step 6: Implement Markdown rendering and the direct CLI entry point**

renderAgentRouteMarkdown(report) must print repository state, routes, docs, source files, checks, broad gate, connected proof, warnings, and this template:

~~~text
| Command | Status | Evidence | Reason | Residual risk |
| --- | --- | --- | --- | --- |
| npm run agent:route:test | PASS | node:test reports 5 pass, 0 fail |  | None |

Allowed status: PASS | FAIL | DEFERRED | SKIPPED
~~~

Implement rendering and the direct entry point as follows. Returned checks are
only strings in output; no child process executes them.

~~~js
export function renderAgentRouteMarkdown(report) {
  const output = [
    "# FixThis Agent Route",
    "",
    "- Branch: " + (report.repositoryState.branch || "detached"),
    "- Upstream: " + (report.repositoryState.upstream || "none"),
    "- Dirty: " + report.repositoryState.dirty,
    "- Worktrees: " + report.repositoryState.worktreeCount,
    "",
    "## Routes",
  ];
  for (const route of report.routes) {
    output.push("", "### " + route.id, "", "Docs:");
    output.push(...route.docs.map((path) => "- " + path));
    output.push("", "First source files:");
    output.push(...route.sources.map((path) => "- " + path));
  }
  output.push("", "## Focused Checks");
  output.push(...report.focusedChecks.map((command) => "- " + command));
  output.push("", "## Broad Gate", "", report.broadGate || "None");
  output.push(
    "",
    "## Connected Proof",
    "",
    "- Required: " + report.connectedProof.required,
    "- Command: " + report.connectedProof.command,
    "- Reason: " + (report.connectedProof.reason || "Not required"),
  );
  if (report.warnings.length > 0) {
    output.push("", "## Warnings");
    output.push(...report.warnings.map((warning) => "- " + warning));
  }
  output.push(
    "",
    "## Completion Evidence",
    "",
    "| Command | Status | Evidence | Reason | Residual risk |",
    "| --- | --- | --- | --- | --- |",
    "| npm run agent:route:test | PASS | node:test reports 5 pass, 0 fail |  | None |",
    "",
    "Allowed status: PASS | FAIL | DEFERRED | SKIPPED",
  );
  return output.join("\n") + "\n";
}

export function main(argv = process.argv.slice(2), cwd = process.cwd()) {
  try {
    const options = parseArgs(argv);
    const snapshot = collectRepositoryState({ cwd });
    const changes = options.changed
      ? collectChangedFiles({
          cwd,
          base: options.base,
          upstream: snapshot.repositoryState.upstream,
        })
      : { changedFiles: [], warnings: [] };
    const report = buildAgentRouteReport({
      task: options.task,
      changedFiles: changes.changedFiles,
      repositoryState: snapshot.repositoryState,
      preflightWarnings: [...snapshot.warnings, ...changes.warnings],
    });
    process.stdout.write(
      options.json
        ? JSON.stringify(report, null, 2) + "\n"
        : renderAgentRouteMarkdown(report),
    );
    return 0;
  } catch (error) {
    process.stderr.write(error.message + "\n");
    return 2;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = main();
}
~~~

- [ ] **Step 7: Add package commands and run GREEN**

Add to package.json:

~~~json
"agent:route": "node scripts/agent-task-router.mjs",
"agent:route:test": "node --test scripts/agent-task-router-test.mjs"
~~~

Run:

~~~bash
npm run agent:route:test
npm run agent:route -- --task console --json
npm run agent:route -- --changed --base origin/main
~~~

Expected: router tests PASS; both CLI commands exit 0 and only display commands.

- [ ] **Step 8: Commit**

~~~bash
git add package.json scripts/agent-route-registry.mjs scripts/agent-task-router.mjs scripts/agent-task-router-test.mjs
git commit -m "feat(agent): add repository task router"
~~~

---

### Task 2: Close Installable Plugin Semantic Drift

**Files:**
- Modify: .codex-plugin/plugin.json:1-30
- Modify: .codex-plugin/skills/fixthis-release-smoke/SKILL.md:1-24
- Modify: scripts/fixthis-plugin-contract-test.mjs:1-45

**Interfaces:**
- Consumes: Task 1 CONNECTED_PROOF_COMMAND.
- Produces: plugin version 0.1.1 and a maintainer-only release workflow bound to canonical commands.

- [ ] **Step 1: Write failing semantic and audience tests**

Extend scripts/fixthis-plugin-contract-test.mjs:

~~~js
import { CONNECTED_PROOF_COMMAND } from "./agent-route-registry.mjs";

const externalAppSkills = [
  "fixthis-install-agent",
  "fixthis-feedback-loop",
  "fixthis-android-evidence",
];

test("plugin version advances with workflow semantics", () => {
  const manifest = JSON.parse(read(join(pluginRoot, "plugin.json")));
  assert.equal(manifest.version, "0.1.1");
});

test("release smoke declares checkout audience and canonical commands", () => {
  const body = read(
    join(pluginRoot, "skills", "fixthis-release-smoke", "SKILL.md"),
  );
  assert.match(body, /FixThis source checkout/);
  for (const command of [
    "npm run release:reality",
    "npm run evidence:release",
    CONNECTED_PROOF_COMMAND + " --continue",
    "npm run release:check",
  ]) {
    assert.ok(body.includes(command), "release smoke missing " + command);
  }
});

test("external app skills exclude repository-only gates", () => {
  for (const skill of externalAppSkills) {
    const body = read(join(pluginRoot, "skills", skill, "SKILL.md"));
    assert.doesNotMatch(
      body,
      /npm run (?:ci:local|release:check|android:proof)|\.\/gradlew :fixthis/,
    );
  }
});
~~~

Change the old manifest assertion from 0.1.0 to 0.1.1 so there is one expectation.

- [ ] **Step 2: Run RED**

~~~bash
npm run plugin:contract:test
~~~

Expected: FAIL on manifest version and missing release commands.

- [ ] **Step 3: Update plugin version and release workflow**

Set .codex-plugin/plugin.json version to 0.1.1. Replace the release skill body after front matter with:

~~~markdown
# FixThis Release Smoke

Use only from a FixThis source checkout when a maintainer asks to validate release readiness or public release reality.

Rules:
- FixThis remains debug-only and Jetpack Compose-only.
- Do not commit `.fixthis/`.
- Separate repository checks, connected Android proof, and live registry reality.
- DEFERRED and SKIPPED are not PASS.
- Do not tag, publish, push, or edit a downstream repository unless explicitly requested.
- Use docs/contributing/release-readiness.md as the maintained release contract.

Workflow:
1. Run npm run release:reality and record live/public evidence separately.
2. Run npm run evidence:release for the repository evidence profile.
3. Run npm run android:proof -- --strict --continue when connected proof is required.
4. Run npm run release:check before a release PR or tag.
5. Report PASS, FAIL, DEFERRED, and SKIPPED separately with reasons and residual risk.
~~~

- [ ] **Step 4: Run GREEN and Commit**

~~~bash
npm run plugin:contract:test
git add .codex-plugin/plugin.json .codex-plugin/skills/fixthis-release-smoke/SKILL.md scripts/fixthis-plugin-contract-test.mjs
git commit -m "fix(agent): bind release skill to current proof"
~~~

Expected: all plugin contract tests PASS.

---

### Task 3: Refactor Root Guidance And Add Narrow Nested Contracts

**Files:**
- Create: scripts/agent-guidance-contract-test.mjs
- Modify: scripts/agent-route-registry.mjs
- Modify: package.json:7-81
- Modify: AGENTS.md:1-160
- Create: fixthis-compose-core/AGENTS.md
- Create: fixthis-compose-sidekick/AGENTS.md
- Create: fixthis-mcp/AGENTS.md
- Create: scripts/AGENTS.md

**Interfaces:**
- Consumes: Task 1 route registry.
- Produces: root/nested durable guidance and npm run docs:agent-guidance:test.
- Consumed by: Tasks 4 and 5.

- [ ] **Step 1: Write failing guidance contract tests**

Create scripts/agent-guidance-contract-test.mjs:

~~~js
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import test from "node:test";
import {
  CONNECTED_PROOF_COMMAND,
  ROUTES,
  selectRoutes,
} from "./agent-route-registry.mjs";

const root = resolve(import.meta.dirname, "..");
const read = (path) => readFileSync(resolve(root, path), "utf8");
const lineCount = (text) => text.split("\n").length;
const nested = [
  "fixthis-compose-core/AGENTS.md",
  "fixthis-compose-sidekick/AGENTS.md",
  "fixthis-mcp/AGENTS.md",
  "scripts/AGENTS.md",
];
const allowedNonNpmCommands = new Set([
  "./gradlew :fixthis-compose-core:test --no-daemon",
  "./gradlew :fixthis-mcp:test --tests '*session*' --no-daemon",
  "./gradlew :fixthis-mcp:test --tests '*architecture*' --no-daemon",
  "./gradlew :fixthis-mcp:test --tests '*Handoff*' --no-daemon",
  "node scripts/build-console-assets.mjs --check",
  "./gradlew :fixthis-mcp:test --tests '*console*' --no-daemon",
  "./gradlew :fixthis-cli:test --no-daemon",
  "bash scripts/check-docs-cli-surface.sh",
  "./gradlew :fixthis-compose-sidekick:testDebugUnitTest :fixthis-cli:test --no-daemon",
  "./gradlew :fixthis-gradle-plugin:test --no-daemon",
  "node scripts/check-release-readiness.mjs",
  "git diff --check",
  "node scripts/check-doc-consistency.mjs",
]);

function npmScriptsIn(text) {
  return [...text.matchAll(/npm run ([\w:-]+)/g)].map((match) => match[1]);
}

test("root has required phases and stays within budget", () => {
  const body = read("AGENTS.md");
  assert.ok(lineCount(body) <= 130, "root lines=" + lineCount(body));
  for (const heading of ["## Start", "## Change", "## Verify", "## Finish"]) {
    assert.match(body, new RegExp("^" + heading + "$", "m"));
  }
  assert.match(body, /docs\/guides\/project-map\.md/);
  assert.match(body, /README\.md#quick-start-sample-app-to-agent-handoff/);
  assert.match(body, /git worktree list --porcelain/);
  assert.ok(body.includes(CONNECTED_PROOF_COMMAND));
});

test("approved nested guidance exists and stays local and small", () => {
  for (const path of nested) {
    assert.ok(existsSync(resolve(root, path)), path + " must exist");
    const body = read(path);
    assert.ok(lineCount(body) <= 60, path + " lines=" + lineCount(body));
    assert.doesNotMatch(body, /^## (Start|Change|Verify|Finish)$/m);
  }
});

test("route docs sources and npm scripts resolve", () => {
  const pkg = JSON.parse(read("package.json"));
  for (const route of ROUTES) {
    for (const path of [...route.docs, ...route.sources]) {
      assert.ok(existsSync(resolve(root, path)), route.id + " missing " + path);
    }
    const commandText = route.focusedChecks.join("\n") + "\n" + route.broadGate;
    for (const script of npmScriptsIn(commandText)) {
      assert.ok(pkg.scripts[script], route.id + " missing npm script " + script);
    }
    for (const command of [...route.focusedChecks, route.broadGate]) {
      if (!command.startsWith("npm run ")) {
        assert.ok(
          allowedNonNpmCommands.has(command),
          route.id + " has unapproved command " + command,
        );
      }
    }
  }
});

test("guidance npm commands exist in package scripts", () => {
  const pkg = JSON.parse(read("package.json"));
  const bodies = [read("AGENTS.md"), ...nested.map(read)];
  for (const script of npmScriptsIn(bodies.join("\n"))) {
    assert.ok(pkg.scripts[script], "guidance references missing " + script);
  }
});

test("high-risk routes retain canonical invariants", () => {
  const byId = Object.fromEntries(
    ROUTES.map((route) => [route.id, route]),
  );
  assert.equal(byId["android-runtime"].connectedProof, true);
  assert.equal(byId.release.connectedProof, true);
  assert.ok(
    byId.console.focusedChecks.includes(
      "node scripts/build-console-assets.mjs --check",
    ),
  );
  assert.ok(byId.handoff.docs.includes("docs/reference/output-schema.md"));
  assert.ok(byId.release.focusedChecks.includes("npm run release:reality"));
  assert.equal(byId["release-docs"].connectedProof, false);
  assert.deepEqual(
    selectRoutes({
      changedFiles: ["fixthis-compose-core/AGENTS.md"],
    }).map((route) => route.id),
    ["agent-guidance"],
  );
});
~~~

- [ ] **Step 2: Run RED after adding the package command**

Add:

~~~json
"docs:agent-guidance:test": "node --test scripts/agent-guidance-contract-test.mjs"
~~~

Run:

~~~bash
npm run docs:agent-guidance:test
~~~

Expected: FAIL because root AGENTS.md exceeds 130 lines and nested files are absent.

- [ ] **Step 3: Refactor root AGENTS.md**

Replace the tutorial-heavy root with one file assembled from the three exact
blocks in this step, keeping it at or below 130 physical lines. Begin with:

~~~markdown
# FixThis — Agent Operating Contract

FixThis is a debug-only Jetpack Compose sidekick. The Android app shows connection status; selection, annotation, evidence, and handoff happen in the local desktop console.

## Read Order

1. Read this file.
2. Read [docs/index.md](docs/index.md) and [the project map](docs/guides/project-map.md).
3. Run npm run agent:route -- --task console for an explicit route or npm run agent:route -- --changed --json once paths are known.
4. Read only maintained references and first source files returned by the router.
5. Treat docs/superpowers/*, docs/specs/*, and docs/plans/* as historical context unless current source or maintained docs point there.

Current implementation wins over docs; docs/reference/* wins over historical planning.
~~~

Then add the four operating phases:

~~~markdown
## Start

- Run git status --short --branch, git log -5 --oneline --decorate, and git worktree list --porcelain before edits.
- Preserve unrelated dirty changes and check whether another worktree owns the task.
- Classify the request as explanation, diagnosis, implementation, review, release, connected proof, or feedback work.
- Do not turn diagnosis or review into code changes without authorization.

## Change

- Make the smallest compatible change and follow router-returned maintained references.
- Keep compose-core free of MCP, CLI, Android UI, browser DTO, and .fixthis dependencies.
- Preserve persisted field names and coordinate bridge changes through docs/reference/bridge-protocol.md.
- Use canonical generators; never hand-edit build outputs.
- Never commit .fixthis, screenshots, reports, fixture workspaces, or personal agent state.
- Do not modify ~/.codex or project .codex for repository work.

## Verify

- Run focused checks first, then rerun the router against the final diff.
- Run the returned broad gate and npm run android:proof -- --strict when required.
- Record PASS, FAIL, DEFERRED, or SKIPPED; the last two require reason and residual risk.
- Require fresh final-diff evidence before completion.

## Finish

- Re-read the final diff and run git diff --check.
- Recheck branch, upstream, dirty state, and worktrees.
- Report exact commands, results, report paths, residual risks, and local-versus-remote Git state.
- Commit, merge, push, publish, resolve feedback, or stop processes only when requested.
~~~

Finish the file with:

~~~markdown
## Product And Tool Boundaries

- Debug builds and Jetpack Compose only; no release runtime, Views, Flutter, or remote service in V1.
- Desktop fixthis-mcp owns HTTP, MCP, console state, and .fixthis; the Android app does not.
- Runtime evidence is local, bounded, redacted, and optional.
- Full tool signatures: [MCP tools](docs/reference/mcp-tools.md).
- Canonical contributor gates: [CONTRIBUTING.md](CONTRIBUTING.md).
- Sample-to-agent flow: [README Quick Start](README.md#quick-start-sample-app-to-agent-handoff).

## Feedback Queue

Read, claim, edit, verify, and resolve in that order. Claim before code changes to avoid duplicate work. Use [the agent guide](docs/guides/agents.md) for the maintained workflow.
~~~

- [ ] **Step 4: Add four nested guidance files**

Create fixthis-compose-core/AGENTS.md:

~~~markdown
# Compose Core Agent Notes

This subtree is pure Kotlin domain logic.

- Do not import Android UI, MCP, CLI, Gradle plugin, sidekick, browser DTO, or .fixthis types.
- Keep source scoring deterministic and persisted-schema-neutral.
- Start with docs/reference/source-matching.md and docs/reference/output-schema.md.
- Run ./gradlew :fixthis-compose-core:test --no-daemon.
- For source matching also run npm run source-matching:fixtures:test.
- Update the relevant ADR and architecture test with any boundary change.
~~~

Create fixthis-compose-sidekick/AGENTS.md:

~~~markdown
# Compose Sidekick Agent Notes

This subtree is the debug-only Android runtime inside the target app.

- Never add release-variant behavior.
- Do not move MCP session storage, HTTP, or browser state into the app.
- Coordinate bridge changes with the CLI and docs/reference/bridge-protocol.md.
- Run ./gradlew :fixthis-compose-sidekick:testDebugUnitTest :fixthis-cli:test --no-daemon.
- Run npm run android:proof -- --strict when the connected path changes.
- Report unavailable, unauthorized, offline, locked, or ambiguous devices explicitly.
~~~

Create fixthis-mcp/AGENTS.md:

~~~markdown
# MCP And Console Agent Notes

This subtree owns MCP, local HTTP, feedback sessions, handoff rendering, and queue persistence.

- Preserve persisted JSON names and backward decoding.
- Keep Copy Prompt and Save to MCP compact-handoff grammar aligned.
- For console edits run node scripts/build-console-assets.mjs and then its --check form.
- Run npm run console:test:fast and ./gradlew :fixthis-mcp:test --no-daemon as applicable.
- For handoff changes also run npm run handoff:eval:test.
- Do not expose raw runtime evidence in JSON, MCP Markdown, or logs.
~~~

Create scripts/AGENTS.md:

~~~markdown
# Script Agent Notes

Repository scripts are executable local and CI contracts.

- Keep Node scripts compatible with Node 20 ESM and dependency-light.
- Keep shell scripts non-interactive unless their interface says otherwise.
- Use stable exit codes and machine-readable reports for composed gates.
- Never turn a deferred environment into a pass.
- Put reports under build/reports or another ignored artifact directory.
- Add or update node:test coverage before behavior changes.
- Update package.json, CONTRIBUTING.md, local CI, and GitHub Actions together when a canonical command changes.
~~~

- [ ] **Step 5: Add agent-guidance route and run GREEN**

Add this route before docs in scripts/agent-route-registry.mjs:

~~~js
{
  id: "agent-guidance",
  tasks: ["agent", "agent-guidance", "agent-kit"],
  pathPrefixes: [
    ".agents/",
    ".codex-plugin/",
    "AGENTS.md",
    "fixthis-compose-core/AGENTS.md",
    "fixthis-compose-sidekick/AGENTS.md",
    "fixthis-mcp/AGENTS.md",
    "scripts/AGENTS.md",
    "scripts/agent-",
  ],
  docs: [
    "AGENTS.md",
    "docs/guides/project-map.md",
    "docs/architecture/agent-code-compass.md",
  ],
  sources: [
    "scripts/agent-route-registry.mjs",
    "scripts/agent-task-router.mjs",
    "scripts/agent-guidance-contract-test.mjs",
  ],
  focusedChecks: [
    "npm run agent:route:test",
    "npm run docs:agent-guidance:test",
    "npm run plugin:contract:test",
  ],
  broadGate: SAFE_FALLBACK_COMMAND,
  connectedProof: false,
},
~~~

Run:

~~~bash
npm run agent:route:test
npm run docs:agent-guidance:test
node scripts/check-doc-consistency.mjs
git diff --check
~~~

Expected: all tests PASS and budgets hold.

- [ ] **Step 6: Commit**

~~~bash
git add AGENTS.md package.json scripts/agent-route-registry.mjs scripts/agent-guidance-contract-test.mjs fixthis-compose-core/AGENTS.md fixthis-compose-sidekick/AGENTS.md fixthis-mcp/AGENTS.md scripts/AGENTS.md
git commit -m "docs(agent): add scoped operating contracts"
~~~

---

### Task 4: Add Auto-Discovered Repository Skills

**Files:**
- Create: .agents/skills/fixthis-repository-change/SKILL.md
- Create: .agents/skills/fixthis-release-maintenance/SKILL.md
- Modify: scripts/agent-guidance-contract-test.mjs

**Interfaces:**
- Consumes: Task 1 router and Task 3 evidence contract.
- Produces: two repo workflow entry points with non-overlapping triggers.

- [ ] **Step 1: Write failing skill tests**

Append:

~~~js
const repoSkills = [
  ".agents/skills/fixthis-repository-change/SKILL.md",
  ".agents/skills/fixthis-release-maintenance/SKILL.md",
];

test("repo skills expose metadata and canonical routing", () => {
  for (const path of repoSkills) {
    const body = read(path);
    assert.match(
      body,
      /^---\nname: [a-z0-9-]+\ndescription: .+\n---\n/,
    );
    assert.match(body, /npm run agent:route/);
    assert.doesNotMatch(
      body,
      /fixthis install-agent --project-dir \. --target all/,
    );
    assert.doesNotMatch(body, /modify .*~\/\.codex|edit .*~\/\.codex/i);
  }
});

test("release skill separates reality proof and state changes", () => {
  const body = read(
    ".agents/skills/fixthis-release-maintenance/SKILL.md",
  );
  for (const command of [
    "npm run release:reality",
    "npm run evidence:release",
    "npm run release:check",
  ]) {
    assert.ok(body.includes(command), "release skill missing " + command);
  }
  assert.match(
    body,
    /Tag, publish, push, or edit a downstream repository only when the user explicitly requests/,
  );
});
~~~

- [ ] **Step 2: Run RED**

~~~bash
npm run docs:agent-guidance:test
~~~

Expected: FAIL with ENOENT for .agents/skills.

- [ ] **Step 3: Create repository-change skill**

Create .agents/skills/fixthis-repository-change/SKILL.md:

~~~markdown
---
name: fixthis-repository-change
description: Route, implement, diagnose, review, and verify changes inside the FixThis source repository.
---

# FixThis Repository Change

Use for source, test, docs, architecture, console, CLI, sidekick, or Gradle-plugin work in this checkout. Do not use for installing FixThis into an external app.

1. Read root AGENTS.md and run its Git/worktree preflight.
2. Choose a matching task id from scripts/agent-route-registry.mjs; for console work run npm run agent:route -- --task console --json. Once paths are known use npm run agent:route -- --changed --base origin/main.
3. Read only returned maintained docs and first source files, then inspect current implementation.
4. Preserve unrelated dirty changes and make the smallest compatible change.
5. Write the failing focused test before behavior changes.
6. Run returned focused checks from cheapest to broadest.
7. Rerun npm run agent:route -- --changed --json against the final diff.
8. Run the returned broad gate and connected proof when required.
9. Report PASS, FAIL, DEFERRED, or SKIPPED with evidence, reason, and residual risk.
10. Recheck branch, upstream, dirty state, and worktrees.

Never modify personal or project Codex configuration in this workflow. Commit, merge, push, publish, resolve feedback, or stop a process only when requested.
~~~

- [ ] **Step 4: Create release-maintenance skill**

Create .agents/skills/fixthis-release-maintenance/SKILL.md:

~~~markdown
---
name: fixthis-release-maintenance
description: Audit or execute FixThis release, publication, compatibility, and public-install work from the source checkout.
---

# FixThis Release Maintenance

Use only for FixThis release work. Keep repository proof, connected Android proof, Git state, and public registry reality separate.

1. Read docs/contributing/release-readiness.md, docs/contributing/release-process.md, and CONTRIBUTING.md.
2. Run npm run agent:route -- --task release --json.
3. Establish intended version and channels without changing them.
4. Run npm run release:reality and record exact registry, tag, and downstream evidence.
5. Run npm run evidence:release for repository evidence.
6. Run npm run android:proof -- --strict --continue when the contract requires connected proof.
7. Run npm run release:check before a release PR or tag.
8. Report PASS, FAIL, DEFERRED, and SKIPPED separately with reason and residual risk.
9. Verify local HEAD, upstream, remote branch, tag, registry, and downstream state independently.

Tag, publish, push, or edit a downstream repository only when the user explicitly requests that state change. Never treat a proxy marker, cached page, deferred device check, or local commit as publication proof.
~~~

- [ ] **Step 5: Run GREEN and Commit**

~~~bash
npm run docs:agent-guidance:test
npm run agent:route -- --task agent-kit --json
git add .agents/skills/fixthis-repository-change/SKILL.md .agents/skills/fixthis-release-maintenance/SKILL.md scripts/agent-guidance-contract-test.mjs
git commit -m "feat(agent): add repository maintainer skills"
~~~

Expected: tests PASS and agent-guidance route requires no connected proof.

---

### Task 5: Wire Contracts Into Maintained Docs And Gates

**Files:**
- Modify: scripts/verify-ci-local-test.mjs:14-120
- Modify: scripts/verify-ci-local.sh:132-160
- Modify: .github/workflows/ci.yml:34-66
- Modify: CONTRIBUTING.md:100-130,220-280
- Modify: docs/index.md:8-28
- Modify: docs/guides/project-map.md:22-90
- Modify: docs/architecture/agent-code-compass.md:30-55
- Modify: docs/releases/unreleased.md

**Interfaces:**
- Consumes: Tasks 1 through 4.
- Produces: required local/CI enforcement and maintained discoverability.

- [ ] **Step 1: Update local-gate expectations first**

Insert immediately after node scripts/check-doc-consistency.mjs in both --fast and --prepush expected arrays:

~~~js
"npm run agent:route:test",
"npm run docs:agent-guidance:test",
"npm run plugin:contract:test",
~~~

Add:

~~~js
test("every local mode includes agent semantic contracts", () => {
  for (const mode of [
    "--prepush",
    "--fast",
    "--changed-only",
    "--full",
  ]) {
    const result = runVerify([mode, "--list"], {
      FIXTHIS_VERIFY_BASE: "HEAD",
      FIXTHIS_VERIFY_CHANGED_FILES: "AGENTS.md\n",
    });
    assert.equal(result.status, 0, result.stderr || result.stdout);
    const commands = commandLines(result.stdout);
    assert.ok(commands.includes("npm run agent:route:test"));
    assert.ok(commands.includes("npm run docs:agent-guidance:test"));
    assert.ok(commands.includes("npm run plugin:contract:test"));
  }
});
~~~

- [ ] **Step 2: Run RED**

~~~bash
npm run ci:local:test
~~~

Expected: FAIL because verify-ci-local.sh omits the commands.

- [ ] **Step 3: Wire every local mode**

Add immediately after doc consistency in both branches of scripts/verify-ci-local.sh:

~~~bash
run_step "npm run agent:route:test"
run_step "npm run docs:agent-guidance:test"
run_step "npm run plugin:contract:test"
~~~

Run npm run ci:local:test. Expected: PASS.

- [ ] **Step 4: Wire GitHub Actions**

Add after Doc consistency check in .github/workflows/ci.yml:

~~~yaml
      - name: Run agent task router tests
        run: npm run agent:route:test

      - name: Run agent guidance contract tests
        run: npm run docs:agent-guidance:test

      - name: Run installable plugin contract tests
        run: npm run plugin:contract:test
~~~

Keep these in Console JavaScript; do not create a new status context.

- [ ] **Step 5: Document the Agent Kit in CONTRIBUTING.md**

Add this subsection to CONTRIBUTING.md after Docs ↔ CLI surface check:

~~~~markdown
### Repository Agent Kit

The repository Agent Kit is checked-in guidance and read-only routing; it does not modify personal or project Codex configuration.

~~~bash
npm run agent:route -- --task console --json
npm run agent:route -- --changed --base origin/main
npm run agent:route:test
npm run docs:agent-guidance:test
npm run plugin:contract:test
~~~

The router prints maintained docs, first source files, focused checks, broad gates, and connected-proof requirements; it never executes them. Unknown paths warn and fall back to npm run ci:local:changed. Contract tests enforce the root 130-line budget, nested 60-line budgets, canonical commands, and skill audiences.

After editing AGENTS.md, a nested AGENTS.md, .agents/skills, .codex-plugin/skills, or scripts/agent-*, run all three focused test commands above.
~~~~

- [ ] **Step 6: Update repository-agent navigation**

Replace the docs/index.md repository-agent row with:

~~~markdown
| Work inside this repository as an agent | [AGENTS.md](../AGENTS.md), [Project map](guides/project-map.md) | Run npm run agent:route -- --task console for an explicit route or --changed --json once paths are known, then read the returned maintained references |
~~~

Add this row to docs/guides/project-map.md:

~~~markdown
| Repository agent guidance and routing | AGENTS.md, docs/architecture/agent-code-compass.md | scripts/agent-route-registry.mjs, scripts/agent-task-router.mjs, .agents/skills/ | npm run agent:route:test, npm run docs:agent-guidance:test, npm run plugin:contract:test |
~~~

Add this row to docs/architecture/agent-code-compass.md:

~~~markdown
| Repository agent guidance and routing | AGENTS.md, docs/guides/project-map.md | scripts/agent-route-registry.mjs, scripts/agent-task-router.mjs, scripts/agent-guidance-contract-test.mjs | Do not modify ~/.codex or project .codex; keep installable-plugin and repo-skill audiences explicit. | npm run agent:route:test, npm run docs:agent-guidance:test, npm run plugin:contract:test |
~~~

- [ ] **Step 7: Add the unreleased note**

Add this unreleased note without claiming plugin publication:

~~~markdown
- **Repository Agent Kit.** Added a read-only task and verification router, scoped AGENTS.md guidance, repo-discovered maintainer skills, and semantic drift gates for commands, line budgets, connected-proof requirements, and installable-plugin audiences.
~~~

- [ ] **Step 8: Run focused verification**

~~~bash
npm run agent:route:test
npm run docs:agent-guidance:test
npm run plugin:contract:test
npm run ci:local:test
node scripts/check-doc-consistency.mjs
npm run docs:agent-bootstrap:test
bash scripts/check-docs-cli-surface.sh
git diff --check
~~~

Expected: every command exits 0 and Node summaries report zero failures.

- [ ] **Step 9: Run final diff routing and broad gate**

~~~bash
npm run agent:route -- --changed --base HEAD~4 --json
npm run ci:local:changed
git status --short --branch
git diff --check
~~~

Expected:
- Routes include agent-guidance and docs.
- connectedProof.required is false because Android product behavior did not change.
- Changed-only gate exits 0 without connected proof.
- No .fixthis, .codex, report, screenshot, or unrelated file is staged.

- [ ] **Step 10: Commit integration**

~~~bash
git add .github/workflows/ci.yml CONTRIBUTING.md docs/index.md docs/guides/project-map.md docs/architecture/agent-code-compass.md docs/releases/unreleased.md scripts/verify-ci-local.sh scripts/verify-ci-local-test.mjs
git commit -m "chore(agent): enforce repository agent contracts"
~~~

---

## Final Review Checklist

- [ ] Router tests cover matched routes, unknown fallback, proof, parser, state, and rendering.
- [ ] Guidance tests cover budgets, route validity, high-risk invariants, nested scope, and skill audiences.
- [ ] Plugin contracts pass and plugin version is 0.1.1.
- [ ] Every local CI mode includes router, guidance, and plugin semantic tests.
- [ ] Doc consistency, bootstrap, CLI-surface, changed-only, and diff checks pass.
- [ ] Root is at most 130 lines; each approved nested file is at most 60.
- [ ] Final route report requires no Android proof for this guidance-only change.
- [ ] ~/.codex, project .codex, .fixthis, active servers, emulators, and unrelated worktrees are untouched.
- [ ] Final handoff separates local and remote Git state and does not push unless requested.
