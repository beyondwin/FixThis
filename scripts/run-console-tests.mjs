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
  const apiSource = fs.readFileSync(path.join(repoRoot, "fixthis-mcp/src/main/console/api.js"), "utf8");
  if (!detailSource.includes('id="attachEvidenceButton"')) {
    throw new Error("Saved annotation detail must expose attachEvidenceButton.");
  }
  if (!detailSource.includes("/runtime-evidence")) {
    throw new Error("Attach evidence action must call the runtime-evidence item route.");
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
