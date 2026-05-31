#!/usr/bin/env node
import { mkdirSync, realpathSync, writeFileSync } from "node:fs";
import { join, relative } from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import {
  installRuntimeFixture,
  loadManifest,
  runCommand,
} from "./source-matching-fixtures.mjs";
import { resolveAndroidEnvironment } from "./evidence-runner.mjs";
import { createMcpJsonRpcClient } from "./mcp-json-rpc-client.mjs";

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

export function itemIdsFromCopiedPrompt(markdown) {
  const ids = [];
  const pattern = /(^|\n)\s*-?\s*id:\s*["']?([^"'\n\r]+)/g;
  let match;
  while ((match = pattern.exec(markdown)) !== null) {
    const id = match[2].trim();
    if (id) ids.push(id);
  }
  return ids;
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

export function withAndroidEnvironment(options = {}, environment = {}) {
  return {
    ...options,
    env: {
      ...(options.env || {}),
      ...(environment.envPatch || {}),
    },
  };
}

function relativeArtifactPath(reportDir, path) {
  const rel = relative(reportDir, path);
  return rel.startsWith("..") ? path : rel;
}

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

function fixtureLabel(fixtureId) {
  const labels = new Map([
    ["reply", "Reply"],
    ["jetsnack", "Jetsnack"],
    ["fixthis-sample", "FixThis Sample"],
  ]);
  if (labels.has(fixtureId)) return labels.get(fixtureId);
  return fixtureId
    .split("-")
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

export function expectedComments(fixture) {
  const label = fixtureLabel(fixture.id);
  return [
    `${label} copy prompt smoke annotation 1`,
    `${label} copy prompt smoke annotation 2`,
  ];
}

function androidDeviceName(environment) {
  return environment.device || environment.serial || null;
}

function ensureMcpDistribution() {
  runCommand("./gradlew", [":fixthis-mcp:installDist", "--no-daemon"], { cwd: repoRoot, stdio: "inherit" });
  return join(repoRoot, "fixthis-mcp/build/install/fixthis-mcp/bin/fixthis-mcp");
}

async function readyDiagnostics(page) {
  return await page.evaluate(() => {
    const card = document.getElementById("connectionCard");
    const image = document.getElementById("snapshotImage");
    return {
      connectionState: card?.dataset.connectionState || null,
      connectionText: card?.textContent?.replace(/\s+/g, " ").trim() || null,
      imagePresent: Boolean(image),
      imageComplete: Boolean(image?.complete),
      naturalWidth: image?.naturalWidth || 0,
      naturalHeight: image?.naturalHeight || 0,
      error: document.getElementById("error")?.textContent || null,
    };
  });
}

async function previewImageReady(page) {
  return await page.evaluate(() => {
    const image = document.getElementById("snapshotImage");
    return Boolean(image?.complete && image.naturalWidth > 0 && image.naturalHeight > 0);
  });
}

async function ensurePreviewImage(page, timeoutMs = 90_000) {
  const deadline = Date.now() + timeoutMs;
  let lastCaptureClickAt = 0;
  while (Date.now() < deadline) {
    if (await previewImageReady(page)) return;
    const canCapture = await page.evaluate(() => {
      const action = document.getElementById("connectionPrimaryAction");
      return Boolean(action && !action.disabled);
    });
    const now = Date.now();
    if (canCapture && now - lastCaptureClickAt > 2_000) {
      lastCaptureClickAt = now;
      await page.evaluate(() => document.getElementById("connectionPrimaryAction")?.click());
    }
    await page.waitForTimeout(500);
  }
  throw new Error(`Preview image did not become ready: ${JSON.stringify(await readyDiagnostics(page))}`);
}

async function waitForReadyConsole(page) {
  await page.waitForSelector("#connectionPrimaryAction", { timeout: 60_000 });
  const connectionState = await page.getAttribute("#connectionCard", "data-connection-state");
  if (connectionState !== "ready") {
    await page.evaluate(() => document.getElementById("connectionPrimaryAction")?.click());
  }
  try {
    await page.waitForFunction(
      () => document.getElementById("connectionCard")?.dataset.connectionState === "ready",
      undefined,
      { timeout: 60_000 },
    );
    await ensurePreviewImage(page);
  } catch (error) {
    throw new Error(`Console did not reach ready preview state: ${error.message}; diagnostics=${JSON.stringify(await readyDiagnostics(page))}`);
  }
}

async function dragAreaAnnotation(page, box, index) {
  const anchors = [
    { x: 0.2, y: 0.22 },
    { x: 0.55, y: 0.62 },
  ];
  const anchor = anchors[index] || { x: 0.25, y: 0.25 };
  const startX = box.x + box.width * anchor.x;
  const startY = box.y + box.height * anchor.y;
  const endX = Math.min(box.x + box.width * 0.92, startX + box.width * 0.26);
  const endY = Math.min(box.y + box.height * 0.92, startY + box.height * 0.18);
  await page.mouse.move(startX, startY);
  await page.mouse.down();
  await page.mouse.move(endX, endY, { steps: 5 });
  await page.mouse.up();
}

async function addAnnotation(page, comment, index) {
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
  await dragAreaAnnotation(page, box, index);
  try {
    await page.waitForFunction(
      (expectedCount) => document.querySelectorAll(".selection-box.annotation-pin").length >= expectedCount,
      index + 1,
      { timeout: 20_000 },
    );
  } catch {
    throw new Error(`Expected at least ${index + 1} annotation pins after drag: ${JSON.stringify(await pendingDiagnostics(page))}`);
  }
  await page.waitForSelector("#annotationCommentInput", { timeout: 20_000 });
  await page.fill("#annotationCommentInput", comment);
  try {
    await page.waitForFunction(
      (expected) => {
        const inputMatches = document.getElementById("annotationCommentInput")?.value === expected;
        const draftItems = window.FixThisConsoleDebug?.getDraftWorkspace?.()?.items || [];
        return inputMatches && draftItems.some((item) => item.comment === expected);
      },
      comment,
      { timeout: 20_000 },
    );
  } catch {
    throw new Error(`Pending annotation comment did not persist for ${comment}: ${JSON.stringify(await pendingDiagnostics(page))}`);
  }
  const backToAnnotations = page.locator("[data-back-annotations]").first();
  if (await backToAnnotations.count() > 0) {
    await backToAnnotations.click();
    await page.waitForFunction(
      () => !document.getElementById("annotationCommentInput"),
      undefined,
      { timeout: 10_000 },
    );
  }
}

async function pendingDiagnostics(page) {
  return await page.evaluate(() => ({
    pins: document.querySelectorAll(".selection-box.annotation-pin").length,
    toolMode: document.getElementById("snapshot")?.dataset.toolMode || null,
    pendingText: document.getElementById("pendingItems")?.textContent?.replace(/\s+/g, " ").trim() || null,
    commentValue: document.getElementById("annotationCommentInput")?.value || null,
    focusedElement: document.activeElement?.id || document.activeElement?.tagName || null,
    error: document.getElementById("error")?.textContent || null,
  }));
}

async function waitForHandedOffState(page, itemIds) {
  await page.waitForFunction(
    (ids) => {
      const items = window.FixThisConsoleDebug?.getState?.()?.session?.items || [];
      return ids.length >= 2 && ids.every((id) => {
        const item = items.find((candidate) => candidate.itemId === id);
        return Number.isFinite(item?.lastHandedOffAtEpochMillis);
      });
    },
    itemIds,
    { timeout: 30_000 },
  );
}

async function browserCopyPromptFlow({ consoleUrl, fixture, reportDir, headed }) {
  const comments = expectedComments(fixture);
  const paths = artifactPaths(reportDir, fixture.id);
  const { chromium } = await import("playwright");
  const browser = await chromium.launch({ headless: !headed });
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
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
    await page.waitForFunction(() => !document.getElementById("copyPromptButton").disabled, undefined, { timeout: 20_000 });
    await page.click("#copyPromptButton");
    await page.waitForFunction(() => window.__fixthisCopiedText?.includes("agent_protocol:"), undefined, { timeout: 30_000 });
    const copiedText = await page.evaluate(() => window.__fixthisCopiedText);
    const promptStats = assertCopiedPrompt(copiedText, comments);
    const itemIds = itemIdsFromCopiedPrompt(copiedText);
    await waitForHandedOffState(page, itemIds);
    const state = await page.evaluate(() => window.FixThisConsoleDebug?.getState?.()?.session);
    writeFileSync(paths.promptPath, copiedText);
    writeFileSync(paths.statePath, `${JSON.stringify(state, null, 2)}\n`);
    await page.screenshot({ path: paths.screenshotPath, fullPage: true });
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

async function runSelectedFixture(fixture, options) {
  const app = {
    fixtureId: fixture.id,
    packageName: fixture.applicationId,
    status: "fail",
    failures: [],
  };
  let mcp = null;
  try {
    const runWithAndroidEnvironment = (command, args, runOptions = {}) =>
      runCommand(command, args, withAndroidEnvironment(runOptions, options.environment));
    const paths = installRuntimeFixture(fixture, {
      stdio: "inherit",
      run: runWithAndroidEnvironment,
    });
    app.projectDir = paths.projectWorkDir;
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
    const opened = await mcp.callTool("fixthis_open_feedback_console", {
      packageName: fixture.applicationId,
      newSession: true,
    });
    app.sessionId = opened.sessionId;
    app.consoleUrl = opened.consoleUrl;
    const browserStats = await browserCopyPromptFlow({
      consoleUrl: opened.consoleUrl,
      fixture,
      reportDir: options.reportDir,
      headed: options.headed,
    });
    Object.assign(app, browserStats);
    app.status = "pass";
    return app;
  } catch (error) {
    app.failures.push(error.message);
    return app;
  } finally {
    await mcp?.close?.();
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

  const mcpBin = await ensureMcpDistribution();
  const manifest = loadManifest();
  const fixtures = selectRuntimeFixtures(manifest, options.fixtureIds);
  const apps = [];
  for (const fixture of fixtures) {
    apps.push(await runSelectedFixture(fixture, { ...options, environment, mcpBin }));
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
