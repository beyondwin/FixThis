import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
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

test("Git preflight warnings override the release gate", () => {
  const report = buildAgentRouteReport({
    task: "release",
    changedFiles: [],
    repositoryState: state,
    preflightWarnings: ["Git diff is unavailable."],
  });
  assert.equal(report.broadGate, SAFE_FALLBACK_COMMAND);
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

test("runtime evidence task and implementation paths select strict connected proof", () => {
  const explicit = buildAgentRouteReport({
    task: "runtime-evidence",
    changedFiles: [],
    repositoryState: state,
  });
  assert.deepEqual(explicit.routes.map((route) => route.id), ["runtime-evidence"]);
  assert.ok(explicit.focusedChecks.includes("npm run runtime-evidence:smoke:test"));
  assert.ok(explicit.focusedChecks.includes("npm run runtime-evidence:smoke -- --strict"));
  assert.ok(explicit.focusedChecks.includes(CONNECTED_PROOF_COMMAND));
  assert.equal(explicit.connectedProof.required, true);

  for (const file of [
    "fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/runtime/AndroidRuntimeEvidenceCollector.kt",
    "fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime/RuntimeEvidenceCaptureCoordinator.kt",
    "fixthis-mcp/src/main/console/runtimeEvidence.js",
  ]) {
    const report = buildAgentRouteReport({
      changedFiles: [file],
      repositoryState: state,
    });
    assert.ok(report.routes.some((route) => route.id === "runtime-evidence"), file);
    assert.equal(report.connectedProof.required, true, file);
  }

  const releaseDocs = buildAgentRouteReport({
    task: "release-docs",
    changedFiles: [],
    repositoryState: state,
  });
  assert.equal(releaseDocs.connectedProof.required, false);
});

test("unknown explicit task fails instead of returning an empty successful report", () => {
  assert.throws(
    () => buildAgentRouteReport({
      task: "future-task",
      changedFiles: [],
      repositoryState: state,
    }),
    /Unknown task "future-task".*npm run agent:route -- --task/i,
  );
  const cli = spawnSync(
    process.execPath,
    ["scripts/agent-task-router.mjs", "--task", "future-task", "--json"],
    { encoding: "utf8" },
  );
  assert.equal(cli.status, 2);
  assert.equal(cli.stdout, "");
  assert.match(cli.stderr, /Unknown task "future-task"/);
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

test("missing parent revision returns a preflight warning", () => {
  const outputs = new Map([
    ["rev-parse --verify HEAD^", { status: 128, stdout: "", stderr: "no parent" }],
    ["diff --name-only HEAD..HEAD", { status: 0, stdout: "", stderr: "" }],
    ["diff --name-only --cached", { status: 0, stdout: "", stderr: "" }],
    ["diff --name-only", { status: 0, stdout: "", stderr: "" }],
  ]);
  const changes = collectChangedFiles({
    runGit: (args) => outputs.get(args.join(" ")),
  });
  const report = buildAgentRouteReport({
    task: "release",
    changedFiles: changes.changedFiles,
    repositoryState: state,
    preflightWarnings: changes.warnings,
  });
  assert.deepEqual(changes.warnings, [
    "Git parent revision is unavailable; using HEAD as the change base.",
  ]);
  assert.equal(report.broadGate, SAFE_FALLBACK_COMMAND);
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
  assert.match(markdown, /NOT RUN|SKIPPED/);
  assert.doesNotMatch(markdown, /node:test reports \d+ pass/);
  assert.doesNotMatch(markdown, /\| npm run agent:route:test \| PASS \|/);
  assert.doesNotMatch(markdown, /\|\s*PASS\s*\|/);
});

test("agent kit integration surfaces use the narrow guidance route", () => {
  for (const file of [
    "scripts/verify-ci-local.sh",
    "scripts/verify-ci-local-test.mjs",
    "scripts/fixthis-plugin-contract-test.mjs",
    ".github/workflows/ci.yml",
    "package.json",
  ]) {
    assert.deepEqual(
      selectRoutes({ changedFiles: [file] }).map((route) => route.id),
      ["agent-guidance"],
      file,
    );
  }
});
