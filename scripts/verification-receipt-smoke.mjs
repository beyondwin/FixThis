#!/usr/bin/env node
import { spawn, spawnSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import {
  chmodSync,
  existsSync,
  mkdirSync,
  readFileSync,
  realpathSync,
  rmSync,
  statSync,
  utimesSync,
  writeFileSync,
} from "node:fs";
import { createInterface } from "node:readline";
import { dirname, isAbsolute, join, relative, resolve, sep } from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { resolveAndroidEnvironment } from "./evidence-runner.mjs";

const scriptPath = fileURLToPath(import.meta.url);
const repoRoot = resolve(dirname(scriptPath), "..");
const defaultReportDir = "build/reports/fixthis-verification-receipt";
const fixtureRoot = join(repoRoot, "build/tmp/fixthis-verification-receipt");
const baselineButtonText = "Review receipt";
const changedButtonText = "Receipt verified";
const buttonTestTag = "comp:VerificationReceiptButton:primary";
const commandTimeoutMs = 8 * 60_000;
const toolTimeoutMs = 90_000;

export function createRunPaths(baseDirectory = fixtureRoot, runId = uniqueRunId()) {
  if (!/^[a-z0-9][a-z0-9-]*$/.test(String(runId))) throw new Error(`Invalid run id: ${runId}`);
  const runDirectory = resolve(baseDirectory, runId);
  const base = resolve(baseDirectory);
  if (!runDirectory.startsWith(`${base}${sep}`)) throw new Error(`Invalid run id: ${runId}`);
  return Object.freeze({ runId, runDirectory, fixtureDirectory: join(runDirectory, "fixture") });
}

function uniqueRunId() {
  return `run-${Date.now().toString(36)}-${randomBytes(4).toString("hex")}`;
}

export const requiredVerificationReceiptSteps = Object.freeze([
  "baseline_install",
  "feedback_sent_and_claimed",
  "stale_install_failed_receipt",
  "rebuilt_install_passed_receipt",
  "restart_replayed_receipts",
  "failed_receipt_rejected",
  "passing_receipt_resolved",
  "console_rendered_verified",
]);

export function parseArgs(argv = process.argv.slice(2)) {
  const options = {
    strict: false,
    headed: false,
    help: false,
    device: null,
    reportDir: defaultReportDir,
    maxRetries: 4,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--strict") options.strict = true;
    else if (arg === "--headed") options.headed = true;
    else if (arg === "--device") options.device = requiredValue(argv, ++index, arg);
    else if (arg === "--report-dir" || arg === "--out-dir") {
      options.reportDir = requiredValue(argv, ++index, arg);
    } else if (arg === "--max-retries") {
      const raw = requiredValue(argv, ++index, arg);
      const parsed = Number(raw);
      if (!Number.isInteger(parsed) || parsed <= 0 || parsed > 20) {
        throw new Error("--max-retries requires a positive integer no greater than 20");
      }
      options.maxRetries = parsed;
    } else if (arg === "-h" || arg === "--help") options.help = true;
    else throw new Error(`Unknown argument: ${arg}`);
  }
  return options;
}

function requiredValue(argv, index, flag) {
  const value = argv[index];
  if (!value || value.startsWith("--")) throw new Error(`${flag} requires a value`);
  return value;
}

function normalizedStep(step) {
  return {
    name: String(step?.name || ""),
    status: String(step?.status || "FAIL").toUpperCase(),
    evidence: step?.evidence == null ? null : String(step.evidence),
    ...(step?.details == null ? {} : { details: step.details }),
    ...(step?.durationMs == null ? {} : { durationMs: step.durationMs }),
  };
}

function requiredStepContract(steps) {
  const normalized = (steps || []).map(normalizedStep);
  const counts = new Map();
  for (const step of normalized) counts.set(step.name, (counts.get(step.name) || 0) + 1);
  const exactNames = normalized.length === requiredVerificationReceiptSteps.length &&
    requiredVerificationReceiptSteps.every((name) => counts.get(name) === 1) &&
    normalized.every((step) => requiredVerificationReceiptSteps.includes(step.name));
  const ordered = exactNames && normalized.every((step, index) => step.name === requiredVerificationReceiptSteps[index]);
  const allPass = ordered && normalized.every((step) => step.status === "PASS");
  return { normalized, exactNames, ordered, allPass };
}

export function buildReport({
  strict = false,
  deviceSerial = null,
  packageName = null,
  sessionId = null,
  steps = [],
  startedAt = null,
  finishedAt = null,
  failures = [],
  cleanup = null,
} = {}) {
  const contract = requiredStepContract(steps);
  const hasFailure = contract.normalized.some((step) => step.status === "FAIL") || failures.length > 0;
  const hasIncomplete = contract.normalized.some((step) => step.status === "DEFERRED" || step.status === "SKIPPED");
  const status = hasFailure
    ? "FAIL"
    : contract.allPass
      ? "PASS"
      : strict || !contract.exactNames || !contract.ordered
      ? "FAIL"
      : hasIncomplete
        ? "DEFERRED"
        : "FAIL";
  return {
    schemaVersion: "1.0",
    status,
    strict,
    generatedAt: new Date().toISOString(),
    startedAt,
    finishedAt,
    deviceSerial,
    packageName,
    sessionId,
    requiredSteps: [...requiredVerificationReceiptSteps],
    steps: contract.normalized,
    failures: failures.map(String),
    cleanup,
  };
}

export function assertStrictReport(report) {
  const contract = requiredStepContract(report?.steps);
  if (report?.status !== "PASS" || !contract.allPass || contract.normalized.some((step) =>
    step.status === "DEFERRED" || step.status === "SKIPPED")) {
    const failed = requiredVerificationReceiptSteps.filter((name) =>
      contract.normalized.find((step) => step.name === name)?.status !== "PASS");
    throw new Error(`verification receipt strict proof failed${failed.length ? `: ${failed.join(", ")}` : ""}`);
  }
  return report;
}

function markdownCell(value) {
  if (value == null || value === "") return "-";
  return String(value).replaceAll("|", "\\|").replace(/\s+/g, " ");
}

function renderReportMarkdown(report) {
  const lines = [
    "# FixThis Verification Receipt Product Path",
    "",
    `- Status: ${report.status}`,
    `- Strict: ${report.strict}`,
    `- Device: ${report.deviceSerial || "unavailable"}`,
    `- Package: ${report.packageName || "unavailable"}`,
    `- Session: ${report.sessionId || "unavailable"}`,
    "",
    "| Checkpoint | Status | Evidence |",
    "| --- | --- | --- |",
  ];
  for (const step of report.steps || []) {
    lines.push(`| ${markdownCell(step.name)} | ${markdownCell(step.status)} | ${markdownCell(step.evidence)} |`);
  }
  if (report.cleanup) {
    lines.push("", "## Cleanup", "", "```json", JSON.stringify(report.cleanup, null, 2), "```");
  }
  if ((report.failures || []).length > 0) {
    lines.push("", "## Failures", "");
    for (const failure of report.failures) lines.push(`- ${markdownCell(failure)}`);
  }
  return `${lines.join("\n")}\n`;
}

export function writeReport(report, reportDir = defaultReportDir) {
  const directory = isAbsolute(reportDir) ? reportDir : resolve(repoRoot, reportDir);
  mkdirSync(directory, { recursive: true });
  const json = join(directory, "report.json");
  const markdown = join(directory, "report.md");
  writeFileSync(json, `${JSON.stringify(report, null, 2)}\n`);
  writeFileSync(markdown, renderReportMarkdown(report));
  return { json, markdown };
}

function toolText(result) {
  return (result?.content || []).find((entry) => entry?.type === "text")?.text || "";
}

export async function mcpCall(client, name, args = {}, timeoutMs = toolTimeoutMs) {
  if (!client || typeof client.requestTool !== "function") throw new Error("MCP client requestTool is required");
  const result = await client.requestTool(name, args, timeoutMs);
  if (!result || typeof result !== "object") throw new Error(`${name} returned no MCP tool result`);
  if (result.isError === true) throw new Error(toolText(result) || `${name} failed`);
  return result;
}

function parseJsonToolResult(result, label) {
  for (const entry of result?.content || []) {
    if (entry?.type !== "text" || typeof entry.text !== "string") continue;
    try {
      const parsed = JSON.parse(entry.text);
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) return parsed;
    } catch {}
  }
  throw new Error(`${label} did not include JSON text content`);
}

