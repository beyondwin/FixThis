#!/usr/bin/env node
import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, isAbsolute, join, resolve, sep } from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { resolveAndroidEnvironment } from "./evidence-runner.mjs";
import { createMcpJsonRpcClient } from "./mcp-json-rpc-client.mjs";

const scriptPath = fileURLToPath(import.meta.url);
const repoRoot = resolve(dirname(scriptPath), "..");
const defaultReportDir = "build/reports/fixthis-runtime-evidence";
const packageName = "io.github.beyondwin.fixthis.sample";
const secretValue = "fixthis-runtime-secret-7f3a";
const handoffLimit = 20_000;

export function parseArgs(argv = process.argv.slice(2)) {
  const args = { strict: false, outDir: defaultReportDir };
  for (let index = 0; index < argv.length; index += 1) {
    const flag = argv[index];
    if (flag === "--strict") args.strict = true;
    else if (flag === "--out-dir" || flag === "--output-dir") {
      const value = argv[++index];
      if (!value || value.startsWith("--")) throw new Error(`${flag} requires a value`);
      args.outDir = value;
    } else if (flag === "-h" || flag === "--help") {
      throw new Error("Usage: node scripts/runtime-evidence-smoke.mjs [--strict] [--out-dir <dir>]");
    } else throw new Error(`Unknown flag: ${flag}`);
  }
  return args;
}

export function validateRuntimeEvidenceProductPath(productPath) {
  if (!productPath || productPath.tool !== "fixthis_collect_runtime_evidence") throw new Error("missing collection tool proof");
  if (!productPath.sessionId) throw new Error("missing product-path session");
  if (!Array.isArray(productPath.itemIds) || productPath.itemIds.length === 0) throw new Error("missing linked feedback item");
  if (!["complete", "partial"].includes(productPath.captureStatus)) throw new Error("runtime evidence capture did not produce a usable terminal status");
  if (!(productPath.attachmentCount > 0)) throw new Error("missing runtime evidence attachment");
  if (productPath.artifactVerified !== true) throw new Error("runtime evidence artifact is missing or outside the project root");
  if (productPath.compactHandoffBounded !== true) throw new Error("compact handoff is missing, unbounded, or contains raw secret data");
  if (productPath.replayVerified !== true) throw new Error("runtime evidence replay mismatch");
  if (productPath.autoHandoffVerified !== true) throw new Error("Save to MCP Auto handoff was not verified");
  if (productPath.linkageVerified !== true) throw new Error("runtime evidence linkage was not verified");
  if (productPath.redactionVerified !== true) throw new Error("runtime evidence redaction was not verified");
  return productPath;
}

export function buildRuntimeEvidenceReport({ strict, status, reason = null, productPath = null, failures = [] }) {
  return { generatedAt: new Date().toISOString(), strict, status, reason, productPath, failures };
}

export function renderRuntimeEvidenceMarkdown(report) {
  const path = report.productPath;
  const lines = [
    "# FixThis Runtime Evidence Report",
    "",
    `Status: ${report.status}`,
    `Strict: ${report.strict}`,
    "",
    "| Tool | Session | Items | Capture | Attachments | Artifact | Handoff | Replay |",
    "| --- | --- | --- | --- | ---: | --- | --- | --- |",
  ];
  lines.push(path
    ? `| ${path.tool} | ${path.sessionId} | ${path.itemIds.join(",")} | ${path.captureStatus} | ${path.attachmentCount} | ${path.artifactVerified} | ${path.compactHandoffBounded} | ${path.replayVerified} |`
    : "| - | - | - | - | 0 | false | false | false |");
  if (report.reason) lines.push("", `Reason: ${report.reason}`);
  for (const failure of report.failures || []) lines.push(`- Failure: ${String(failure).replace(/\s+/g, " ").slice(0, 240)}`);
  return `${lines.join("\n")}\n`;
}

export function writeRuntimeEvidenceReport(report, outDir = defaultReportDir) {
  mkdirSync(outDir, { recursive: true });
  const json = join(outDir, "report.json");
  const markdown = join(outDir, "report.md");
  writeFileSync(json, `${JSON.stringify(report, null, 2)}\n`);
  writeFileSync(markdown, renderRuntimeEvidenceMarkdown(report));
  return { json, markdown };
}

