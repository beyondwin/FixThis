#!/usr/bin/env node
import { spawnSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { resolveAndroidEnvironment } from "./evidence-runner.mjs";

const scriptPath = fileURLToPath(import.meta.url);
const repoRoot = resolve(dirname(scriptPath), "..");
const defaultReportDir = "build/reports/fixthis-runtime-evidence";
const defaultAndroidReason = "Android SDK or ready emulator is unavailable.";
const summaryLimit = 140;
const strictRuntimeItemId = "strict-runtime";

export function normalizeRuntimeEvidenceStatus({
  strict = false,
  androidReady = false,
  evidenceCount = 0,
  reason = defaultAndroidReason,
} = {}) {
  if (!androidReady) return { status: strict ? "fail" : "deferred", reason };
  if (strict && evidenceCount === 0) {
    return {
      status: "fail",
      reason: "Strict runtime evidence requires at least one captured evidence row.",
    };
  }
  return { status: "pass", reason: null };
}

export function selectRuntimeEvidenceCommand(type) {
  const commands = {
    logcat_window: { label: "Logcat window", command: "adb logcat -d --pid <pid>" },
    frame_summary: { label: "Frame summary", command: "adb shell dumpsys gfxinfo <package>" },
    memory_summary: { label: "Memory summary", command: "adb shell dumpsys meminfo <package>" },
    trace_artifact: { label: "Trace artifact", command: "perfetto or simpleperf capture script" },
  };
  if (!commands[type]) throw new Error(`Unsupported runtime evidence type: ${type}`);
  return commands[type];
}

export function buildRuntimeEvidenceReport({
  strict = false,
  status = "pass",
  reason = null,
  evidence = [],
} = {}) {
  return {
    generatedAt: new Date().toISOString(),
    strict,
    status,
    reason,
    evidence: evidence.map((entry) => ({
      itemId: entry.itemId || "-",
      type: entry.type,
      summary: entry.summary || "",
      artifactPath: entry.artifactPath || null,
      command: entry.command || selectRuntimeEvidenceCommand(entry.type).command,
    })),
  };
}

function boundedCell(value) {
  const text = String(value || "-").replace(/\s+/g, " ").trim();
  if (text.length <= summaryLimit) return text;
  return `${text.slice(0, summaryLimit - 1)}…`;
}

export function renderRuntimeEvidenceMarkdown(report) {
  const lines = [
    "# FixThis Runtime Evidence Report",
    "",
    `Status: ${report.status}`,
    `Strict: ${report.strict}`,
    "",
    "| Item | Type | Summary | Artifact |",
    "| --- | --- | --- | --- |",
  ];
  for (const entry of report.evidence || []) {
    lines.push(
      `| ${entry.itemId || "-"} | ${entry.type} | ${boundedCell(entry.summary)} | \`${entry.artifactPath || "-"}\` |`,
    );
  }
  if (!report.evidence?.length) lines.push("| - | - | - | `-` |");
  if (report.reason) lines.push("", `Reason: ${report.reason}`);
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

function strictArtifactPath(now = new Date()) {
  const timestamp = now.toISOString().replace(/[:.]/g, "-");
  return `.fixthis/runtime-evidence/${timestamp}-logcat.txt`;
}

function logcatSummary(stdout = "", device = null) {
  const lines = stdout.split(/\r?\n/).filter((line) => line.trim()).length;
  return `Captured ${lines} logcat lines from ${device || "ready Android device"}.`;
}

export function captureStrictRuntimeEvidence({
  androidEnvironment,
  now = () => new Date(),
  spawn = spawnSync,
  root = repoRoot,
} = {}) {
  const artifactPath = strictArtifactPath(now());
  const fullPath = resolve(root, artifactPath);
  const adbArgs = [];
  if (androidEnvironment?.device) adbArgs.push("-s", androidEnvironment.device);
  adbArgs.push("logcat", "-d", "-t", "80");
  const command = ["adb", ...adbArgs].join(" ");
  const result = spawn("adb", adbArgs, {
    cwd: root,
    encoding: "utf8",
    env: { ...process.env, ...(androidEnvironment?.envPatch || {}) },
  });
  if (result.status !== 0) return null;
  mkdirSync(dirname(fullPath), { recursive: true });
  writeFileSync(fullPath, result.stdout || "");
  return {
    itemId: strictRuntimeItemId,
    type: "logcat_window",
    summary: logcatSummary(result.stdout || "", androidEnvironment?.device),
    artifactPath,
    command,
  };
}

function requireValue(argv, index, flag) {
  const value = argv[index + 1];
  if (!value || value.startsWith("--")) throw new Error(`${flag} requires a value`);
  return value;
}

export function parseArgs(argv = process.argv.slice(2)) {
  const args = {
    strict: false,
    outDir: defaultReportDir,
    evidence: [],
  };
  let pending = null;
  const pushPending = () => {
    if (!pending) return;
    if (!pending.itemId) throw new Error("--item is required for runtime evidence rows");
    if (!pending.type) throw new Error("--type is required for runtime evidence rows");
    selectRuntimeEvidenceCommand(pending.type);
    args.evidence.push({
      itemId: pending.itemId,
      type: pending.type,
      summary: pending.summary || "",
      artifactPath: pending.artifactPath || null,
    });
    pending = null;
  };

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--strict") {
      args.strict = true;
    } else if (arg === "--out-dir" || arg === "--output-dir") {
      args.outDir = requireValue(argv, i, arg);
      i += 1;
    } else if (arg === "--item") {
      pushPending();
      pending = { itemId: requireValue(argv, i, arg) };
      i += 1;
    } else if (arg === "--type") {
      if (!pending) pending = {};
      pending.type = requireValue(argv, i, arg);
      i += 1;
    } else if (arg === "--summary") {
      if (!pending) pending = {};
      pending.summary = requireValue(argv, i, arg);
      i += 1;
    } else if (arg === "--artifact" || arg === "--artifact-path") {
      if (!pending) pending = {};
      pending.artifactPath = requireValue(argv, i, arg);
      i += 1;
    } else if (arg === "-h" || arg === "--help") {
      throw new Error(
        "Usage: node scripts/runtime-evidence-smoke.mjs [--strict] [--out-dir <dir>] [--item <id> --type <type> --summary <text> --artifact <path>]",
      );
    } else {
      throw new Error(`Unknown flag: ${arg}`);
    }
  }
  pushPending();
  return args;
}

