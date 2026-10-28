import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath, pathToFileURL } from "node:url";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const defaultGroups = JSON.parse(fs.readFileSync(path.join(repoRoot, "scripts/console-tests.json"), "utf8"));

export function selectConsoleTests(requested, { groups = defaultGroups, root = repoRoot } = {}) {
  if (requested.length === 0) {
    throw new Error("Usage: node scripts/run-console-tests.mjs <all|group> [<group> ...]\nKnown groups: " + Object.keys(groups).join(", "));
  }
  const selectedGroups = requested.flatMap((group) => group === "all" ? Object.keys(groups) : [group]);
  const files = new Set();
  for (const group of selectedGroups) {
    if (!Object.hasOwn(groups, group)) throw new Error(`Unknown group: ${group}`);
    for (const file of groups[group]) {
      if (!fs.existsSync(path.join(root, file)) || !fs.statSync(path.join(root, file)).isFile()) {
        throw new Error(`Missing console test: ${file}`);
      }
      files.add(file);
    }
  }
  return [...files];
}

function runConsoleContractGuards() {
  const detailSource = fs.readFileSync(
    path.join(repoRoot, "fixthis-mcp/src/main/console/presentation/annotationDetailView.js"),
    "utf8",
  );
  const runtimeEvidenceSource = fs.readFileSync(
    path.join(repoRoot, "fixthis-mcp/src/main/console/runtimeEvidence.js"),
    "utf8",
  );
  const verificationReceiptSource = fs.readFileSync(
    path.join(repoRoot, "fixthis-mcp/src/main/console/verificationReceipt.js"),
    "utf8",
  );
  const apiSource = fs.readFileSync(path.join(repoRoot, "fixthis-mcp/src/main/console/api.js"), "utf8");
  if (!runtimeEvidenceSource.includes('id="collectRuntimeEvidenceButton"')) {
    throw new Error("Saved annotation detail must expose the diagnostics collection control.");
  }
  if (!detailSource.includes("runtimeEvidenceSectionHtml(item)")) {
    throw new Error("Saved annotation detail must render runtime evidence state.");
  }
  if (!runtimeEvidenceSource.includes("/runtime-evidence/collect")) {
    throw new Error("Diagnostics action must call the runtime-evidence collection route.");
  }
  if (!runtimeEvidenceSource.includes("encodeURIComponent(input.itemId)")) {
    throw new Error("Diagnostics action must encode the feedback item id.");
  }
  if (!runtimeEvidenceSource.includes("showSuccess('Diagnostics captured")) {
    throw new Error("Diagnostics action must show success feedback.");
  }
  if (runtimeEvidenceSource.includes("localStorage")) {
    throw new Error("Runtime diagnostics policy must remain session-scoped.");
  }
  if (!detailSource.includes("verificationBadgeHtml(state.session, item)")) {
    throw new Error("Saved annotation rows must render verification state from the refreshed session.");
  }
  if (!detailSource.includes("verificationReceiptSectionHtml(state.session, item)")) {
    throw new Error("Saved annotation detail must render verification receipt evidence.");
  }
  if (!verificationReceiptSource.includes("/api/verification-receipts/")) {
    throw new Error("Verification receipt detail must use the contained after-screenshot route.");
  }
  if (!verificationReceiptSource.includes("encodeURIComponent(String(session.sessionId))")) {
    throw new Error("Verification receipt screenshot routes must encode the explicit session id.");
  }
  if (/setInterval|setTimeout|\bpoll(?:ing)?\b/i.test(verificationReceiptSource)) {
    throw new Error("Verification receipt rendering must remain refresh-driven without timers or polling.");
  }
  if (!apiSource.includes("X-FixThis-Console-Token")) {
    throw new Error("Console mutation requests must keep token header wiring.");
  }
}

function main() {
  let files;
  try {
    files = selectConsoleTests(process.argv.slice(2));
  } catch (error) {
    console.error(error.message);
    return 2;
  }
  try {
    runConsoleContractGuards();
  } catch (error) {
    console.error(error.message);
    return 1;
  }
  const result = spawnSync(process.execPath, ["--test", ...files], { stdio: "inherit", cwd: repoRoot });
  return result.status ?? 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  process.exitCode = main();
}
