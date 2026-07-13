import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const groups = JSON.parse(fs.readFileSync(path.join(repoRoot, "scripts/console-tests.json"), "utf8"));

function runConsoleContractGuards() {
  const detailSource = fs.readFileSync(
    path.join(repoRoot, "fixthis-mcp/src/main/console/presentation/annotationDetailView.js"),
    "utf8",
  );
  const runtimeEvidenceSource = fs.readFileSync(
    path.join(repoRoot, "fixthis-mcp/src/main/console/runtimeEvidence.js"),
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
  if (!apiSource.includes("X-FixThis-Console-Token")) {
    throw new Error("Console mutation requests must keep token header wiring.");
  }
}

const requested = process.argv.slice(2);
if (requested.length === 0) {
  console.error("Usage: node scripts/run-console-tests.mjs <group> [<group> ...]");
  console.error("Known groups:", Object.keys(groups).join(", "));
  process.exit(2);
}
const files = [];
for (const g of requested) {
  if (!(g in groups)) {
    console.error(`Unknown group: ${g}`);
    process.exit(2);
  }
  files.push(...groups[g]);
}
try {
  runConsoleContractGuards();
} catch (error) {
  console.error(error.message);
  process.exit(1);
}
const result = spawnSync(process.execPath, ["--test", ...files], { stdio: "inherit", cwd: repoRoot });
process.exit(result.status ?? 1);