export class McpStdioClient {
  constructor(child, lines, pending, stderr, closedState, stopChild = stopOwnedChild) {
    this.child = child;
    this.lines = lines;
    this.pending = pending;
    this.stderr = stderr;
    this.closedState = closedState;
    this.stopChild = stopChild;
    this.nextId = 1;
  }

  static async start({
    command,
    args,
    cwd,
    env,
    clientName,
    ownership = null,
    spawnImpl = spawn,
    initialize = async (client) => {
      await client.request("initialize", {
        protocolVersion: "2025-06-18",
        capabilities: {},
        clientInfo: { name: clientName, version: "0" },
      }, 45_000);
      client.notify("notifications/initialized", {});
    },
    stopChild = stopOwnedChild,
  }) {
    const child = spawnImpl(command, args, { cwd, env, stdio: ["pipe", "pipe", "pipe"] });
    const pending = new Map();
    const stderr = [];
    const closedState = { closed: false, code: null, signal: null };
    child.stderr.setEncoding("utf8");
    child.stderr.on("data", (chunk) => {
      stderr.push(String(chunk));
      if (stderr.join("").length > 64_000) stderr.splice(0, stderr.length - 4);
    });
    child.stdout.setEncoding("utf8");
    const lines = createInterface({ input: child.stdout });
    lines.on("line", (line) => {
      let response;
      try { response = JSON.parse(line); } catch { return; }
      const slot = pending.get(String(response.id));
      if (!slot) return;
      pending.delete(String(response.id));
      clearTimeout(slot.timer);
      if (response.error) slot.reject(new Error(response.error.message || JSON.stringify(response.error)));
      else slot.resolve(response.result);
    });
    const client = new McpStdioClient(child, lines, pending, stderr, closedState, stopChild);
    ownership?.trackMcp(client);
    child.once("error", (error) => client.rejectAll(new Error(`MCP child failed: ${error.message}`)));
    child.once("close", (code, signal) => {
      closedState.closed = true;
      closedState.code = code;
      closedState.signal = signal;
      client.rejectAll(new Error(`MCP child closed: code=${code ?? "unknown"} signal=${signal ?? "none"}${client.stderrText()}`));
    });
    try {
      await initialize(client);
      return client;
    } catch (error) {
      if (ownership) await ownership.stopMcp(client);
      else await client.close().catch(() => {});
      throw error;
    }
  }

  get pid() {
    return this.child.pid || null;
  }

  stderrText() {
    const text = this.stderr.join("").trim();
    return text ? `\n${text}` : "";
  }

  rejectAll(error) {
    for (const [id, slot] of this.pending) {
      clearTimeout(slot.timer);
      this.pending.delete(id);
      slot.reject(error);
    }
  }

  send(message) {
    if (this.closedState.closed) throw new Error(`MCP child is closed${this.stderrText()}`);
    this.child.stdin.write(`${JSON.stringify(message)}\n`);
  }

  notify(method, params) {
    this.send({ jsonrpc: "2.0", method, params });
  }

  async request(method, params, timeoutMs = 30_000) {
    const id = this.nextId++;
    const response = new Promise((resolvePromise, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(String(id));
        reject(new Error(`Timed out waiting for MCP ${method}${this.stderrText()}`));
      }, timeoutMs);
      this.pending.set(String(id), { resolve: resolvePromise, reject, timer });
    });
    this.send({ jsonrpc: "2.0", id, method, params });
    return response;
  }

  requestTool(name, args, timeoutMs) {
    return this.request("tools/call", { name, arguments: args }, timeoutMs);
  }

  async close() {
    if (this.closedState.closed) return true;
    this.lines.close();
    this.child.stdin.end();
    return this.stopChild(this.child);
  }
}

function childExited(child) {
  return child?.exitCode != null || child?.signalCode != null;
}

async function waitForChildExit(child, timeoutMs) {
  if (childExited(child)) return true;
  return Promise.race([
    new Promise((resolvePromise) => child.once("close", () => resolvePromise(true))),
    delay(timeoutMs).then(() => childExited(child)),
  ]);
}

export async function stopOwnedChild(child, { graceMs = 5_000, killMs = 2_000 } = {}) {
  if (!child || childExited(child)) return true;
  child.kill("SIGTERM");
  if (await waitForChildExit(child, graceMs)) return true;
  child.kill("SIGKILL");
  return waitForChildExit(child, killMs);
}

function ownedProcessGroupExists(pid) {
  try {
    process.kill(-pid, 0);
    return true;
  } catch (error) {
    if (error?.code === "ESRCH") return false;
    if (error?.code === "EPERM") return true;
    throw error;
  }
}

async function waitForProcessGroupExit(pid, timeoutMs, groupExists, wait) {
  const deadline = Date.now() + timeoutMs;
  while (groupExists(pid)) {
    const remaining = deadline - Date.now();
    if (remaining <= 0) return false;
    await wait(Math.min(50, remaining));
  }
  return true;
}

export async function terminateOwnedProcessGroup(child, {
  killGroup = (pid, signal) => {
    try { process.kill(-pid, signal); } catch (error) {
      if (error?.code !== "ESRCH") throw error;
    }
  },
  groupExists = ownedProcessGroupExists,
  wait = delay,
  graceMs = 5_000,
  killMs = 2_000,
} = {}) {
  if (!child?.pid || !groupExists(child.pid)) return true;
  killGroup(child.pid, "SIGTERM");
  if (await waitForProcessGroupExit(child.pid, graceMs, groupExists, wait)) return true;
  killGroup(child.pid, "SIGKILL");
  return waitForProcessGroupExit(child.pid, killMs, groupExists, wait);
}

