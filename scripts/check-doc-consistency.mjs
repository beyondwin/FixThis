// Lightweight DX-docs drift detector.
// Run with: node scripts/check-doc-consistency.mjs
// Exit 0 on success; exit 1 with rule name on failure.
// No dependencies; runs on Node >=18.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import GithubSlugger from "github-slugger";

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

// Rule 6: every *.md#anchor link in the DX docs resolves to an existing heading.
const dxDocs = ["README.md", "AGENTS.md", "CONTRIBUTING.md", "CLAUDE.md"];
const docFiles = new Map();
for (const f of dxDocs) {
  try { docFiles.set(f, read(f)); } catch {}
}
const anchorLink = /\[[^\]]+\]\(([^)\s]+\.md)#([^)\s]+)\)/g;
for (const [src, content] of docFiles) {
  for (const m of content.matchAll(anchorLink)) {
    const [, targetPath, anchor] = m;
    const targetKey = path.normalize(targetPath);
    const target = docFiles.get(targetKey) ?? (fs.existsSync(path.join(repoRoot, targetPath)) ? read(targetPath) : null);
    if (!target) continue; // skip external/missing files; R3 covers cross-file presence
    const slugger = new GithubSlugger();
    const headings = [...target.matchAll(/^#+\s+(.+)$/gm)].map((mm) => slugger.slug(mm[1].trim()));
    check(
      `R6.${src}->${targetPath}#${anchor}`,
      headings.includes(anchor),
      `${src} links to ${targetPath}#${anchor} but no matching heading exists.`,
    );
  }
}

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
  /docs\/superpowers[\s\S]{0,240}historical|historical[\s\S]{0,240}docs\/superpowers/i.test(`${agents}\n${docsIndex}`),
  "AGENTS.md or docs/index.md must describe docs/superpowers as historical planning context.",
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

if (failures.length > 0) {
  console.error("\n" + failures.join("\n"));
  process.exit(1);
}
console.log("\nAll doc-consistency rules pass.");