function run(command, args, environment, options = {}) {
  return spawnSync(command, args, {
    cwd: options.cwd || repoRoot,
    encoding: "utf8",
    stdio: options.stdio || "pipe",
    env: { ...process.env, ...(environment?.envPatch || {}) },
  });
}

function ensureMcpDistribution(environment) {
  const result = run("./gradlew", [":fixthis-mcp:installDist", "--no-daemon"], environment, { stdio: "inherit" });
  if (result.status !== 0) throw new Error("Could not build the MCP distribution");
  return join(repoRoot, "fixthis-mcp/build/install/fixthis-mcp/bin/fixthis-mcp");
}

async function consoleClient(consoleUrl) {
  const base = new URL(consoleUrl);
  const response = await fetch(base);
  if (!response.ok) throw new Error(`Console bootstrap failed (${response.status})`);
  const html = await response.text();
  const token = html.match(/consoleToken:\s*"([^"]+)"/)?.[1];
  if (!token) throw new Error("Console token was not present in bootstrap HTML");
  const origin = base.origin;
  return async (path, body) => {
    const next = await fetch(new URL(path, origin), {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-FixThis-Console-Token": token, Origin: origin },
      body: JSON.stringify(body),
    });
    const text = await next.text();
    if (!next.ok) throw new Error(`${path} failed (${next.status}): ${text}`);
    return JSON.parse(text);
  };
}

function requireProof(condition, message) {
  if (!condition) throw new Error(message);
}

function sortedUnique(values = []) {
  return [...new Set(values)].sort();
}

function sameIds(actual, expected) {
  return JSON.stringify(sortedUnique(actual)) === JSON.stringify(sortedUnique(expected));
}

function evidenceProjection(session, itemId, result, projectDir, expectedTrigger, requireRedaction = false) {
  requireProof(result?.attempted === true, `${expectedTrigger} collection was not attempted`);
  requireProof(["complete", "partial"].includes(result.status), `${expectedTrigger} collection did not produce a usable terminal status`);
  requireProof(Boolean(result.captureId), `${expectedTrigger} collection is missing capture id`);
  const ids = sortedUnique(result.attachmentIds);
  requireProof(ids.length > 0, `${expectedTrigger} collection is missing attachments`);
  requireProof(sameIds(result.linkedItemIds, [itemId]), `${expectedTrigger} linked item mismatch`);
  const targetItem = session.items?.find((entry) => entry.itemId === itemId);
  requireProof(targetItem, "target feedback item missing from session");
  const root = realpathSync(resolve(projectDir, ".fixthis/runtime-evidence"));
  let redactionMarkerFound = false;
  const attachments = ids.map((id) => {
    const attachment = session.runtimeEvidence?.find((entry) => entry.evidenceId === id);
    requireProof(attachment, `${expectedTrigger} attachment missing from session: ${id}`);
    requireProof(targetItem.runtimeEvidenceIds?.includes(id), `${expectedTrigger} attachment is not linked to target item: ${id}`);
    requireProof(attachment.trigger === expectedTrigger, `${expectedTrigger} attachment has wrong trigger: ${id}`);
    requireProof(attachment.captureId === result.captureId, `${expectedTrigger} attachment has wrong capture id: ${id}`);
    requireProof(Boolean(attachment.artifactPath), `${expectedTrigger} attachment is missing artifact path: ${id}`);
    const candidate = isAbsolute(attachment.artifactPath) ? attachment.artifactPath : resolve(projectDir, attachment.artifactPath);
    requireProof(existsSync(candidate), `${expectedTrigger} artifact missing or outside evidence root: ${id}`);
    const actual = realpathSync(candidate);
    requireProof(actual.startsWith(`${root}${sep}`) && statSync(actual).isFile(), `${expectedTrigger} artifact missing or outside evidence root: ${id}`);
    const body = readFileSync(actual, "utf8");
    requireProof(!body.includes(secretValue), `${expectedTrigger} artifact contains raw secret: ${id}`);
    if (body.includes("[REDACTED]") && attachment.warnings?.includes("redaction_applied")) redactionMarkerFound = true;
    return {
      evidenceId: attachment.evidenceId,
      trigger: attachment.trigger,
      captureId: attachment.captureId,
      artifactPath: attachment.artifactPath,
    };
  });
  if (requireRedaction) requireProof(redactionMarkerFound, `${expectedTrigger} redaction marker was not observed`);
  return attachments.sort((left, right) => left.evidenceId.localeCompare(right.evidenceId));
}