export function createOwnedResourceController() {
  const mcps = new Map();
  const processGroups = new Map();
  const tasks = [];
  const failures = [];
  const cleanup = {
    ownedMcpPids: [],
    ownedMcpStopped: [],
    ownedProcessGroupPids: [],
    ownedProcessGroupsStopped: [],
  };
  let cleanupPromise = null;

  async function stopMcp(client, finalAttempt = false) {
    if (!client) return true;
    const pid = client.pid;
    const stopped = await client.close().catch((error) => {
      failures.push(`Owned MCP PID ${pid} cleanup failed: ${error.message}`);
      return false;
    });
    if (stopped === true) {
      if (!cleanup.ownedMcpStopped.includes(pid)) cleanup.ownedMcpStopped.push(pid);
      mcps.delete(pid);
    } else if (finalAttempt) failures.push(`Unconfirmed owned MCP PID ${pid} stop`);
    return stopped === true;
  }

  return {
    failures,
    summary: cleanup,
    trackMcp(client) {
      if (client?.pid != null && !mcps.has(client.pid)) {
        mcps.set(client.pid, client);
        cleanup.ownedMcpPids.push(client.pid);
      }
      return client;
    },
    stopMcp,
    trackProcessGroup(child) {
      if (child?.pid != null && !processGroups.has(child.pid)) {
        processGroups.set(child.pid, child);
        cleanup.ownedProcessGroupPids.push(child.pid);
      }
    },
    releaseProcessGroup(child, stopped = childExited(child)) {
      if (!child?.pid || !stopped) return;
      processGroups.delete(child.pid);
      if (!cleanup.ownedProcessGroupsStopped.includes(child.pid)) cleanup.ownedProcessGroupsStopped.push(child.pid);
    },
    addCleanupTask(label, operation, priority = 20) {
      tasks.push({ label, operation, priority });
    },
    cleanup(reason = "normal") {
      if (cleanupPromise) return cleanupPromise;
      cleanup.reason = reason;
      cleanupPromise = (async () => {
        for (const child of [...processGroups.values()]) {
          const stopped = await terminateOwnedProcessGroup(child).catch((error) => {
            failures.push(`Owned process group PID ${child.pid} cleanup failed: ${error.message}`);
            return false;
          });
          if (stopped) {
            processGroups.delete(child.pid);
            cleanup.ownedProcessGroupsStopped.push(child.pid);
          } else failures.push(`Unconfirmed owned process group PID ${child.pid} stop`);
        }
        for (const client of [...mcps.values()]) await stopMcp(client, true);
        for (const task of [...tasks].sort((left, right) => left.priority - right.priority)) {
          try { Object.assign(cleanup, await task.operation()); } catch (error) {
            failures.push(`${task.label} cleanup failed: ${error.message}`);
          }
        }
        return { ...cleanup, failures: [...failures] };
      })();
      return cleanupPromise;
    },
  };
}

export function installLifecycleCleanup({ processLike = process, cleanup, exit = (code) => process.exit(code) }) {
  let handling = null;
  const begin = (code, reason) => {
    if (handling) return;
    handling = Promise.resolve()
      .then(() => cleanup(reason))
      .catch(() => {})
      .finally(() => exit(code));
  };
  const handlers = {
    SIGINT: () => begin(130, "SIGINT"),
    SIGTERM: () => begin(143, "SIGTERM"),
    uncaughtException: () => begin(1, "uncaughtException"),
    unhandledRejection: () => begin(1, "unhandledRejection"),
  };
  for (const [event, handler] of Object.entries(handlers)) processLike.on(event, handler);
  return () => {
    for (const [event, handler] of Object.entries(handlers)) processLike.removeListener(event, handler);
  };
}

function runChecked(command, args, { cwd = repoRoot, env = process.env, timeout = commandTimeoutMs } = {}) {
  const printable = [command, ...args].join(" ");
  process.stdout.write(`[verification-receipt] RUN ${printable}\n`);
  const started = Date.now();
  const result = spawnSync(command, args, {
    cwd,
    env,
    encoding: "utf8",
    stdio: "pipe",
    timeout,
    maxBuffer: 32 * 1024 * 1024,
  });
  if (result.error || result.status !== 0) {
    const output = `${result.stdout || ""}\n${result.stderr || ""}`.trim().split(/\r?\n/).slice(-80).join("\n");
    throw new Error(`${printable} failed (${result.status ?? result.error?.code ?? "spawn"})${output ? `\n${output}` : ""}`);
  }
  process.stdout.write(`[verification-receipt] PASS ${printable} (${Date.now() - started}ms)\n`);
  return result;
}

export async function runOwnedCommand(command, args, {
  cwd = repoRoot,
  env = process.env,
  timeout = commandTimeoutMs,
  ownership = null,
  spawnImpl = spawn,
  terminateGroup = terminateOwnedProcessGroup,
  groupExists = ownedProcessGroupExists,
} = {}) {
  const printable = [command, ...args].join(" ");
  process.stdout.write(`[verification-receipt] RUN ${printable}\n`);
  const started = Date.now();
  const child = spawnImpl(command, args, {
    cwd,
    env,
    detached: true,
    stdio: ["ignore", "pipe", "pipe"],
  });
  ownership?.trackProcessGroup(child);
  let stdout = "";
  let stderr = "";
  child.stdout?.setEncoding?.("utf8");
  child.stderr?.setEncoding?.("utf8");
  child.stdout?.on?.("data", (chunk) => { stdout = `${stdout}${chunk}`.slice(-32 * 1024 * 1024); });
  child.stderr?.on?.("data", (chunk) => { stderr = `${stderr}${chunk}`.slice(-32 * 1024 * 1024); });
  const closed = new Promise((resolvePromise) => {
    child.once("error", (error) => resolvePromise({ kind: "error", error }));
    child.once("close", (code, signal) => resolvePromise({ kind: "close", code, signal }));
  });
  let timeoutTriggered = false;
  let terminationPromise = null;
  let timer;
  const timedOut = new Promise((resolvePromise) => {
    timer = setTimeout(() => {
      timeoutTriggered = true;
      terminationPromise = terminateGroup(child).catch(() => false);
      terminationPromise.then((stopped) => resolvePromise({ kind: "timeout", stopped }));
    }, timeout);
    timer.unref?.();
  });
  const result = await Promise.race([closed, timedOut]);
  clearTimeout(timer);
  if (timeoutTriggered || result.kind === "timeout") {
    const stopped = result.kind === "timeout" ? result.stopped : await terminationPromise;
    ownership?.releaseProcessGroup(child, stopped);
    throw new Error(`${printable} timed out; owned process group PID ${child.pid} ${stopped ? "stopped" : "stop unconfirmed"}`);
  }
  if (result.kind === "error") {
    const stopped = await terminateGroup(child).catch(() => false);
    ownership?.releaseProcessGroup(child, stopped);
    throw new Error(`${printable} failed (${result.error?.code ?? "spawn"}); owned process group PID ${child.pid} ${stopped ? "stopped" : "stop unconfirmed"}`);
  }
  const groupStopped = !groupExists(child.pid) || await terminateGroup(child).catch(() => false);
  ownership?.releaseProcessGroup(child, groupStopped);
  if (!groupStopped) {
    throw new Error(`${printable} completed but owned process group PID ${child.pid} stop unconfirmed`);
  }
  if (result.code !== 0) {
    const output = `${stdout}\n${stderr}`.trim().split(/\r?\n/).slice(-80).join("\n");
    throw new Error(`${printable} failed (${result.code ?? result.signal ?? "spawn"})${output ? `\n${output}` : ""}`);
  }
  process.stdout.write(`[verification-receipt] PASS ${printable} (${Date.now() - started}ms)\n`);
  return { status: result.code, signal: result.signal, stdout, stderr };
}

function writeGenerated(path, body, mode = null) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, body);
  if (mode != null) chmodSync(path, mode);
}

function kotlinString(value) {
  return JSON.stringify(String(value));
}

export function fixtureSettings({ pluginDirectory, versionCatalogPath, localMavenDirectory }) {
  return `
pluginManagement {
    includeBuild(${kotlinString(pluginDirectory)})
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri(${kotlinString(localMavenDirectory)}) }
        google()
        mavenCentral()
    }
    defaultLibrariesExtensionName = "defaultLibs"
    versionCatalogs {
        create("libs") { from(files(${kotlinString(versionCatalogPath)})) }
    }
}
rootProject.name = "FixThisVerificationReceiptFixture"
include(":app")
`.trimStart();
}

function uniquePackageName() {
  const suffix = `${Date.now().toString(36)}${randomBytes(3).toString("hex")}`.toLowerCase();
  return `io.github.beyondwin.fixthis.receipt.r${suffix}`;
}

