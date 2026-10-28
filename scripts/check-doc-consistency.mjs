// Lightweight DX-docs drift detector.
// Run with: node scripts/check-doc-consistency.mjs
// Exit 0 on success; exit 1 with rule name on failure.
// Uses the repository's Node dependencies and Git file inventory.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { validateDocLinks } from "./check-doc-links.mjs";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

const read = (rel) => fs.readFileSync(path.join(repoRoot, rel), "utf8");

const failures = [];

function check(rule, condition, detail) {
  if (!condition) failures.push(`FAIL ${rule}: ${detail}`);
  else console.log(`PASS ${rule}`);
}

// Rule 1: every `console:*` script in package.json appears in CONTRIBUTING.md.
const pkg = JSON.parse(read("package.json"));
const consoleScripts = Object.keys(pkg.scripts ?? {}).filter((k) => k.startsWith("console:"));
const contributing = read("CONTRIBUTING.md");
for (const script of consoleScripts) {
  check(
    `R1.${script}`,
    contributing.includes(script),
    `package.json defines '${script}' but CONTRIBUTING.md does not reference it.`,
  );
}

// Rule 2: every `npm run console:*` referenced in CONTRIBUTING.md exists.
const referenced = [...contributing.matchAll(/npm run (console:[\w:]+)/g)].map((m) => m[1]);
for (const script of referenced) {
  check(
    `R2.${script}`,
    consoleScripts.includes(script),
    `CONTRIBUTING.md references 'npm run ${script}' but package.json does not define it.`,
  );
}

// Rule 3: README links to AGENTS.md and vice versa.
const readme = read("README.md");
const agents = read("AGENTS.md");
check("R3.readme->agents", readme.includes("AGENTS.md"), "README.md does not link to AGENTS.md.");
check("R3.agents->readme", agents.includes("README.md"), "AGENTS.md does not link to README.md.");

// Rule 4: both contributor scripts are named in CONTRIBUTING.md.
check(
  "R4.restart-console",
  contributing.includes("scripts/restart-console.sh"),
  "CONTRIBUTING.md does not mention scripts/restart-console.sh.",
);
check(
  "R4.fixthis-console-dev",
  contributing.includes("scripts/fixthis-console-dev.sh"),
  "CONTRIBUTING.md does not mention scripts/fixthis-console-dev.sh.",
);

// Rule 5: package.json has engines.node defined.
check(
  "R5.engines",
  Boolean(pkg.engines && pkg.engines.node),
  "package.json missing engines.node field.",
);

// Rule 6: maintained Markdown links survive file moves and heading changes.
const links = validateDocLinks(repoRoot);
check(
  "R6.maintained-local-links",
  links.errors.length === 0,
  links.errors.join("\n"),
);

// Rule 7: the shared project map is linked from the main navigation entry points.
const docsIndex = read("docs/index.md");
const projectMap = read("docs/guides/project-map.md");
check(
  "R7.readme-project-map",
  readme.includes("docs/guides/project-map.md"),
  "README.md must link to docs/guides/project-map.md.",
);
check(
  "R7.agents-project-map",
  agents.includes("docs/guides/project-map.md"),
  "AGENTS.md must link to docs/guides/project-map.md.",
);
check(
  "R7.docs-index-project-map",
  docsIndex.includes("guides/project-map.md"),
  "docs/index.md must link to guides/project-map.md.",
);

// Rule 8: historical planning docs are labeled as context, not current contracts.
check(
  "R8.historical-planning-label",
  /historical/i.test(docsIndex) && ["specs/README.md", "plans/README.md"].every((file) => docsIndex.includes(file)),
  "docs/index.md must label the remaining specs and plans as historical context.",
);

// Rule 9: docs/index.md keeps the contract and history navigation sections.
check(
  "R9.reference-contracts-section",
  /^## Reference Contracts$/m.test(docsIndex),
  "docs/index.md must keep a 'Reference Contracts' section.",
);
check(
  "R9.historical-planning-section",
  /^## Historical Planning$/m.test(docsIndex),
  "docs/index.md must keep a 'Historical Planning' section.",
);

// Rule 10: project-map.md names every primary module.
for (const moduleName of [
  ":app",
  ":fixthis-compose-core",
  ":fixthis-compose-sidekick",
  "fixthis-gradle-plugin",
  ":fixthis-cli",
  ":fixthis-mcp",
]) {
  check(
    `R10.project-map-module-${moduleName.replace(/[^a-z0-9]+/gi, "-")}`,
    projectMap.includes(moduleName),
    `docs/guides/project-map.md must mention ${moduleName}.`,
  );
}

// Rule 11: maintained console bundle budgets match the generator's enforced limits.
const consoleBuilder = read("scripts/build-console-assets.mjs");
const consoleBudgetContracts = new Map([
  ["docs/reference/feedback-console-contract.md", read("docs/reference/feedback-console-contract.md")],
  ["CONTRIBUTING.md", read("CONTRIBUTING.md")],
  ["CLAUDE.md", read("CLAUDE.md")],
]);
function numericConstant(name) {
  const match = consoleBuilder.match(new RegExp(`const ${name} = ([\\d_]+);`));
  return match ? Number(match[1].replaceAll("_", "")) : null;
}
function documentedBudgets(content, label) {
  const inline = [...content.matchAll(new RegExp(`([\\d,]+) B ${label}`, "gi"))];
  const list = [...content.matchAll(new RegExp(`${label} budget\\s*-\\s*\`([\\d,]+) B\``, "gi"))];
  return new Set([...inline, ...list].map((match) => Number(match[1].replaceAll(",", ""))));
}
for (const [contractPath, content] of consoleBudgetContracts) {
  for (const [label, constantName] of [
    ["raw", "RAW_BUDGET_BYTES"],
    ["gzip", "GZIP_BUDGET_BYTES"],
  ]) {
    const enforced = numericConstant(constantName);
    const documented = documentedBudgets(content, label);
    const contractId = contractPath.replace(/[^a-z0-9]+/gi, "-");
    check(
      `R11.console-${contractId}-${label}-budget`,
      enforced !== null && documented.size === 1 && documented.has(enforced),
      `${contractPath} documents ${label} budgets ${[...documented].join(", ") || "none"}; generator enforces ${enforced ?? "unknown"}.`,
    );
  }
}

if (failures.length > 0) {
  console.error("\n" + failures.join("\n"));
  process.exit(1);
}
console.log("\nAll doc-consistency rules pass.");