function noSecret(value) {
  return !JSON.stringify(value).includes(secretValue);
}

export function proveRuntimeEvidenceProductPath({
  toolName,
  sessionId,
  itemId,
  projectDir,
  policy,
  collected,
  handoff,
  session,
  replayed,
}) {
  requireProof(toolName === "fixthis_collect_runtime_evidence", "missing collection tool proof");
  requireProof(policy?.runtimeEvidencePolicy === "auto_on_handoff", "runtime evidence policy did not persist as Auto");
  requireProof(handoff?.session?.runtimeEvidencePolicy === "auto_on_handoff", "handoff session lost Auto runtime evidence policy");
  requireProof(noSecret(handoff), "raw secret entered handoff JSON or Markdown");
  requireProof(noSecret(session), "raw secret entered session JSON");
  requireProof(noSecret(replayed), "raw secret entered replayed session JSON");

  const manualProjection = evidenceProjection(session, itemId, collected, projectDir, "mcp_manual");
  const auto = handoff.runtimeEvidence;
  requireProof(auto?.attempted === true, "Auto handoff did not attempt runtime evidence collection");
  requireProof(!auto.skippedReason, "Auto handoff runtime evidence was skipped");
  const autoProjection = evidenceProjection(session, itemId, auto, projectDir, "handoff_auto", true);
  const handoffProjection = evidenceProjection(handoff.session, itemId, auto, projectDir, "handoff_auto", true);
  requireProof(JSON.stringify(autoProjection) === JSON.stringify(handoffProjection), "handoff response attachment mismatch");

  const compactHandoffBounded = handoff.prompt.length <= handoffLimit &&
    handoff.prompt.includes("runtimeEvidenceAttempt:") && handoff.prompt.includes("attempted=true") && noSecret(handoff.prompt);
  requireProof(compactHandoffBounded, "compact handoff is missing, unbounded, or contains raw secret data");

  const relevantIds = sortedUnique([...collected.attachmentIds, ...auto.attachmentIds]);
  const replayItem = replayed.items?.find((entry) => entry.itemId === itemId);
  requireProof(sameIds(replayItem?.runtimeEvidenceIds, relevantIds), "runtime evidence item linkage replay mismatch");
  const replayProjection = relevantIds.map((id) => {
    const attachment = replayed.runtimeEvidence?.find((entry) => entry.evidenceId === id);
    requireProof(attachment, `runtime evidence replay attachment missing: ${id}`);
    return {
      evidenceId: attachment.evidenceId,
      trigger: attachment.trigger,
      captureId: attachment.captureId,
      artifactPath: attachment.artifactPath,
    };
  }).sort((left, right) => left.evidenceId.localeCompare(right.evidenceId));
  const expectedReplay = [...manualProjection, ...autoProjection].sort((left, right) => left.evidenceId.localeCompare(right.evidenceId));
  const replayVerified = JSON.stringify(replayProjection) === JSON.stringify(expectedReplay);
  requireProof(replayVerified, "runtime evidence replay mismatch");

  return validateRuntimeEvidenceProductPath({
    tool: toolName,
    sessionId,
    itemIds: [itemId],
    captureStatus: auto.status,
    attachmentCount: auto.attachmentIds.length,
    artifactVerified: true,
    compactHandoffBounded,
    replayVerified,
    autoHandoffVerified: true,
    linkageVerified: true,
    redactionVerified: true,
  });
}