export function generateOwnedFixture({ environment, runPaths, ownership, generate = generateFixture }) {
  Object.assign(ownership.summary, {
    fixtureDirectoryRemoved: false,
    runDirectory: runPaths.runDirectory,
  });
  ownership.addCleanupTask("Fixture", () => {
    rmSync(runPaths.runDirectory, { recursive: true, force: true });
    return {
      fixtureDirectoryRemoved: !existsSync(runPaths.fixtureDirectory),
      removedFixtureDirectory: runPaths.fixtureDirectory,
      runDirectoryRemoved: !existsSync(runPaths.runDirectory),
    };
  }, 20);
  return generate({ environment, runPaths });
}

function generateFixture({ environment, runPaths }) {
  const { fixtureDirectory, runDirectory } = runPaths;
  rmSync(fixtureDirectory, { recursive: true, force: true });
  mkdirSync(fixtureDirectory, { recursive: true });
  const packageName = uniquePackageName();
  const namespace = "io.github.beyondwin.fixthis.receipt";
  const sourcePath = join(fixtureDirectory, "app/src/main/java/io/github/beyondwin/fixthis/receipt/MainActivity.kt");
  const localMavenDirectory = join(fixtureDirectory, "local-maven");
  writeGenerated(join(fixtureDirectory, "settings.gradle.kts"), fixtureSettings({
    pluginDirectory: join(repoRoot, "fixthis-gradle-plugin"),
    versionCatalogPath: join(repoRoot, "gradle/libs.versions.toml"),
    localMavenDirectory,
  }));
  writeGenerated(join(fixtureDirectory, "build.gradle.kts"), `
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
allprojects {
    group = "io.github.beyondwin"
    version = "1.5.0"
}
`.trimStart());
  writeGenerated(join(fixtureDirectory, "gradle.properties"), `org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8\nkotlin.code.style=official\n`);
  const sdk = environment.envPatch?.ANDROID_HOME || environment.envPatch?.ANDROID_SDK_ROOT;
  if (sdk) writeGenerated(join(fixtureDirectory, "local.properties"), `sdk.dir=${sdk.replaceAll("\\", "\\\\")}\n`);
  writeGenerated(join(fixtureDirectory, "app/build.gradle.kts"), `
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("io.github.beyondwin.fixthis.compose")
}
android {
    namespace = ${kotlinString(namespace)}
    compileSdk = 34
    defaultConfig {
        applicationId = ${kotlinString(packageName)}
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures { compose = true }
}
fixthis {
    addDebugRuntime.set(true)
    generateSourceIndex.set(true)
    generateProjectMetadata.set(true)
}
dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
}
`.trimStart());
  writeGenerated(join(fixtureDirectory, "app/src/main/AndroidManifest.xml"), `
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <application android:label="FixThis Receipt Fixture" android:theme="@style/AppTheme">
    <activity android:name=".MainActivity" android:exported="true">
      <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
      </intent-filter>
    </activity>
  </application>
</manifest>
`.trimStart());
  writeGenerated(join(fixtureDirectory, "app/src/main/res/values/styles.xml"), `
<resources>
  <style name="AppTheme" parent="android:style/Theme.Material.Light.NoActionBar">
    <item name="android:fontFamily">sans</item>
  </style>
</resources>
`.trimStart());
  writeGenerated(sourcePath, fixtureSource(baselineButtonText));
  return { fixtureDirectory, runDirectory, localMavenDirectory, packageName, namespace, sourcePath };
}

function fixtureSource(buttonText) {
  return `
package io.github.beyondwin.fixthis.receipt

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Button
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Box(Modifier.fillMaxSize().wrapContentSize()) {
                Button(
                    onClick = {},
                    modifier = Modifier.testTag(${kotlinString(buttonTestTag)}),
                ) {
                    Text(${kotlinString(buttonText)})
                }
            }
        }
    }
}
`.trimStart();
}

function adbArgs(environment, ...args) {
  return ["-s", environment.device, ...args];
}

export function packageCleanupOutcome({
  packagePathStatus,
  packagePathOutput = "",
  uninstallStatus = null,
  uninstallOutput = "",
}) {
  if (packagePathStatus !== 0) return { packageWasInstalled: null, packageUninstalled: false };
  const packageWasInstalled = /^package:/m.test(packagePathOutput);
  if (!packageWasInstalled) return { packageWasInstalled: false, packageUninstalled: true };
  return {
    packageWasInstalled: true,
    packageUninstalled: uninstallStatus === 0 && /^Success\s*$/m.test(uninstallOutput),
  };
}

function cleanupFixturePackage(fixture, environment) {
  const packagePath = spawnSync("adb", adbArgs(environment, "shell", "pm", "path", fixture.packageName), {
    cwd: repoRoot,
    env: environment.env,
    encoding: "utf8",
    timeout: 60_000,
  });
  const packagePathOutput = `${packagePath.stdout || ""}${packagePath.stderr || ""}`;
  const packageWasInstalled = packagePath.status === 0 && /^package:/m.test(packagePathOutput);
  const uninstall = packageWasInstalled
    ? spawnSync("adb", adbArgs(environment, "uninstall", fixture.packageName), {
      cwd: repoRoot,
      env: environment.env,
      encoding: "utf8",
      timeout: 60_000,
    })
    : null;
  const outcome = packageCleanupOutcome({
    packagePathStatus: packagePath.status,
    packagePathOutput,
    uninstallStatus: uninstall?.status ?? null,
    uninstallOutput: `${uninstall?.stdout || ""}${uninstall?.stderr || ""}`,
  });
  if (!outcome.packageUninstalled) {
    throw new Error(String(uninstall?.stderr || uninstall?.stdout || packagePath.stderr || packagePath.stdout));
  }
  return {
    packageWasInstalled: outcome.packageWasInstalled,
    packageUninstalled: outcome.packageUninstalled,
    uninstalledPackage: fixture.packageName,
  };
}

export function assertActivityStartOutput(output, packageName, activityClass) {
  const expectedActivity = `${packageName}/${activityClass}`;
  const started = /^Status:\s*ok\s*$/mi.test(String(output)) &&
    new RegExp(`^Activity:\\s*${expectedActivity.replaceAll(".", "\\.")}\\s*$`, "mi").test(String(output));
  if (!started) throw new Error(`ADB did not start ${expectedActivity}: ${String(output).trim()}`);
}

function isRetryableActivityStartTimeout(output, packageName, activityClass) {
  const expectedActivity = `${packageName}/${activityClass}`;
  return /^Status:\s*timeout\s*$/mi.test(String(output)) &&
    /^LaunchState:\s*UNKNOWN(?:\s*\(-?\d+\))?\s*$/mi.test(String(output)) &&
    new RegExp(`^Activity:\\s*${expectedActivity.replaceAll(".", "\\.")}\\s*$`, "mi").test(String(output));
}

export async function coldLaunch(environment, fixture, {
  run = runChecked,
  attempts = 2,
  delay: retryDelay = delay,
} = {}) {
  const activityClass = `${fixture.namespace}.MainActivity`;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    run("adb", adbArgs(environment, "shell", "am", "force-stop", fixture.packageName), { env: environment.env });
    const started = run("adb", adbArgs(environment, "shell", "am", "start", "-W", "-n", `${fixture.packageName}/${activityClass}`), {
      env: environment.env,
      timeout: 60_000,
    });
    const output = `${started.stdout || ""}\n${started.stderr || ""}`;
    try {
      assertActivityStartOutput(output, fixture.packageName, activityClass);
      return;
    } catch (error) {
      const retryable = isRetryableActivityStartTimeout(output, fixture.packageName, activityClass);
      if (!retryable || attempt >= attempts) throw error;
      process.stdout.write(`[verification-receipt] RETRY cold launch after exact timeout/UNKNOWN (${attempt}/${attempts})\n`);
      await retryDelay(1_500);
    }
  }
}

