#!/usr/bin/env node
import { mkdirSync, realpathSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import {
  installRuntimeFixture,
  loadManifest,
  runCommand,
} from "./source-matching-fixtures.mjs";
import { resolveAndroidEnvironment } from "./evidence-runner.mjs";

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
      result.fixtureIds = value
        .split(",")
        .map((entry) => entry.trim())
        .filter(Boolean);
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
  const idLineCount = (markdown.match(/(^|\n)\s*-?\s*id:/g) || []).length;
  if (idLineCount < 2) failures.push(`expected at least 2 id lines, found ${idLineCount}`);
  if (!markdown.includes("Handoff quality:")) failures.push("missing Handoff quality");
  if (!markdown.includes("agent_protocol:")) failures.push("missing agent_protocol");
  if (failures.length > 0) throw new Error(failures.join("; "));
  return {
    idLineCount,
    promptChars: markdown.length,
  };
}

export function assertSessionHandedOffItems(session, itemIds) {
  const items = Array.isArray(session?.items) ? session.items : [];
  const selected = itemIds.length > 0
    ? items.filter((item) => itemIds.includes(item.itemId))
    : items;
  const handedOffCount = selected.filter((item) => Number.isFinite(item.lastHandedOffAtEpochMillis)).length;
  if (selected.length < 2) throw new Error(`expected at least 2 persisted items, found ${selected.length}`);
  if (handedOffCount < 2) {
    throw new Error(`expected at least 2 copied items with lastHandedOffAtEpochMillis, found ${handedOffCount}`);
  }
  return {
    itemCount: items.length,
    handedOffCount,
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
    lines.push(
      `| ${app.fixtureId} | ${app.packageName || ""} | ${app.status} | ${app.itemCount || 0} | ${app.idLineCount || 0} | ${app.handedOffCount || 0} | ${app.promptPath || ""} |`,
    );
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

function androidDeviceName(environment) {
  return environment.device || environment.serial || null;
}

function ensureMcpDistribution() {
  runCommand("./gradlew", [":fixthis-mcp:installDist", "--no-daemon"], { cwd: repoRoot, stdio: "inherit" });
  return join(repoRoot, "fixthis-mcp/build/install/fixthis-mcp/bin/fixthis-mcp");
}

async function runSelectedFixture(fixture) {
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
      summary: {
        totalApps: 0,
        passedApps: 0,
        failedApps: preflight.status === "fail" ? 1 : 0,
        deferredApps: preflight.status === "deferred" ? 1 : 0,
      },
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

export async function main(argv = process.argv.slice(2)) {
  const options = parseArgs(argv);
  const { report, exitCode } = await runSmoke(options);
  console.log(renderMarkdownReport(report));
  return exitCode;
}

const invokedAsCli = process.argv[1] ? fileURLToPath(import.meta.url) === realpathSync(process.argv[1]) : false;

if (invokedAsCli) {
  process.exitCode = await main();
}
