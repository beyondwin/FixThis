import { spawnSync } from "node:child_process";
import { pathToFileURL } from "node:url";
import {
  CONNECTED_PROOF_COMMAND,
  SAFE_FALLBACK_COMMAND,
  isKnownTask,
  orderedUnique,
  selectRoutes,
} from "./agent-route-registry.mjs";

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
    if (parent.status === 0) {
      target = "HEAD^";
    } else {
      target = "HEAD";
      warnings.push("Git parent revision is unavailable; using HEAD as the change base.");
    }
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
  if (!isKnownTask(task)) {
    throw new Error(
      `Unknown task "${task}". Run npm run agent:route -- --task <known-task> --json; ` +
      "task names are defined in scripts/agent-route-registry.mjs.",
    );
  }
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
  const broadGate = preflightWarnings.length > 0
    ? SAFE_FALLBACK_COMMAND
    : routes.some((route) => route.broadGate === "npm run release:check")
      ? "npm run release:check"
      : routes.length > 0 || unmatched.length > 0
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
    "| Recommended checks | SKIPPED | NOT RUN: the router is read-only and executes no verification. | Run the listed checks before completion. | Verification pending |",
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
