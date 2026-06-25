#!/usr/bin/env node
import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { delimiter, dirname, join, resolve } from "node:path";
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
    step("External fixture matrix contracts", "npm run external-fixture:matrix:test"),
    step("Runtime evidence attachment", "npm run runtime-evidence:smoke"),
    step("External trust matrix v2 strict", "npm run external-fixture:matrix -- --strict", {
      deferrable: true,
      requiresAndroid: true,
    }),
    step("Runtime trust strict", "npm run source-matching:fixtures:runtime -- --strict", {
      deferrable: true,
      requiresAndroid: true,
    }),
    step("Real copy prompt smoke", "npm run real-copy-prompt:smoke -- --strict", {
      deferrable: true,
      requiresAndroid: true,
    }),
    step("Agent loop smoke", "npm run agent-loop:smoke -- --strict", {
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
    step("Release reality", "npm run release:reality"),
    step("Release drift", "npm run release:drift"),
    step("Agent loop smoke contracts", "npm run agent-loop:smoke:test"),
    step("Runtime evidence attachment strict", "npm run runtime-evidence:smoke -- --strict"),
    step("Release readiness", "node scripts/check-release-readiness.mjs"),
    step("Docs CLI surface", "bash scripts/check-docs-cli-surface.sh"),
    step("Agent bootstrap docs", "npm run docs:agent-bootstrap:test"),
    step("Version metadata", "npm run release:version:check"),
    step("Package tests", "npm run release:package:test"),
    step("npm and MCP registry tests", "npm run release:npm:test"),
  ],
  gate: [
    step("Release reality", "npm run release:reality"),
    step("Release drift strict", "npm run release:drift -- --strict"),
    step("ADB discovery tests", "./gradlew :fixthis-cli:test --tests \"*AdbTest\" --no-daemon"),
    step("Compose core source trust", "./gradlew :fixthis-compose-core:test --tests \"*SourceMatcherTest\" --no-daemon"),
    step(
      "Interop boundary contracts",
      "./gradlew :fixthis-mcp:test --tests \"*TargetBoundaryContextFormatterTest\" --tests \"*TargetBoundaryGuidanceTest\" --tests \"*CompactHandoffRendererTest.compactHandoffRendersInteropBoundaryContextFromNearbyComposeHost\" --no-daemon",
    ),
    step("Handoff evaluation", "npm run handoff:eval:test"),
    step("Runtime evidence attachment", "npm run runtime-evidence:smoke"),
    step("Plugin contract", "npm run plugin:contract:test"),
    step("Runtime trust boundary observations", "npm run source-matching:fixtures:test"),
    step("Runtime trust strict", "npm run source-matching:fixtures:runtime -- --strict", {
      deferrable: true,
      requiresAndroid: true,
    }),
    step(
      "First handoff autopilot CLI contract",
      "./gradlew :fixthis-cli:test --tests \"*AgentSetupVerificationServiceTest\" --tests \"*InstallAgentJsonReportTest\" --no-daemon",
    ),
    step("Agent loop smoke contracts", "npm run agent-loop:smoke:test"),
    step("External fixture matrix contracts", "npm run external-fixture:matrix:test"),
    step("External trust matrix v2 strict", "npm run external-fixture:matrix -- --strict", {
      deferrable: true,
      requiresAndroid: true,
    }),
    step("Agent loop smoke", "npm run agent-loop:smoke -- --strict", {
      deferrable: true,
      requiresAndroid: true,
    }),
    step("Real copy prompt smoke", "npm run real-copy-prompt:smoke -- --strict", {
      deferrable: true,
      requiresAndroid: true,
    }),
    step("Console reliability contracts", "node --test scripts/console-reliability-report-test.mjs scripts/studioReliabilityContract-test.mjs"),
    step("Console browser reliability", "npm run console:browser:reliability"),
    step("Release readiness", "node scripts/check-release-readiness.mjs"),
    step("Docs consistency", "node scripts/check-doc-consistency.mjs"),
    step("Workspace whitespace", "node scripts/check-whitespace.mjs diff --check"),
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

function adbExecutableName(platform = process.platform) {
  return platform === "win32" ? "adb.exe" : "adb";
}

function sdkCandidates({ env, homeDir, platform }) {
  const candidates = [env.ANDROID_HOME, env.ANDROID_SDK_ROOT];
  if (homeDir) {
    if (platform === "darwin") {
      candidates.push(join(homeDir, "Library/Android/sdk"));
    } else if (platform === "linux") {
      candidates.push(join(homeDir, "Android/Sdk"));
    }
  }
  return [...new Set(candidates.filter((value) => typeof value === "string" && value.trim()))];
}

export function parseReadyAndroidDevice(stdout = "") {
  const match = stdout.match(/\n(\S+)\s+device\b/);
  return match?.[1] || null;
}

export function resolveAndroidEnvironment({
  env = process.env,
  homeDir = process.env.HOME,
  platform = process.platform,
  exists = existsSync,
  spawn = spawnSync,
} = {}) {
  const adbName = adbExecutableName(platform);
  const candidates = sdkCandidates({ env, homeDir, platform });
  const sdkWithAdb = candidates.find((candidate) => exists(join(candidate, "platform-tools", adbName)));
  const sdk = sdkWithAdb ?? candidates.find((candidate) => candidate === env.ANDROID_HOME || candidate === env.ANDROID_SDK_ROOT);
  if (!sdk) {
    return {
      ready: false,
      reason: "Android SDK is unavailable.",
      envPatch: {},
    };
  }

  const platformTools = join(sdk, "platform-tools");
  const adbFromSdk = join(platformTools, adbName);
  const adbCommand = exists(adbFromSdk) ? adbFromSdk : "adb";
  const envPatch = {
    ANDROID_HOME: sdk,
    ANDROID_SDK_ROOT: sdk,
    PATH: [platformTools, env.PATH].filter(Boolean).join(delimiter),
  };
  const adb = spawn(adbCommand, ["devices"], {
    cwd: repoRoot,
    encoding: "utf8",
    env: { ...process.env, ...env, ...envPatch },
  });
  const device = parseReadyAndroidDevice(adb.stdout);
  return {
    ready: adb.status === 0 && Boolean(device),
    reason: "Android SDK or ready emulator is unavailable.",
    envPatch,
    device,
  };
}

function runCommand(command, envPatch = {}) {
  const started = Date.now();
  const result = spawnSync("bash", ["-lc", command], {
    cwd: repoRoot,
    encoding: "utf8",
    env: { ...process.env, ...envPatch },
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
  const androidEnvironment = options.androidEnvironment ?? resolveAndroidEnvironment();
  const runStep = options.runCommandFn ?? runCommand;

  for (const entry of plan.steps) {
    if (entry.requiresAndroid && !androidEnvironment.ready && !plan.strictRuntime) {
      steps.push({
        ...entry,
        status: "deferred",
        durationMs: 0,
        reason: androidEnvironment.reason,
      });
      continue;
    }
    const result = runStep(entry.command, entry.requiresAndroid ? androidEnvironment.envPatch : {});
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