export async function runRuntimeEvidenceProductPath({ environment, projectDir, mcpBin = ensureMcpDistribution(environment) }) {
  const env = { ...process.env, ...(environment.envPatch || {}) };
  let mcp;
  try {
    mcp = await createMcpJsonRpcClient({
      command: mcpBin,
      args: ["--project-dir", projectDir, "--package", packageName],
      cwd: repoRoot,
      env,
      clientInfo: { name: "runtime-evidence-smoke", version: "0" },
    });
    const opened = await mcp.callTool("fixthis_open_feedback_console", { packageName, newSession: true });
    const sessionId = opened.sessionId;
    const captured = await mcp.callTool("fixthis_capture_screen", { sessionId });
    const post = await consoleClient(opened.consoleUrl);
    const item = await post("/api/items", {
      sessionId,
      screenId: captured.screen.screenId,
      comment: "Runtime evidence product-path proof",
      targetType: "area",
      bounds: { left: 0, top: 0, right: 32, bottom: 32 },
    });
    const policy = await post(`/api/sessions/${encodeURIComponent(sessionId)}/runtime-evidence-policy`, { policy: "auto_on_handoff" });
    const collected = await mcp.callTool("fixthis_collect_runtime_evidence", {
      sessionId,
      itemId: item.itemId,
      preset: "baseline",
    }, 30_000);
    const logged = run(
      "adb",
      ["-s", environment.device, "shell", "log", "-b", "crash", "-t", "FixThisRuntimeSmoke", `Authorization: Bearer ${secretValue}`],
      environment,
    );
    if (logged.status !== 0) throw new Error(`Could not inject redaction sentinel: ${logged.stderr || logged.stdout}`);
    const handoff = await post("/api/agent-handoffs", { sessionId, itemIds: [item.itemId] });
    const session = await mcp.callTool("fixthis_read_feedback", { sessionId, includeAll: true });
    await mcp.close();
    mcp = null;

    const replay = await createMcpJsonRpcClient({
      command: mcpBin,
      args: ["--project-dir", projectDir, "--package", packageName],
      cwd: repoRoot,
      env,
      clientInfo: { name: "runtime-evidence-replay-smoke", version: "0" },
    });
    mcp = replay;
    const replayed = await replay.callTool("fixthis_read_feedback", { sessionId, includeAll: true });
    return proveRuntimeEvidenceProductPath({
      toolName: "fixthis_collect_runtime_evidence",
      sessionId,
      itemId: item.itemId,
      projectDir,
      policy,
      collected,
      handoff,
      session,
      replayed,
    });
  } finally {
    await mcp?.close?.();
  }
}

export async function createRuntimeEvidenceSmokeReport({
  args = parseArgs(),
  environment = resolveAndroidEnvironment(),
  runProductPath = runRuntimeEvidenceProductPath,
} = {}) {
  if (!environment.ready) {
    const status = args.strict ? "fail" : "deferred";
    return buildRuntimeEvidenceReport({ strict: args.strict, status, reason: environment.reason || "Android device unavailable" });
  }
  const projectDir = mkdtempSync(join(tmpdir(), "fixthis-runtime-product-path-"));
  try {
    const productPath = await runProductPath({ environment, projectDir });
    return buildRuntimeEvidenceReport({ strict: args.strict, status: "pass", productPath });
  } catch (error) {
    return buildRuntimeEvidenceReport({ strict: args.strict, status: "fail", reason: error.message, failures: [error.message] });
  } finally {
    rmSync(projectDir, { recursive: true, force: true });
  }
}

export async function main(argv = process.argv.slice(2), io = { stdout: process.stdout, stderr: process.stderr }) {
  try {
    const args = parseArgs(argv);
    const report = await createRuntimeEvidenceSmokeReport({ args });
    const paths = writeRuntimeEvidenceReport(report, args.outDir);
    io.stdout.write(`Runtime evidence smoke: ${report.status}\nJSON: ${paths.json}\nMarkdown: ${paths.markdown}\n`);
    return report.status === "fail" ? 1 : 0;
  } catch (error) {
    io.stderr.write(`${error.message}\n`);
    return 2;
  }
}

if (process.argv[1] && resolve(process.argv[1]) === scriptPath) {
  main().then((code) => { process.exitCode = code; });
}
