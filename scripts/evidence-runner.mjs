#!/usr/bin/env node
import { spawnSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const scriptPath = fileURLToPath(import.meta.url);
const repoRoot = resolve(dirname(scriptPath), "..");

const profileDefinitions = {
  fast: [
    step("Docs consistency", "node scripts/check-doc-consistency.mjs"),
    step("Release readiness", "node scripts/check-release-readiness.mjs"),
    step("Agent bootstrap docs", "npm run docs:agent-bootstrap:test"),
    step("Console bundle check", "node scripts/build-console-assets.mjs --check"),
    step("Fast console contracts", "npm run console:test:fast"),
    step("Workspace whitespace", "node scripts/check-whitespace.mjs diff --check"),
  ],
  trust: [
    step("Compose core source trust", "./gradlew :fixthis-compose-core:test --tests \"*SourceMatcherTest\" --no-daemon"),
    step("Handoff evaluation", "npm run handoff:eval:test"),
    step("Source fixture contracts", "npm run source-matching:fixtures:test"),
    step("Runtime trust strict", "npm run source-matching:fixtures:runtime -- --strict", {
      deferrable: true,
      requiresAndroid: true,
    }),
  ],
  console: [
    step("Studio reliability contract", "node --test scripts/studioReliabilityContract-test.mjs"),
    step("Console fast contracts", "npm run console:test:fast"),
    step("Browser reliability", "npm run console:browser:reliability"),
  ],
  release: [
    step("Release readiness", "node scripts/check-release-readiness.mjs"),
    step("Docs CLI surface", "bash scripts/check-docs-cli-surface.sh"),
    step("Agent bootstrap docs", "npm run docs:agent-bootstrap:test"),
    step("Version metadata", "npm run release:version:check"),
    step("Package tests", "npm run release:package:test"),
    step("npm and MCP registry tests", "npm run release:npm:test"),
  ],
};

profileDefinitions.full = [
  ...profileDefinitions.fast,
  ...profileDefinitions.trust,
  ...profileDefinitions.console,
  ...profileDefinitions.release,
  step("Local CI full mirror", "npm run ci:local"),
];

const profiles = Object.freeze(profileDefinitions);

function step(name, command, options = {}) {
  return Object.freeze({
    name,
    command,
    deferrable: options.deferrable === true,
    requiresAndroid: options.requiresAndroid === true,
  });
}

export function expandProfile(profile) {
  const selected = profiles[profile];
  if (!selected) {
    throw new Error(`Unknown evidence profile: ${profile}`);
  }
  return selected;
}

export function planRun({ profile, dryRun = false, strictRuntime = false } = {}) {
  const selectedProfile = profile || "fast";
  return {
    schemaVersion: "1.0",
    profile: selectedProfile,
    dryRun,
    strictRuntime,
    steps: expandProfile(selectedProfile).map((entry) => ({ ...entry })),
  };
}

export function renderDryRun(plan) {
  const lines = [`DRY RUN profile=${plan.profile}`];
  for (const entry of plan.steps) {
    lines.push(`RUN ${entry.command}`);
  }
  return `${lines.join("\n")}\n`;
}

function androidReady() {
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  if (!sdk) return false;
  const adb = spawnSync("adb", ["devices"], { cwd: repoRoot, encoding: "utf8" });
  return adb.status === 0 && /\n\S+\s+device\b/.test(adb.stdout);
}

function runCommand(command) {
  const started = Date.now();
  const result = spawnSync("bash", ["-lc", command], {
    cwd: repoRoot,
    encoding: "utf8",
    stdio: "pipe",
  });
  return {
    status: result.status === 0 ? "passed" : "failed",
    durationMs: Date.now() - started,
    stdout: result.stdout,
    stderr: result.stderr,
    exitCode: result.status ?? 1,
  };
}

export function writeReports(report, root = repoRoot) {
  const reportDir = join(root, "build/reports/fixthis-evidence");
  mkdirSync(reportDir, { recursive: true });
  const jsonPath = join(reportDir, `${report.profile}.json`);
  const markdownPath = join(reportDir, `${report.profile}.md`);
  writeFileSync(jsonPath, `${JSON.stringify(report, null, 2)}\n`);
  writeFileSync(markdownPath, renderMarkdownReport(report));
  return { json: jsonPath, markdown: markdownPath };
}

function renderMarkdownReport(report) {
  const lines = [
    "# FixThis Evidence Report",
    "",
    `Profile: ${report.profile}`,
    `Status: ${report.status}`,
    "",
    "| Step | Status | Command | Duration |",
    "| --- | --- | --- | --- |",
  ];
  for (const entry of report.steps) {
    lines.push(`| ${entry.name} | ${entry.status} | \`${entry.command}\` | ${entry.durationMs ?? 0}ms |`);
  }
  return `${lines.join("\n")}\n`;
}

function parseArgs(argv) {
  const args = {
    profile: "fast",
    dryRun: false,
    strictRuntime: false,
    continueOnFailure: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--profile") {
      args.profile = argv[i + 1];
      i += 1;
    } else if (arg === "--dry-run") {
      args.dryRun = true;
    } else if (arg === "--strict-runtime") {
      args.strictRuntime = true;
    } else if (arg === "--continue") {
      args.continueOnFailure = true;
    } else if (arg === "-h" || arg === "--help") {
      console.log("Usage: node scripts/evidence-runner.mjs --profile <fast|trust|console|release|full> [--dry-run] [--strict-runtime] [--continue]");
      process.exit(0);
    } else {
      throw new Error(`Unknown flag: ${arg}`);
    }
  }
  return args;
}

export function runPlan(plan, options = {}) {
  const startedAt = new Date().toISOString();
  const steps = [];
  let status = "passed";
  const canRunAndroid = androidReady();

  for (const entry of plan.steps) {
    if (entry.requiresAndroid && !canRunAndroid && !plan.strictRuntime) {
      steps.push({
        ...entry,
        status: "deferred",
        durationMs: 0,
        reason: "Android SDK or ready emulator is unavailable.",
      });
      continue;
    }
    const result = runCommand(entry.command);
    steps.push({ ...entry, ...result });
    if (result.status === "failed") {
      status = "failed";
      if (!options.continueOnFailure) break;
    }
  }

  return {
    schemaVersion: "1.0",
    profile: plan.profile,
    status,
    startedAt,
    finishedAt: new Date().toISOString(),
    steps,
  };
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const plan = planRun(args);
  if (args.dryRun) {
    process.stdout.write(renderDryRun(plan));
    return;
  }
  const report = runPlan(plan, args);
  const paths = writeReports(report);
  console.log(`Evidence profile ${report.profile}: ${report.status}`);
  console.log(`JSON: ${paths.json}`);
  console.log(`Markdown: ${paths.markdown}`);
  const failed = report.steps.find((entry) => entry.status === "failed");
  if (failed) {
    console.error(`First failed command: ${failed.command}`);
    process.exit(1);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === scriptPath) {
  main();
}