export function createRuntimeEvidenceSmokeReport({
  args = parseArgs(),
  androidEnvironment = resolveAndroidEnvironment(),
  captureRuntimeEvidence = captureStrictRuntimeEvidence,
} = {}) {
  const evidence = args.evidence.slice();
  if (args.strict && androidEnvironment.ready && evidence.length === 0) {
    const captured = captureRuntimeEvidence({ androidEnvironment });
    if (captured) evidence.push(captured);
  }
  const status = normalizeRuntimeEvidenceStatus({
    strict: args.strict,
    androidReady: androidEnvironment.ready,
    evidenceCount: evidence.length,
    reason: androidEnvironment.reason || defaultAndroidReason,
  });
  return buildRuntimeEvidenceReport({
    strict: args.strict,
    status: status.status,
    reason: status.reason,
    evidence,
  });
}

export function main(argv = process.argv.slice(2), io = { stdout: process.stdout, stderr: process.stderr }) {
  try {
    const args = parseArgs(argv);
    const report = createRuntimeEvidenceSmokeReport({ args });
    const paths = writeRuntimeEvidenceReport(report, args.outDir);
    io.stdout.write(`Runtime evidence smoke: ${report.status}\n`);
    io.stdout.write(`JSON: ${paths.json}\n`);
    io.stdout.write(`Markdown: ${paths.markdown}\n`);
    if (report.status === "fail") return 1;
    return 0;
  } catch (error) {
    io.stderr.write(`${error.message}\n`);
    return 2;
  }
}

if (process.argv[1] && resolve(process.argv[1]) === scriptPath) {
  process.exitCode = main();
}
