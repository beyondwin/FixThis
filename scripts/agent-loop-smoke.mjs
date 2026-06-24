#!/usr/bin/env node
import { spawnSync } from "node:child_process";
import { mkdirSync, realpathSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { resolveAndroidEnvironment } from "./evidence-runner.mjs";
import { createMcpJsonRpcClient } from "./mcp-json-rpc-client.mjs";
import {
  installRuntimeFixture,
  loadManifest,
  runCommand,
} from "./source-matching-fixtures.mjs";

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

export function selectResolutionItemIds(promptItemIds, session) {
  const sessionItemIds = (Array.isArray(session?.items) ? session.items : [])
    .map((item) => item.itemId)
    .filter(Boolean);
  const sessionItemIdSet = new Set(sessionItemIds);
  const promptMatches = (promptItemIds || []).filter((itemId) => sessionItemIdSet.has(itemId));
  return (promptMatches.length > 0 ? promptMatches : sessionItemIds).slice(0, 3);
}

export function assertCopiedPromptProtocol(markdown) {
  const failures = [];
  const sessionId = markdown.match(/(^|\n)\s*session_id:\s*["']?([^"'\n\r]+)/)?.[2]?.trim() || null;
  const itemIds = [...markdown.matchAll(/(^|\n)\s*-?\s*id:\s*["']?([^"'\n\r]+)/g)]
    .map((match) => match[2].trim())
    .filter(Boolean);
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
    if (item.status !== expected.status) {
      throw new Error(`${expected.itemId} status expected ${expected.status} but was ${item.status}`);
    }
    if (item.agentSummary !== expected.summary) {
      throw new Error(`${expected.itemId} summary expected ${expected.summary} but was ${item.agentSummary}`);
    }
    if (expected.status === "resolved") counts.resolved += 1;
    if (expected.status === "needs_clarification") counts.needsClarification += 1;
    if (expected.status === "wont_fix") counts.wontFix += 1;
  }
  return counts;
}

export function assertReadFeedbackQueueContains(queue, expectedItemIds) {
  const items = Array.isArray(queue?.items) ? queue.items : [];
  const seen = new Set(items.map((item) => item.itemId).filter(Boolean));
  const missing = expectedItemIds.filter((itemId) => !seen.has(itemId));
  if (missing.length > 0) {
    throw new Error(`read_feedback_missing_items: ${missing.join(", ")}`);
  }
  return {
    itemCount: items.length,
    sentCount: items.filter((item) => item.delivery === "sent").length,
  };
}

export function categorizeFeedbackLifecycleFailure(operation, error) {
  const message = String(error?.message || error || "").toLowerCase();
  const prefix = operation === "claim" ? "claim" : "resolve";
  if (message.includes("unknown feedback item") || message.includes("item_not_found")) return `${prefix}_item_not_found`;
  if (message.includes("item_already_resolved") || message.includes("already resolved")) return `${prefix}_item_already_resolved`;
  if (message.includes("unsupported feedback resolution status") || message.includes("not allowed") || message.includes("invalid status")) return `${prefix}_invalid_status`;
  if (message.includes("summary")) return `${prefix}_missing_summary`;
  if (message.includes("unknown feedback session") || message.includes("session_not_found") || message.includes("stale-session") || message.includes("stale session")) return `${prefix}_stale_session`;
  if (message.includes("timed out") || message.includes("bridge") || message.includes("mcp process") || message.includes("econnrefused") || message.includes("socket")) return `${prefix}_transport_failure`;
  if (message.includes("persist") || message.includes("write") || message.includes("save") || message.includes("event log")) return `${prefix}_persistence_failure`;
  return `${prefix}_unknown_failure`;
}

export function categorizeFirstHandoffFailure(error) {
  const message = String(error?.message || error || "").toLowerCase();
  if (message.includes("android sdk is unavailable")) return "android_environment_unavailable";
  if (message.includes("ready emulator") || message.includes("connected android device")) return "device_unavailable";
  if (message.includes("multiple android applicationid") || message.includes("pass --package")) return "package_ambiguous";
  if (message.includes("metadata") || message.includes("project.json")) return "metadata_missing";
  if (message.includes("not debuggable") || message.includes("unsupported build")) return "unsupported_build";
  if (message.includes("debug app is not connected") || (message.includes("bridge") && message.includes("timed out"))) return "app_not_launched";
  if (message.includes("annotation detail") || message.includes("annotation target") || message.includes("annotation pins") || message.includes("pins after drag") || message.includes("pending annotation")) return "annotation_unavailable";
  if (message.includes("snapshot image") || message.includes("preview") || message.includes("capture")) return "preview_capture_unavailable";
  if (message.includes("save to mcp") || message.includes("agent-handoffs")) return "save_to_mcp_failed";
  if (message.includes("read_feedback_missing_items")) return "read_feedback_missing_items";
  if (message.includes("readyformcptooling=false") && message.includes("fixthis_open_feedback_console")) return "mcp_tooling_not_ready";
  if (message.includes("mcp process") || message.includes("econnrefused") || message.includes("socket")) return "mcp_transport_failure";
  return "mcp_transport_failure";
}

export function handoffLifecycleActionOrder() {
  return ["copy-prompt", "save-to-mcp"];
}

function normalizeVisibleText(value) {
  return String(value || "").replace(/\s+/g, " ").trim();
}

export function combineVisibleAnnotationStatusText(containers) {
  return (containers || [])
    .filter((container) => !container.hidden)
    .map((container) => normalizeVisibleText(container.textContent))
    .filter(Boolean)
    .join(" ")
    .trim();
}

export function statusForEnvironment({ strict, androidReady, reason }) {
  if (androidReady) return { status: "pass", exitCode: 0, failures: [] };
  return {
    status: strict ? "fail" : "deferred",
    exitCode: strict ? 1 : 0,
    failures: [reason || "Android environment is unavailable."],
  };
}

const allowedAutopilotActors = new Set(["agent", "user", "agent_after_restart"]);
const allowedAutopilotKinds = new Set(["command", "mcp_tool", "manual"]);

function assertAutopilotAction(action, index) {
  if (!action || typeof action !== "object") throw new Error(`actions[${index}] must be an object`);
  if (!allowedAutopilotActors.has(action.actor)) throw new Error(`actions[${index}] unsupported actor: ${action.actor}`);
  if (!allowedAutopilotKinds.has(action.kind)) throw new Error(`actions[${index}] unsupported kind: ${action.kind}`);
  if (action.kind === "command" && typeof action.command !== "string") throw new Error(`actions[${index}] command action requires command`);
  if (action.kind === "mcp_tool" && typeof action.tool !== "string") throw new Error(`actions[${index}] mcp_tool action requires tool`);
  if (action.kind === "manual" && typeof action.reason !== "string") throw new Error(`actions[${index}] manual action requires reason`);
}

export function assertVerifyReportAutopilotContract(report) {
  if (!report || typeof report !== "object") throw new Error("verify report must be an object");
  if (report.schemaVersion !== "1.1") throw new Error(`verify report schemaVersion must be 1.1, got ${report.schemaVersion}`);
  if (!report.readiness || typeof report.readiness.state !== "string") throw new Error("verify report readiness.state is required");
  if (!Array.isArray(report.actions) || report.actions.length === 0) throw new Error("verify report actions[] is required");
  report.actions.forEach(assertAutopilotAction);

  const openConsoleActions = report.actions.filter((action) => action.tool === "fixthis_open_feedback_console");
  const unsafeCurrentConsole = openConsoleActions.find((action) => action.actor === "agent" && report.readyForMcpTooling !== true);
  if (unsafeCurrentConsole) {
    throw new Error("readyForMcpTooling=false cannot include current-agent console action");
  }
  const blockedByUser = report.actions.some((action) => action.actor === "user" && action.blocksProgress === true);
  if (report.requiresUserAction === true && !blockedByUser) {
    throw new Error("requiresUserAction=true requires a blocking user action");
  }
  return {
    readyForMcpTooling: report.readyForMcpTooling === true,
    blockedByUser,
    openConsoleActor: openConsoleActions[0]?.actor || null,
    runnableCommandCount: report.actions.filter((action) => action.actor === "agent" && action.kind === "command").length,
  };
}

export function autopilotEvidenceForVerifyReport(report) {
  const summary = assertVerifyReportAutopilotContract(report);
  return {
    status: summary.readyForMcpTooling || summary.openConsoleActor === "agent_after_restart" || summary.runnableCommandCount > 0 ? "pass" : "deferred",
    readyForMcpTooling: summary.readyForMcpTooling,
    requiresUserAction: report.requiresUserAction === true,
    openConsoleActor: summary.openConsoleActor,
    actionCount: report.actions.length,
  };
}

export function assertVerifyReportReadyForMcpTooling(report) {
  const summary = assertVerifyReportAutopilotContract(report);
  const opensFeedbackConsole = report.actions.some((action) => action.tool === "fixthis_open_feedback_console");
  if (opensFeedbackConsole && summary.readyForMcpTooling !== true) {
    throw new Error("readyForMcpTooling=false blocks fixthis_open_feedback_console for the current agent");
  }
  return summary;
}

const FirstHandoffFailureCatalog = Object.freeze({
  android_environment_unavailable: {
    state: "ENV_BLOCKER",
    cause: "Android SDK is unavailable.",
    verify: "fixthis doctor --project-dir . --json",
    fix: "Install Android SDK platform-tools or fix ANDROID_HOME.",
    nextAction: "Install Android SDK platform-tools or fix ANDROID_HOME.",
  },
  device_unavailable: {
    state: "DEVICE_BLOCKED",
    cause: "No connected Android device or emulator found.",
    verify: "adb devices",
    fix: "Start an emulator or connect a device, then run `adb devices`.",
    nextAction: "Start an emulator or connect a device, then run `adb devices`.",
  },
  metadata_missing: {
    state: "NEEDS_INSTALL",
    cause: "FixThis project metadata was not found.",
    verify: "./gradlew fixthisSetup",
    fix: "Run `fixthis install-agent --project-dir . --target all` from the Android project root.",
    nextAction: "Run `fixthis install-agent --project-dir . --target all`",
  },
  package_ambiguous: {
    state: "CONFIG_RECOVERABLE",
    cause: "FixThis could not choose a unique Android applicationId.",
    verify: "fixthis install-agent --project-dir . --target all --dry-run",
    fix: "Pass `--package com.example.app` or inspect the dry-run output before writing config.",
    nextAction: "Run `fixthis install-agent --project-dir . --target all --dry-run`",
  },
  app_not_launched: {
    state: "NEEDS_APP_LAUNCH",
    cause: "The debug app is not connected to the FixThis bridge.",
    verify: "fixthis doctor --project-dir . --json",
    fix: "Launch the debug app so the FixThis sidekick can write its bridge session.",
    nextAction: "Open app",
  },
  unsupported_build: {
    state: "UNSUPPORTED_BUILD",
    cause: "This build cannot expose the FixThis bridge.",
    verify: "adb shell run-as com.example.app ls files/fixthis/session.json",
    fix: "Install a debuggable build with the FixThis sidekick enabled.",
    nextAction: "Install a debuggable build with FixThis enabled.",
  },
  preview_capture_unavailable: {
    state: "CAPTURE_UNAVAILABLE",
    cause: "Screenshot bytes unavailable.",
    verify: "Open the app foreground and tap Capture, or open doctor for the bridge log.",
    fix: "Reopen the app foreground and tap Retry capture, or open doctor for the bridge log.",
    nextAction: "Retry capture",
  },
  annotation_unavailable: {
    state: "CAPTURE_UNAVAILABLE",
    cause: "Annotation target capture did not complete.",
    verify: "Recapture the screen and verify the frozen preview matches the app.",
    fix: "Recapture and retry annotation.",
    nextAction: "Retry capture",
  },
  save_to_mcp_failed: {
    state: "UNKNOWN_ERROR",
    cause: "Save to MCP did not complete.",
    verify: "Open diagnostic details and rerun the failed command with --verbose.",
    fix: "Open diagnostics and rerun with report artifacts.",
    nextAction: "Open diagnostic details",
  },
  read_feedback_missing_items: {
    state: "SESSION_MISMATCH",
    cause: "Saved feedback items were not readable through MCP.",
    verify: "Compare the response sessionId with the active feedback session.",
    fix: "Refresh the active session or inspect the saved handoff batch.",
    nextAction: "Refresh session",
  },
  mcp_tooling_not_ready: {
    state: "RESTART_REQUIRED",
    cause: "Verify report says the current agent cannot call FixThis MCP tools yet.",
    verify: "fixthis install-agent --project-dir . --target all --verify --json",
    fix: "Restart Claude Code or Codex so the new FixThis MCP config is loaded.",
    nextAction: "Restart agent",
  },
  mcp_transport_failure: {
    state: "UNKNOWN_ERROR",
    cause: "MCP transport failed during first handoff.",
    verify: "fixthis doctor --project-dir . --json",
    fix: "Restart the MCP server and rerun the smoke.",
    nextAction: "Restart MCP server",
  },
});

function readinessWithDetails(entry, message, details) {
  return {
    state: entry.state,
    cause: message || entry.cause,
    verify: entry.verify,
    fix: entry.fix,
    nextAction: entry.nextAction,
    details: { ...details },
  };
}

export function firstHandoffFailure({ failureCode, message = null, details = {}, status = "fail" } = {}) {
  const entry = FirstHandoffFailureCatalog[failureCode] || FirstHandoffFailureCatalog.mcp_transport_failure;
  const readiness = readinessWithDetails(entry, message, details);
  return {
    status,
    failureCode: FirstHandoffFailureCatalog[failureCode] ? failureCode : "mcp_transport_failure",
    readiness,
    nextAction: readiness.nextAction,
  };
}

export function firstHandoffSuccess({
  savedItemCount = 0,
  readFeedbackItemCount = 0,
  readFeedbackSentCount = 0,
  autopilot = null,
} = {}) {
  return {
    status: "pass",
    savedItemCount,
    readFeedbackItemCount,
    readFeedbackSentCount,
    ...(autopilot ? { autopilot } : {}),
  };
}

export function firstHandoffForEnvironment({ strict, androidReady, reason }) {
  if (androidReady) return { status: "pending" };
  const failureCode = reason === "Android SDK is unavailable."
    ? "android_environment_unavailable"
    : "device_unavailable";
  return firstHandoffFailure({
    failureCode,
    status: strict ? "fail" : "deferred",
    message: reason,
    details: { reason },
  });
}

export function buildReport({ strict, device = null, startedAt, finishedAt, fixture, firstHandoff = null }) {
  const status = fixture.status === "pass" ? "pass" : fixture.status === "deferred" ? "deferred" : "fail";
  const autopilot = fixture.verifyReport ? autopilotEvidenceForVerifyReport(fixture.verifyReport) : null;
  const explicitFirstHandoff = firstHandoff && autopilot && !firstHandoff.autopilot
    ? { ...firstHandoff, autopilot }
    : firstHandoff;
  return {
    status,
    strict,
    device,
    startedAt,
    finishedAt,
    firstHandoff: explicitFirstHandoff || (
      status === "pass"
        ? firstHandoffSuccess({
            savedItemCount: fixture.savedItemCount || 0,
            readFeedbackItemCount: fixture.readFeedbackItemCount || 0,
            readFeedbackSentCount: fixture.readFeedbackSentCount || 0,
            autopilot,
          })
        : firstHandoffFailure({
            failureCode: "mcp_transport_failure",
            message: (fixture.failures || [])[0] || "First handoff failed.",
          })
    ),
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
  ];
  const firstHandoff = report.firstHandoff || {};
  lines.push("", "## First Handoff", "");
  lines.push(`- Status: ${firstHandoff.status || "unknown"}`);
  if (firstHandoff.failureCode) lines.push(`- Failure code: ${firstHandoff.failureCode}`);
  if (firstHandoff.readiness?.state) lines.push(`- Readiness: ${firstHandoff.readiness.state}`);
  if (firstHandoff.nextAction) lines.push(`- Next action: ${firstHandoff.nextAction}`);
  if (Number.isFinite(firstHandoff.savedItemCount)) lines.push(`- Saved items: ${firstHandoff.savedItemCount}`);
  if (Number.isFinite(firstHandoff.readFeedbackItemCount)) lines.push(`- Read feedback items: ${firstHandoff.readFeedbackItemCount}`);
  if (Number.isFinite(firstHandoff.readFeedbackSentCount)) lines.push(`- Sent items read: ${firstHandoff.readFeedbackSentCount}`);
  if (firstHandoff.autopilot) {
    lines.push(`- Autopilot: ${firstHandoff.autopilot.status}`);
    lines.push(`- Autopilot readyForMcpTooling: ${firstHandoff.autopilot.readyForMcpTooling}`);
    lines.push(`- Autopilot open console actor: ${firstHandoff.autopilot.openConsoleActor || "none"}`);
  }
  lines.push(
    "",
    "| Fixture | Package | Status | Saved | Read | Sent | Resolved | Needs clarification | Won't fix |",
    "|---|---|---:|---:|---:|---:|---:|---:|---:|",
  );
  const fixture = report.fixture || {};
  lines.push(`| ${fixture.fixtureId || ""} | ${fixture.packageName || ""} | ${fixture.status || ""} | ${fixture.savedItemCount || 0} | ${fixture.readFeedbackItemCount || 0} | ${fixture.readFeedbackSentCount || 0} | ${fixture.resolved || 0} | ${fixture.needsClarification || 0} | ${fixture.wontFix || 0} |`);
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

function ensureCliDistribution() {
  runCommand("./gradlew", [":fixthis-cli:installDist", "--no-daemon"], { cwd: repoRoot, stdio: "inherit" });
  return join(repoRoot, "fixthis-cli/build/install/fixthis/bin/fixthis");
}

function parseJsonObject(text, label) {
  try {
    const parsed = JSON.parse(text);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      throw new Error("expected a JSON object");
    }
    return parsed;
  } catch (error) {
    throw new Error(`${label} did not emit valid JSON: ${error.message}`);
  }
}

function runCommandAllowingJsonFailure(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd || repoRoot,
    encoding: "utf8",
    stdio: "pipe",
    env: { ...process.env, ...(options.env || {}) },
  });
  if (result.error) {
    throw new Error(`${command} ${args.join(" ")} failed to spawn: ${result.error.message}`);
  }
  const stdout = typeof result.stdout === "string" ? result.stdout.trim() : "";
  if (!stdout) {
    const stderr = typeof result.stderr === "string" ? result.stderr.trim() : "";
    throw new Error(`${command} ${args.join(" ")} emitted no JSON${stderr ? `\n${stderr}` : ""}`);
  }
  return stdout;
}

function verifyReportForRuntimeFixture({ fixture, projectWorkDir, environment }) {
  const cli = ensureCliDistribution();
  const home = join(projectWorkDir, ".fixthis-agent-loop-home");
  const stdout = runCommandAllowingJsonFailure(
    cli,
    [
      "install-agent",
      "--project-dir",
      projectWorkDir,
      "--target",
      "all",
      "--package",
      fixture.applicationId,
      "--verify",
      "--json",
    ],
    {
      cwd: repoRoot,
      env: {
        ...environment.envPatch,
        JAVA_OPTS: `-Duser.home=${home}`,
      },
    },
  );
  return parseJsonObject(stdout, "install-agent verify report");
}

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

export function annotationDragPoints(box, index) {
  const anchors = [
    { x: 0.2, y: 0.22 },
    { x: 0.55, y: 0.62 },
    { x: 0.32, y: 0.42 },
  ];
  const anchor = anchors[index] || { x: 0.25, y: 0.25 };
  const startX = box.x + box.width * anchor.x;
  const startY = box.y + box.height * anchor.y;
  return {
    startX,
    startY,
    endX: Math.min(box.x + box.width * 0.92, startX + box.width * 0.26),
    endY: Math.min(box.y + box.height * 0.92, startY + box.height * 0.18),
  };
}

async function dragAreaAnnotation(page, box, index) {
  const { startX, startY, endX, endY } = annotationDragPoints(box, index);
  await page.mouse.move(startX, startY);
  await page.mouse.down();
  await page.mouse.move(endX, endY, { steps: 5 });
  await page.mouse.up();
}

async function pendingDiagnostics(page) {
  return await page.evaluate(() => ({
    pins: document.querySelectorAll(".selection-box.annotation-pin").length,
    toolMode: document.getElementById("snapshot")?.dataset.toolMode || null,
    pendingText: document.getElementById("pendingItems")?.textContent?.replace(/\s+/g, " ").trim() || null,
    draftText: document.getElementById("draftItems")?.textContent?.replace(/\s+/g, " ").trim() || null,
    commentValue: document.getElementById("annotationCommentInput")?.value || null,
    focusedElement: document.activeElement?.id || document.activeElement?.tagName || null,
    error: document.getElementById("error")?.textContent || null,
  }));
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
  const initialPinCount = await page.locator(".selection-box.annotation-pin").count();
  const expectedPinCount = initialPinCount + 1;
  await dragAreaAnnotation(page, box, index);
  try {
    await page.waitForFunction(
      (expectedCount) => document.querySelectorAll(".selection-box.annotation-pin").length >= expectedCount,
      expectedPinCount,
      { timeout: 20_000 },
    );
  } catch {
    throw new Error(`Expected at least ${expectedPinCount} annotation pins after drag: ${JSON.stringify(await pendingDiagnostics(page))}`);
  }
  try {
    await page.waitForSelector("#annotationCommentInput", { timeout: 20_000 });
  } catch {
    throw new Error(`Annotation detail did not open after pin creation: ${JSON.stringify(await pendingDiagnostics(page))}`);
  }
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
    try {
      await page.waitForFunction(
        () => !document.getElementById("annotationCommentInput"),
        undefined,
        { timeout: 10_000 },
      );
    } catch {
      throw new Error(`Annotation detail did not close after back: ${JSON.stringify(await pendingDiagnostics(page))}`);
    }
  }
}

async function copyPromptBeforeLifecycleSave(page) {
  try {
    await page.waitForFunction(() => !document.getElementById("copyPromptButton")?.disabled, undefined, { timeout: 20_000 });
    await page.click("#copyPromptButton");
    await page.waitForFunction(() => window.__fixthisCopiedText?.includes("agent_protocol:"), undefined, { timeout: 30_000 });
    return await page.evaluate(() => window.__fixthisCopiedText);
  } catch (error) {
    throw new Error(`Copy Prompt did not produce agent handoff Markdown: ${error.message}; diagnostics=${JSON.stringify(await browserDiagnostics(page))}`);
  }
}

async function savePromptToMcp(page) {
  try {
    await page.waitForFunction(() => !document.querySelector('[data-testid="save-to-mcp-button"]')?.disabled, undefined, { timeout: 20_000 });
    await page.getByTestId("save-to-mcp-button").click();
    await page.waitForFunction(() => document.querySelector('[data-testid="global-status"]')?.textContent?.includes("Saved to MCP"), undefined, { timeout: 20_000 });
  } catch (error) {
    throw new Error(`Save to MCP did not complete after Copy Prompt: ${error.message}; diagnostics=${JSON.stringify(await browserDiagnostics(page))}`);
  }
}

async function runHandoffLifecycleAction(page, action) {
  if (action === "copy-prompt") return await copyPromptBeforeLifecycleSave(page);
  if (action === "save-to-mcp") {
    await savePromptToMcp(page);
    return null;
  }
  throw new Error(`Unknown handoff lifecycle action: ${action}`);
}

async function visibleAnnotationContainerSnapshots(page) {
  return await page.evaluate(() => {
    const visible = (node) => {
      if (!node || node.hidden) return false;
      const style = window.getComputedStyle(node);
      return style.display !== "none" && style.visibility !== "hidden";
    };
    return Array.from(document.querySelectorAll("#pendingItems, #draftItems")).map((node) => ({
      id: node.id,
      hidden: !visible(node),
      textContent: node.textContent || "",
    }));
  });
}

async function refreshBrowserSessionState(page) {
  return await page.evaluate(async () => {
    if (typeof refreshSessions === "function") {
      await refreshSessions();
      if (typeof render === "function") render();
      else if (typeof renderInspectorRegion === "function") renderInspectorRegion();
      return true;
    }
    return false;
  });
}

async function browserDiagnostics(page) {
  return await page.evaluate(() => ({
    copyPromptDisabled: document.getElementById("copyPromptButton")?.disabled ?? null,
    saveToMcpDisabled: document.querySelector('[data-testid="save-to-mcp-button"]')?.disabled ?? null,
    promptReadiness: document.getElementById("promptReadiness")?.textContent?.replace(/\s+/g, " ").trim() || null,
    globalStatus: document.querySelector('[data-testid="global-status"]')?.textContent?.replace(/\s+/g, " ").trim() || null,
    error: document.getElementById("error")?.textContent?.replace(/\s+/g, " ").trim() || null,
    copiedTextPrefix: typeof window.__fixthisCopiedText === "string" ? window.__fixthisCopiedText.slice(0, 160) : null,
    draftItemCount: window.FixThisConsoleDebug?.getDraftWorkspace?.()?.items?.length ?? null,
    sessionItemCount: window.FixThisConsoleDebug?.getState?.()?.session?.items?.length ?? null,
    sessionItems: (window.FixThisConsoleDebug?.getState?.()?.session?.items || []).map((item) => ({
      itemId: item.itemId,
      delivery: item.delivery || null,
      status: item.status || null,
      agentSummary: item.agentSummary || null,
    })),
  }));
}

async function browserSaveAndCopyFlow({ consoleUrl, headed, comments }) {
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
    for (let index = 0; index < comments.length; index += 1) {
      await addAreaAnnotation(page, comments[index], index);
    }
    let copiedText = null;
    for (const action of handoffLifecycleActionOrder()) {
      const result = await runHandoffLifecycleAction(page, action);
      if (action === "copy-prompt") copiedText = result;
    }
    return {
      copiedText,
      session: await page.evaluate(() => window.FixThisConsoleDebug?.getState?.()?.session),
      async waitForResolvedStatuses(expectedPlan) {
        try {
          await refreshBrowserSessionState(page);
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
          await page.waitForFunction(
            () => {
              const text = Array.from(document.querySelectorAll("#pendingItems, #draftItems"))
                .filter((node) => {
                  if (!node || node.hidden) return false;
                  const style = window.getComputedStyle(node);
                  return style.display !== "none" && style.visibility !== "hidden";
                })
                .map((node) => node.textContent || "")
                .join(" ");
              return text.includes("Resolved") && text.includes("Needs Clarification") && text.includes("Won't Fix");
            },
            undefined,
            { timeout: 20_000 },
          );
        } catch (error) {
          throw new Error(`Console did not refresh resolved MCP statuses: ${error.message}; diagnostics=${JSON.stringify(await browserDiagnostics(page))}`);
        }
        const bodyText = combineVisibleAnnotationStatusText(await visibleAnnotationContainerSnapshots(page));
        return {
          session: await page.evaluate(() => window.FixThisConsoleDebug?.getState?.()?.session),
          bodyText,
        };
      },
      close: () => browser.close(),
    };
  } catch (error) {
    await browser.close();
    const suffix = consoleMessages.length > 0 ? `; console=${consoleMessages.join(" | ")}` : "";
    throw new Error(`${error.message}${suffix}`);
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
    const report = buildReport({
      strict: options.strict,
      device: null,
      startedAt,
      finishedAt: new Date().toISOString(),
      firstHandoff: firstHandoffForEnvironment({
        strict: options.strict,
        androidReady: environment.ready,
        reason: environment.reason,
      }),
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
  const verifyReport = verifyReportForRuntimeFixture({
    fixture,
    projectWorkDir: paths.projectWorkDir,
    environment,
  });
  let mcp = null;
  let browserFlow = null;
  const fixtureResult = {
    fixtureId: fixture.id,
    packageName: fixture.applicationId,
    status: "fail",
    verifyReport,
    failures: [],
  };
  try {
    assertVerifyReportReadyForMcpTooling(verifyReport);
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
    const resolutionItemIds = selectResolutionItemIds(protocol.itemIds, browserFlow.session);
    const plan = expectedResolutionPlan(resolutionItemIds);
    const readFeedback = await mcp.callTool("fixthis_read_feedback", {
      sessionId: protocol.sessionId,
      includeAll: true,
    });
    const queueStats = assertReadFeedbackQueueContains(readFeedback, resolutionItemIds);
    for (const item of plan) {
      try {
        await mcp.callTool("fixthis_claim_feedback", {
          sessionId: protocol.sessionId,
          itemId: item.itemId,
          agentNote: `Agent loop smoke claiming ${item.itemId}`,
        });
      } catch (error) {
        throw new Error(`${categorizeFeedbackLifecycleFailure("claim", error)}: ${error.message}`);
      }
      try {
        await mcp.callTool("fixthis_resolve_feedback", {
          sessionId: protocol.sessionId,
          itemId: item.itemId,
          status: item.status,
          summary: item.summary,
        });
      } catch (error) {
        throw new Error(`${categorizeFeedbackLifecycleFailure("resolve", error)}: ${error.message}`);
      }
    }
    const reflected = await browserFlow.waitForResolvedStatuses(plan);
    const counts = assertLifecycleSessionState(reflected.session, plan);
    const firstHandoff = firstHandoffSuccess({
      savedItemCount: protocol.itemIds.length,
      readFeedbackItemCount: queueStats.itemCount,
      readFeedbackSentCount: queueStats.sentCount,
      autopilot: autopilotEvidenceForVerifyReport(verifyReport),
    });
    if (!reflected.bodyText.includes("Resolved") || !reflected.bodyText.includes("Needs Clarification") || !reflected.bodyText.includes("Won't Fix")) {
      throw new Error(`Console did not render all terminal statuses: ${reflected.bodyText}`);
    }
    Object.assign(fixtureResult, {
      status: "pass",
      savedItemCount: protocol.itemIds.length,
      readFeedbackItemCount: queueStats.itemCount,
      readFeedbackSentCount: queueStats.sentCount,
      firstHandoff,
      ...counts,
    });
  } catch (error) {
    const failureCode = categorizeFirstHandoffFailure(error);
    fixtureResult.firstHandoff = firstHandoffFailure({
      failureCode,
      message: error.message,
      details: { fixtureId: fixture.id },
    });
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
    firstHandoff: fixtureResult.firstHandoff,
    fixture: fixtureResult,
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
