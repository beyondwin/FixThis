#!/usr/bin/env node
import { execFileSync } from "node:child_process";

export const workflowRules = {
  ".github/workflows/ci.yml": { requiredSuccesses: 7 },
  ".github/workflows/codeql.yml": { requiredSuccesses: 7 },
  ".github/workflows/connected-tests.yml": { requiredSuccesses: 14 },
  ".github/workflows/nightly-compat.yml": { requiredSuccesses: 1 },
};

export const workflowAliases = {
  ci: ".github/workflows/ci.yml",
  codeql: ".github/workflows/codeql.yml",
  connected: ".github/workflows/connected-tests.yml",
  "connected-tests": ".github/workflows/connected-tests.yml",
  compat: ".github/workflows/nightly-compat.yml",
  "nightly-compat": ".github/workflows/nightly-compat.yml",
};

export function consecutiveSuccesses(runs) {
  let count = 0;
  for (const run of runs) {
    if (run.status !== "completed") continue;
    if (run.conclusion === "success") count += 1;
    else break;
  }
  return count;
}

export function summarizeWorkflow(workflow, runs, rule = workflowRules[workflow]) {
  const completedSuccesses = runs.filter(
    (run) => run.status === "completed" && run.conclusion === "success",
  );
  const streak = consecutiveSuccesses(runs);
  const requiredSuccesses = rule?.requiredSuccesses ?? 7;
  return {
    workflow,
    firstGreenInSample: completedSuccesses.at(-1)?.createdAt ?? null,
    consecutiveGreenCompletedRunsFromLatest: streak,
    requiredSuccesses,
    ready: streak >= requiredSuccesses,
    latestUrl: runs[0]?.url ?? null,
  };
}

function ghJson(args) {
  try {
    return JSON.parse(execFileSync("gh", args, { encoding: "utf8" }));
  } catch (_) {
    console.error("error: failed to query GitHub Actions with gh.");
    console.error("Run `gh auth status` and retry from a checkout with repository access.");
    process.exitCode = 2;
    return null;
  }
}

function parseArgs(argv) {
  const options = { json: false, requireReady: [] };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--json") {
      options.json = true;
    } else if (arg === "--require-ready") {
      const value = argv[++i] ?? "";
      options.requireReady = value.split(",").filter(Boolean);
    } else if (arg === "--help" || arg === "-h") {
      options.help = true;
    } else {
      throw new Error(`unknown flag: ${arg}`);
    }
  }
  return options;
}

function printUsage() {
  const lines = [
    "Usage: required-checks-observation [--json] [--require-ready <names>] [-h|--help]",
    "",
    "Flags:",
    "  --json                     Print machine-readable JSON summaries.",
    "  --require-ready <names>    Comma-separated workflow names (aliases allowed:",
    "                             ci, codeql, connected, connected-tests, compat,",
    "                             nightly-compat). Exit nonzero if any are not ready.",
    "  -h, --help                 Show this help and exit.",
  ];
  console.log(lines.join("\n"));
}

function resolveWorkflowKey(token) {
  return workflowAliases[token] ?? token;
}

function main(argv) {
  let options;
  try {
    options = parseArgs(argv);
  } catch (err) {
    console.error(`error: ${err.message}`);
    printUsage();
    process.exitCode = 2;
    return;
  }

  if (options.help) {
    printUsage();
    return;
  }

  const summaries = [];
  for (const workflow of Object.keys(workflowRules)) {
    const runs = ghJson([
      "run",
      "list",
      "--workflow",
      workflow,
      "--branch",
      "main",
      "--limit",
      "20",
      "--json",
      "conclusion,createdAt,databaseId,status,url",
    ]);
    if (!runs) continue;
    summaries.push(summarizeWorkflow(workflow, runs));
  }

  if (options.json) {
    console.log(JSON.stringify(summaries, null, 2));
  } else {
    for (const summary of summaries) {
      console.log(`${summary.workflow}`);
      console.log(`  first green in sample: ${summary.firstGreenInSample ?? "none in last 20 runs"}`);
      console.log(
        `  consecutive green completed runs from latest: ${summary.consecutiveGreenCompletedRunsFromLatest}`,
      );
      console.log(`  required: ${summary.requiredSuccesses} (ready: ${summary.ready ? "yes" : "no"})`);
      console.log(`  latest: ${summary.latestUrl ?? "no runs"}`);
    }
  }

  if (options.requireReady.length > 0) {
    for (const token of options.requireReady) {
      const key = resolveWorkflowKey(token);
      const summary = summaries.find((entry) => entry.workflow === key);
      if (!summary) {
        console.error(`not ready: ${token} (no runs observed for ${key})`);
        process.exitCode = 1;
        continue;
      }
      if (!summary.ready) {
        console.error(
          `not ready: ${key} (${summary.consecutiveGreenCompletedRunsFromLatest}/${summary.requiredSuccesses} consecutive green)`,
        );
        process.exitCode = 1;
      }
    }
  }
}

const invokedDirectly = import.meta.url === `file://${process.argv[1]}`;
if (invokedDirectly) {
  main(process.argv.slice(2));
}