async function retry(label, operation, { attempts = 4, delayMs = 1_500 } = {}) {
  let lastError;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      return await operation(attempt);
    } catch (error) {
      lastError = error;
      if (attempt < attempts) await delay(delayMs * attempt);
    }
  }
  throw new Error(`${label} failed after ${attempts} attempts: ${lastError?.message || lastError}`);
}

function delay(milliseconds) {
  return new Promise((resolvePromise) => setTimeout(resolvePromise, milliseconds));
}

async function stopOwnedEmulator(ownedEmulator) {
  if (!ownedEmulator || ownedEmulator.stopped) return true;
  const { child } = ownedEmulator;
  if (child.exitCode == null && child.signalCode == null) child.kill("SIGTERM");
  let stopped = child.exitCode != null || child.signalCode != null;
  if (!stopped) {
    stopped = await Promise.race([
      new Promise((resolvePromise) => child.once("close", () => resolvePromise(true))),
      delay(5_000).then(() => false),
    ]);
  }
  if (!stopped && child.exitCode == null && child.signalCode == null) {
    child.kill("SIGKILL");
    stopped = await Promise.race([
      new Promise((resolvePromise) => child.once("close", () => resolvePromise(true))),
      delay(2_000).then(() => false),
    ]);
  }
  ownedEmulator.stopped = stopped || child.exitCode != null || child.signalCode != null;
  return ownedEmulator.stopped;
}

function consoleAccess(consoleUrl) {
  const pageUrl = new URL(consoleUrl);
  const token = new URLSearchParams(pageUrl.hash.slice(1)).get("consoleToken");
  if (!token) throw new Error("Console URL is missing consoleToken");
  pageUrl.hash = "";
  const origin = pageUrl.origin;
  async function request(path, { method = "GET", body = undefined } = {}) {
    const response = await fetch(new URL(path, origin), {
      method,
      headers: {
        Accept: "application/json",
        ...(body === undefined ? {} : { "Content-Type": "application/json" }),
        "X-FixThis-Console-Token": token,
        Origin: origin,
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    const text = await response.text();
    if (!response.ok) throw new Error(`${method} ${path} failed (${response.status}): ${text}`);
    return text ? JSON.parse(text) : null;
  }
  return { pageUrl: consoleUrl, origin, token, request };
}

function flattenedNodes(screen) {
  const nodes = [];
  for (const root of screen?.roots || []) {
    for (const node of root?.mergedNodes || []) nodes.push(node);
    for (const node of root?.unmergedNodes || []) nodes.push(node);
  }
  return nodes;
}

function buttonNode(screen) {
  const node = flattenedNodes(screen).find((candidate) => candidate?.testTag === buttonTestTag);
  if (!node) {
    const tags = flattenedNodes(screen).map((candidate) => candidate?.testTag).filter(Boolean);
    throw new Error(`Tagged fixture button was not captured; observed tags=${JSON.stringify(tags)}`);
  }
  if (!node.uid || !node.boundsInWindow) throw new Error("Tagged fixture button is missing uid or boundsInWindow");
  return node;
}

function receiptFrom(result) {
  const receipt = result?.structuredContent?.receipt;
  if (!receipt?.receiptId || !receipt?.verdict) throw new Error("Verification tool omitted structured receipt");
  return receipt;
}

function requireCondition(condition, message) {
  if (!condition) throw new Error(message);
}

function checkKinds(receipt) {
  return (receipt?.checks || []).map((check) => check?.kind).filter(Boolean);
}

function assertContainedAfterArtifact(projectDir, sessionId, receipt) {
  const configured = receipt?.afterScreenshot?.desktopFullPath;
  requireCondition(Boolean(configured), `Receipt ${receipt?.receiptId} is missing after screenshot path`);
  const expectedRoot = resolve(projectDir, `.fixthis/feedback-sessions/${sessionId}/verification/${receipt.receiptId}`);
  const artifact = realpathSync(isAbsolute(configured) ? configured : resolve(projectDir, configured));
  const root = realpathSync(expectedRoot);
  requireCondition(artifact.startsWith(`${root}${sep}`), `Receipt artifact escaped its receipt directory: ${artifact}`);
  requireCondition(relative(root, artifact) === "after.png", `Receipt artifact is not exact after.png: ${artifact}`);
  requireCondition(statSync(artifact).isFile(), `Receipt artifact is not a regular file: ${artifact}`);
  return artifact;
}

async function waitForBridge(client, packageName, attempts) {
  return retry("FixThis bridge status", async () => {
    const status = parseJsonToolResult(await mcpCall(client, "fixthis_status", { packageName }, 30_000), "fixthis_status");
    requireCondition(status.sidekickConnected === true && status.appRunning === true, `Bridge not ready: ${JSON.stringify(status)}`);
    return status;
  }, { attempts: Math.max(attempts, 8), delayMs: 1_000 });
}

async function waitForChangedText(client, packageName, attempts) {
  return retry("changed fixture text", async () => {
    const result = parseJsonToolResult(
      await mcpCall(client, "fixthis_verify_ui_change", { packageName, expectedText: changedButtonText }, 30_000),
      "fixthis_verify_ui_change",
    );
    requireCondition(result.found === true, `Changed text not visible: ${JSON.stringify(result)}`);
    return result;
  }, { attempts: Math.max(attempts, 8), delayMs: 1_000 });
}

function androidFailureDiagnostics(fixture, environment) {
  const commandOutputs = [
    ["shell", "pidof", fixture.packageName],
    ["shell", "dumpsys", "activity", "activities"],
    ["logcat", "-d", "-t", "600", "FixThisBridge:V", "FixThisInitializer:V", "AndroidRuntime:E", "*:S"],
  ].map((args) => {
    const result = spawnSync("adb", adbArgs(environment, ...args), {
      cwd: repoRoot,
      env: environment.env,
      encoding: "utf8",
      timeout: 30_000,
      maxBuffer: 4 * 1024 * 1024,
    });
    const output = `${result.stdout || ""}${result.stderr || ""}`.trim();
    return `${args.join(" ")} status=${result.status ?? result.error?.code ?? "spawn"}\n${output}`;
  });
  const manifestPath = join(
    fixture.fixtureDirectory,
    "app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml",
  );
  const manifest = existsSync(manifestPath) ? readFileSync(manifestPath, "utf8") : "";
  commandOutputs.push(
    `mergedManifest=${manifestPath} exists=${existsSync(manifestPath)} ` +
      `startupProvider=${manifest.includes("androidx.startup.InitializationProvider")} ` +
      `fixThisInitializer=${manifest.includes("FixThisInitializer")}`,
  );
  return commandOutputs.join("\n---\n").split(/\r?\n/).slice(-220).join("\n");
}

async function ensureMcpDistribution(environment, ownership) {
  await runOwnedCommand("./gradlew", [":fixthis-mcp:installDist", "--no-daemon"], {
    env: environment.env,
    ownership,
  });
  const binary = join(repoRoot, "fixthis-mcp/build/install/fixthis-mcp/bin/fixthis-mcp");
  requireCondition(existsSync(binary), `MCP distribution missing: ${binary}`);
  return binary;
}

async function startMcp(binary, fixture, environment, name, ownership) {
  return McpStdioClient.start({
    command: binary,
    args: ["--project-dir", fixture.fixtureDirectory, "--package", fixture.packageName],
    cwd: repoRoot,
    env: environment.env,
    clientName: name,
    ownership,
  });
}

async function publishLocalRuntimeArtifacts(fixture, environment, ownership) {
  await runOwnedCommand(
    "./gradlew",
    [
      `-Dmaven.repo.local=${fixture.localMavenDirectory}`,
      ":fixthis-compose-core:publishToMavenLocal",
      ":fixthis-compose-sidekick:publishToMavenLocal",
      "--no-daemon",
    ],
    { env: environment.env, ownership },
  );
}

async function installFixture(fixture, environment, ownership) {
  await runOwnedCommand(
    "./gradlew",
    ["-p", fixture.fixtureDirectory, ":app:installDebug", "-Pfixthis.runtimeCompatibleSourceIndex=true", "--no-daemon"],
    { env: environment.env, ownership },
  );
}

function advanceSourceBeyondInstall(fixture, installEpochMillis) {
  const sourceMtime = Math.max(Date.now(), Number(installEpochMillis) + 1_000);
  writeFileSync(fixture.sourcePath, fixtureSource(changedButtonText));
  const timestamp = new Date(sourceMtime);
  utimesSync(fixture.sourcePath, timestamp, timestamp);
  requireCondition(statSync(fixture.sourcePath).mtimeMs > Number(installEpochMillis), "Fixture source mtime did not advance beyond install epoch");
  return sourceMtime;
}

async function waitUntilAfter(epochMillis) {
  const remaining = Math.ceil(epochMillis - Date.now() + 250);
  if (remaining > 0) await delay(Math.min(remaining, 5_000));
}

export function savedItemRowSelector(itemId) {
  if (!/^[A-Za-z0-9-]+$/.test(String(itemId))) throw new Error(`Invalid item id for browser selector: ${itemId}`);
  return `[data-focus-saved="${itemId}"]`;
}

async function proveConsole({ consoleUrl, sessionId, itemId, receiptId, headed }) {
  const { chromium } = await import("playwright");
  const browser = await chromium.launch({ headless: !headed });
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  const messages = [];
  page.on("console", (message) => messages.push(`${message.type()}: ${message.text()}`));
  page.on("pageerror", (error) => messages.push(`pageerror: ${error.message}`));
  try {
    await page.goto(consoleUrl, { waitUntil: "domcontentloaded", timeout: 60_000 });
    await page.waitForFunction((expectedSessionId) =>
      window.FixThisConsoleDebug?.getState?.()?.session?.sessionId === expectedSessionId,
    sessionId, { timeout: 60_000 });
    await page.waitForFunction(({ expectedItemId, expectedReceiptId }) => {
      const row = [...document.querySelectorAll("[data-focus-saved]")]
        .find((candidate) => candidate.dataset.focusSaved === expectedItemId);
      const badge = row?.querySelector(".verification-badge[data-tone='success']");
      const state = window.FixThisConsoleDebug?.getState?.()?.session;
      const item = state?.items?.find((candidate) => candidate.itemId === expectedItemId);
      return badge?.textContent?.trim() === "verified" &&
        item?.resolutionVerificationReceiptId === expectedReceiptId;
    }, { expectedItemId: itemId, expectedReceiptId: receiptId }, { timeout: 60_000 });
    const row = page.locator(savedItemRowSelector(itemId));
    const badge = await row.locator(".verification-badge[data-tone='success']").textContent();
    requireCondition(badge?.trim() === "verified", `Console badge was not exact verified: ${badge}`);
    await row.click();
    await page.waitForFunction((expectedReceiptId) => {
      const section = document.querySelector(".verification-receipt-section");
      return [...(section?.querySelectorAll("dd") || [])].some((node) => node.textContent?.trim() === expectedReceiptId);
    }, receiptId, { timeout: 30_000 });
    const detail = await page.locator(".verification-receipt-section").textContent();
    requireCondition(detail?.includes(receiptId), `Console detail omitted linked receipt ${receiptId}`);
    return { badge: badge.trim(), linkedReceiptId: receiptId, pageErrors: messages.filter((line) => line.startsWith("pageerror")) };
  } catch (error) {
    throw new Error(`${error.message}${messages.length ? `; console=${messages.join(" | ")}` : ""}`);
  } finally {
    await browser.close();
  }
}

function stepRecorder(steps) {
  return async function record(name, operation) {
    const started = Date.now();
    process.stdout.write(`[verification-receipt] CHECKPOINT ${name}\n`);
    try {
      const result = await operation();
      const step = {
        name,
        status: "PASS",
        evidence: result?.evidence || `${name} passed`,
        ...(result?.details == null ? {} : { details: result.details }),
        durationMs: Date.now() - started,
      };
      steps.push(step);
      process.stdout.write(`[verification-receipt] PASS ${name}\n`);
      return result?.value;
    } catch (error) {
      steps.push({ name, status: "FAIL", evidence: error.message, durationMs: Date.now() - started });
      throw error;
    }
  };
}

async function executeConnectedProductPath({ options, environment, ownership, runPaths }) {
  const cleanup = ownership.summary;
  const fixture = generateOwnedFixture({ environment, runPaths, ownership });
  const steps = [];
  const record = stepRecorder(steps);
  Object.assign(cleanup, {
    packageUninstalled: false,
    emulatorPid: environment.ownedEmulator?.pid || null,
    ownedEmulatorStopped: environment.ownedEmulator ? false : null,
  });
  ownership.addCleanupTask("Package", () => cleanupFixturePackage(fixture, environment), 10);
  const failures = ownership.failures;
  let mcp = null;
  let sessionId = null;
  let itemId = null;
  let consoleUrl = null;
  let failedReceipt = null;
  let passingReceipt = null;
  let latestFailedReceipt = null;
  let baselineStatus = null;
  let sourceMtime = null;
  try {
    const mcpBinary = await ensureMcpDistribution(environment, ownership);
    await record("baseline_install", async () => {
      await publishLocalRuntimeArtifacts(fixture, environment, ownership);
      await installFixture(fixture, environment, ownership);
      await coldLaunch(environment, fixture);
      mcp = await startMcp(mcpBinary, fixture, environment, "verification-receipt-baseline", ownership);
      try {
        baselineStatus = await waitForBridge(mcp, fixture.packageName, options.maxRetries);
      } catch (error) {
        throw new Error(`${error.message}\nAndroid diagnostics:\n${androidFailureDiagnostics(fixture, environment)}`);
      }
      requireCondition(Number.isFinite(baselineStatus.installedAtEpochMillis), "Baseline status omitted installedAtEpochMillis");
      return {
        evidence: `Installed and cold-launched ${fixture.packageName} on ${environment.device}`,
        details: { packageName: fixture.packageName, installedAtEpochMillis: baselineStatus.installedAtEpochMillis },
      };
    });

    await record("feedback_sent_and_claimed", async () => {
      const opened = parseJsonToolResult(await mcpCall(mcp, "fixthis_open_feedback_console", {
        packageName: fixture.packageName,
        newSession: true,
      }), "fixthis_open_feedback_console");
      sessionId = opened.sessionId;
      consoleUrl = opened.consoleUrl;
      const captured = parseJsonToolResult(await mcpCall(mcp, "fixthis_capture_screen", { sessionId }), "fixthis_capture_screen");
      const node = buttonNode(captured.screen);
      const consoleApi = consoleAccess(consoleUrl);
      const item = await consoleApi.request("/api/items", {
        method: "POST",
        body: {
          sessionId,
          screenId: captured.screen.screenId,
          comment: "Prove the verification receipt product path",
          targetType: "node",
          nodeUid: node.uid,
          bounds: node.boundsInWindow,
        },
      });
      itemId = item.itemId;
      await consoleApi.request("/api/agent-handoffs", {
        method: "POST",
        body: { sessionId, itemIds: [itemId] },
      });
      const claimed = parseJsonToolResult(await mcpCall(mcp, "fixthis_claim_feedback", {
        sessionId,
        itemId,
        agentNote: "Verification receipt strict smoke",
      }), "fixthis_claim_feedback");
      requireCondition(claimed.status === "in_progress", `Claim did not set in_progress: ${claimed.status}`);
      return { evidence: `Created, sent, and claimed ${itemId}`, details: { sessionId, itemId, nodeUid: node.uid } };
    });

    await record("stale_install_failed_receipt", async () => {
      sourceMtime = advanceSourceBeyondInstall(fixture, baselineStatus.installedAtEpochMillis);
      failedReceipt = receiptFrom(await mcpCall(mcp, "fixthis_verify_feedback", {
        sessionId,
        itemId,
        assertions: [{ kind: "target_present" }],
      }));
      requireCondition(failedReceipt.verdict === "fail", `Stale install verdict was ${failedReceipt.verdict}`);
      requireCondition(checkKinds(failedReceipt).includes("SOURCE_INSTALL_STALE"),
        `Stale receipt omitted SOURCE_INSTALL_STALE: ${JSON.stringify(failedReceipt.checks)}`);
      const artifact = assertContainedAfterArtifact(fixture.fixtureDirectory, sessionId, failedReceipt);
      return {
        evidence: `Receipt ${failedReceipt.receiptId} failed closed with SOURCE_INSTALL_STALE`,
        details: {
          verdict: failedReceipt.verdict,
          receiptId: failedReceipt.receiptId,
          checkKinds: checkKinds(failedReceipt),
          sourceMtime,
          artifact,
          artifactContained: true,
        },
      };
    });

    await record("rebuilt_install_passed_receipt", async () => {
      await waitUntilAfter(sourceMtime);
      await installFixture(fixture, environment, ownership);
      await coldLaunch(environment, fixture);
      await waitForBridge(mcp, fixture.packageName, options.maxRetries);
      await waitForChangedText(mcp, fixture.packageName, options.maxRetries);
      passingReceipt = receiptFrom(await mcpCall(mcp, "fixthis_verify_feedback", {
        sessionId,
        itemId,
        assertions: [
          { kind: "target_present" },
          { kind: "text_present", value: changedButtonText },
        ],
      }));
      requireCondition(passingReceipt.verdict === "pass", `Reinstalled verification verdict was ${passingReceipt.verdict}: ${JSON.stringify(passingReceipt.checks)}`);
      const artifact = assertContainedAfterArtifact(fixture.fixtureDirectory, sessionId, passingReceipt);
      return {
        evidence: `Receipt ${passingReceipt.receiptId} passed target_present and text_present`,
        details: {
          verdict: passingReceipt.verdict,
          receiptId: passingReceipt.receiptId,
          assertions: passingReceipt.assertions?.map((assertion) => assertion.kind),
          checkKinds: checkKinds(passingReceipt),
          artifact,
          artifactContained: true,
        },
      };
    });

    await record("restart_replayed_receipts", async () => {
      const firstPid = mcp.pid;
      requireCondition(await ownership.stopMcp(mcp), `Could not verify owned MCP PID ${firstPid} stopped`);
      mcp = null;
      mcp = await startMcp(mcpBinary, fixture, environment, "verification-receipt-replay", ownership);
      const replayed = parseJsonToolResult(await mcpCall(mcp, "fixthis_read_feedback", {
        sessionId,
        includeAll: true,
      }), "fixthis_read_feedback replay");
      const receipts = replayed.verificationReceipts || [];
      const receiptIds = receipts.map((receipt) => receipt.receiptId);
      requireCondition(receiptIds.includes(failedReceipt.receiptId) && receiptIds.includes(passingReceipt.receiptId),
        `Restart replay receipt mismatch: ${JSON.stringify(receiptIds)}`);
      const replayedPass = receipts.find((receipt) => receipt.receiptId === passingReceipt.receiptId);
      const afterArtifact = assertContainedAfterArtifact(fixture.fixtureDirectory, sessionId, replayedPass);
      const reopened = parseJsonToolResult(await mcpCall(mcp, "fixthis_open_feedback_console", {
        sessionId,
        packageName: fixture.packageName,
      }), "fixthis_open_feedback_console replay");
      consoleUrl = reopened.consoleUrl;
      return {
        evidence: `Fresh MCP child replayed both receipts and ${afterArtifact}`,
        details: {
          receiptIds,
          afterArtifact,
          artifactContained: true,
          artifactSurvivedRestart: true,
          stoppedMcpPid: firstPid,
          replayMcpPid: mcp.pid,
        },
      };
    });

    await record("failed_receipt_rejected", async () => {
      latestFailedReceipt = receiptFrom(await mcpCall(mcp, "fixthis_verify_feedback", {
        sessionId,
        itemId,
        assertions: [
          { kind: "target_present" },
          { kind: "text_present", value: "This text must never exist" },
        ],
      }));
      requireCondition(latestFailedReceipt.verdict === "fail",
        `Latest negative-control receipt was ${latestFailedReceipt.verdict}`);
      requireCondition(checkKinds(latestFailedReceipt).includes("ASSERTION_FAILED"),
        `Latest negative-control receipt omitted ASSERTION_FAILED: ${JSON.stringify(latestFailedReceipt.checks)}`);
      let rejection = null;
      try {
        await mcpCall(mcp, "fixthis_resolve_feedback", {
          sessionId,
          itemId,
          status: "resolved",
          summary: "This failed receipt must not resolve feedback",
          verificationReceiptId: latestFailedReceipt.receiptId,
        });
      } catch (error) {
        rejection = error;
      }
      requireCondition(rejection?.message.includes("VERIFICATION_RECEIPT_FAILED:"),
        `Failed receipt resolution did not return VERIFICATION_RECEIPT_FAILED: ${rejection?.message || "accepted"}`);
      return {
        evidence: `Resolution rejected latest FAIL ${latestFailedReceipt.receiptId} with VERIFICATION_RECEIPT_FAILED`,
        details: {
          receiptId: latestFailedReceipt.receiptId,
          verdict: latestFailedReceipt.verdict,
          checkKinds: checkKinds(latestFailedReceipt),
          prefix: "VERIFICATION_RECEIPT_FAILED:",
        },
      };
    });

    await record("passing_receipt_resolved", async () => {
      passingReceipt = receiptFrom(await mcpCall(mcp, "fixthis_verify_feedback", {
        sessionId,
        itemId,
        assertions: [
          { kind: "target_present" },
          { kind: "text_present", value: changedButtonText },
        ],
      }));
      requireCondition(passingReceipt.verdict === "pass",
        `Final latest verification receipt was ${passingReceipt.verdict}: ${JSON.stringify(passingReceipt.checks)}`);
      const resolved = parseJsonToolResult(await mcpCall(mcp, "fixthis_resolve_feedback", {
        sessionId,
        itemId,
        status: "resolved",
        summary: "Verification receipt strict smoke passed",
        verificationReceiptId: passingReceipt.receiptId,
      }), "fixthis_resolve_feedback pass");
      requireCondition(resolved.status === "resolved", `Passing receipt did not resolve item: ${resolved.status}`);
      requireCondition(resolved.resolutionVerificationReceiptId === passingReceipt.receiptId,
        `Resolution link mismatch: ${resolved.resolutionVerificationReceiptId}`);
      const reread = parseJsonToolResult(await mcpCall(mcp, "fixthis_read_feedback", {
        sessionId,
        itemId,
        includeAll: true,
      }), "fixthis_read_feedback resolved");
      const persisted = (reread.items || []).find((item) => item.itemId === itemId);
      requireCondition(persisted?.status === "resolved" && persisted?.resolutionVerificationReceiptId === passingReceipt.receiptId,
        `Atomic persisted resolution link missing: ${JSON.stringify(persisted)}`);
      return {
        evidence: `Resolved ${itemId} atomically with latest PASS ${passingReceipt.receiptId}`,
        details: { status: resolved.status, resolutionVerificationReceiptId: resolved.resolutionVerificationReceiptId },
      };
    });

    await record("console_rendered_verified", async () => {
      const evidence = await proveConsole({
        consoleUrl,
        sessionId,
        itemId,
        receiptId: passingReceipt.receiptId,
        headed: options.headed,
      });
      return {
        evidence: `Headless console rendered exact verified badge and ${passingReceipt.receiptId}`,
        details: evidence,
      };
    });
  } catch (error) {
    failures.push(error.message);
  } finally {
    if (mcp) {
      const pid = mcp.pid;
      if (!await ownership.stopMcp(mcp)) failures.push(`Unconfirmed owned MCP PID ${pid} stop`);
    }
    await ownership.cleanup("normal");
  }
  for (const name of requiredVerificationReceiptSteps) {
    if (!steps.some((step) => step.name === name)) {
      steps.push({ name, status: options.strict ? "FAIL" : "DEFERRED", evidence: failures.at(-1) || "Product path did not reach this checkpoint" });
    }
  }
  steps.sort((left, right) => requiredVerificationReceiptSteps.indexOf(left.name) - requiredVerificationReceiptSteps.indexOf(right.name));
  return { fixture, steps, failures, cleanup, sessionId };
}

function sdkFromEnvironment(environment) {
  return environment.envPatch?.ANDROID_HOME || environment.envPatch?.ANDROID_SDK_ROOT || null;
}

function readyEnvironment(base, ownedEmulator = null) {
  return {
    ...base,
    env: { ...process.env, ...(base.envPatch || {}) },
    ownedEmulator,
  };
}

async function resolveConnectedEnvironment(options, ownership) {
  const requestedEnv = options.device ? { ...process.env, ANDROID_SERIAL: options.device } : process.env;
  let environment = resolveAndroidEnvironment({ env: requestedEnv });
  if (environment.ready) return readyEnvironment(environment);
  if (options.device) return readyEnvironment(environment);
  const sdk = sdkFromEnvironment(environment);
  const emulator = sdk ? join(sdk, "emulator/emulator") : null;
  if (!emulator || !existsSync(emulator)) return readyEnvironment(environment);
  const avds = spawnSync(emulator, ["-list-avds"], { encoding: "utf8", env: requestedEnv })
    .stdout?.split(/\r?\n/).map((value) => value.trim()).filter(Boolean) || [];
  if (avds.length === 0) return readyEnvironment(environment);
  process.stdout.write(`[verification-receipt] No ready device; starting owned AVD ${avds[0]}\n`);
  const child = spawn(emulator, ["-avd", avds[0], "-no-snapshot-save", "-no-audio", "-no-boot-anim"], {
    cwd: repoRoot,
    env: requestedEnv,
    stdio: ["ignore", "ignore", "ignore"],
  });
  const ownedEmulator = { child, pid: child.pid, stopped: false };
  ownership.summary.emulatorPid = child.pid;
  ownership.summary.ownedEmulatorStopped = false;
  ownership.addCleanupTask("Owned emulator", async () => {
    const stopped = await stopOwnedEmulator(ownedEmulator);
    if (!stopped) throw new Error(`Unconfirmed owned emulator PID ${child.pid} stop`);
    return { emulatorPid: child.pid, ownedEmulatorStopped: true };
  }, 30);
  for (let attempt = 0; attempt < 90; attempt += 1) {
    await delay(2_000);
    environment = resolveAndroidEnvironment({ env: requestedEnv });
    if (environment.ready) {
      const env = readyEnvironment(environment, ownedEmulator);
      const boot = spawnSync("adb", adbArgs(env, "shell", "getprop", "sys.boot_completed"), {
        encoding: "utf8",
        env: env.env,
      });
      if (String(boot.stdout || "").trim() === "1") return env;
    }
    if (child.exitCode != null) break;
  }
  return readyEnvironment(environment, ownedEmulator);
}

export async function runVerificationReceiptSmoke({
  options = parseArgs(),
  environment = null,
  executeProductPath = executeConnectedProductPath,
  ownership = createOwnedResourceController(),
  runPaths = createRunPaths(),
} = {}) {
  const startedAt = new Date().toISOString();
  const activeEnvironment = environment
    ? { ...environment, env: environment.env || { ...process.env, ...(environment.envPatch || {}) } }
    : await resolveConnectedEnvironment(options, ownership);
  if (!activeEnvironment.ready) {
    await ownership.cleanup("environment-unavailable");
    const status = options.strict ? "FAIL" : "DEFERRED";
    const steps = requiredVerificationReceiptSteps.map((name) => ({
      name,
      status,
      evidence: activeEnvironment.reason || "Android device unavailable",
    }));
    return buildReport({
      strict: options.strict,
      deviceSerial: activeEnvironment.device || null,
      steps,
      startedAt,
      finishedAt: new Date().toISOString(),
      failures: [activeEnvironment.reason || "Android device unavailable"],
      cleanup: ownership.summary,
    });
  }
  let result;
  try {
    result = await executeProductPath({ options, environment: activeEnvironment, ownership, runPaths });
  } finally {
    await ownership.cleanup("normal");
  }
  return buildReport({
    strict: options.strict,
    deviceSerial: activeEnvironment.device,
    packageName: result.fixture?.packageName || null,
    sessionId: result.sessionId || null,
    steps: result.steps,
    startedAt,
    finishedAt: new Date().toISOString(),
    failures: result.failures || [],
    cleanup: ownership.summary,
  });
}

export async function main(argv = process.argv.slice(2), io = { stdout: process.stdout, stderr: process.stderr }) {
  let options;
  try {
    options = parseArgs(argv);
  } catch (error) {
    io.stderr.write(`${error.message}\n`);
    return 2;
  }
  if (options.help) {
    io.stdout.write("Usage: node scripts/verification-receipt-smoke.mjs [--strict] [--headed] [--device <serial>] [--report-dir <dir>] [--max-retries <count>]\n");
    return 0;
  }
  const ownership = createOwnedResourceController();
  const disposeLifecycle = installLifecycleCleanup({
    cleanup: (reason) => ownership.cleanup(reason),
  });
  let report;
  try {
    report = await runVerificationReceiptSmoke({ options, ownership });
  } catch (error) {
    await ownership.cleanup("main-error");
    io.stderr.write(`${error.message}\n`);
    disposeLifecycle();
    return 1;
  }
  disposeLifecycle();
  const paths = writeReport(report, options.reportDir);
  io.stdout.write(`Verification receipt smoke: ${report.status}\nJSON: ${paths.json}\nMarkdown: ${paths.markdown}\n`);
  if (options.strict) {
    try { assertStrictReport(report); } catch (error) {
      io.stderr.write(`${error.message}\n`);
      return 1;
    }
  }
  return report.status === "FAIL" ? 1 : 0;
}

if (process.argv[1] && resolve(process.argv[1]) === scriptPath) {
  process.exitCode = await main();
}
