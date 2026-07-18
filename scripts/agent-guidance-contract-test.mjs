import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import test from "node:test";
import {
  CONNECTED_PROOF_COMMAND,
  ROUTES,
  selectRoutes,
} from "./agent-route-registry.mjs";

const root = resolve(import.meta.dirname, "..");
const read = (path) => readFileSync(resolve(root, path), "utf8");
const lineCount = (text) => {
  const lines = text.split("\n");
  return lines.at(-1) === "" ? lines.length - 1 : lines.length;
};
const nested = [
  "fixthis-compose-core/AGENTS.md",
  "fixthis-compose-sidekick/AGENTS.md",
  "fixthis-mcp/AGENTS.md",
  "scripts/AGENTS.md",
];
const allowedNonNpmCommands = new Set([
  "./gradlew :fixthis-compose-core:test --no-daemon",
  "./gradlew :fixthis-mcp:test --tests '*session*' --no-daemon",
  "./gradlew :fixthis-mcp:test --tests '*architecture*' --no-daemon",
  "./gradlew :fixthis-mcp:test --tests '*Handoff*' --no-daemon",
  "node scripts/build-console-assets.mjs --check",
  "./gradlew :fixthis-mcp:test --tests '*console*' --no-daemon",
  "./gradlew :fixthis-cli:test --no-daemon",
  "bash scripts/check-docs-cli-surface.sh",
  "./gradlew :fixthis-compose-sidekick:testDebugUnitTest :fixthis-cli:test --no-daemon",
  "./gradlew :fixthis-gradle-plugin:test --no-daemon",
  "node scripts/check-release-readiness.mjs",
  "git diff --check",
  "node scripts/check-doc-consistency.mjs",
]);

function npmScriptsIn(text) {
  return [...text.matchAll(/npm run ([\w:-]+)/g)].map((match) => match[1]);
}

test("physical line count ignores a terminal newline", () => {
  assert.equal(lineCount("first\nsecond\n"), 2);
  assert.equal(lineCount("first\n\nsecond"), 3);
});

test("root has required phases and stays within budget", () => {
  const body = read("AGENTS.md");
  assert.ok(lineCount(body) <= 130, "root lines=" + lineCount(body));
  for (const heading of ["## Start", "## Change", "## Verify", "## Finish"]) {
    assert.match(body, new RegExp("^" + heading + "$", "m"));
  }
  assert.match(body, /docs\/guides\/project-map\.md/);
  assert.match(body, /README\.md#quick-start-sample-app-to-agent-handoff/);
  assert.match(body, /git worktree list --porcelain/);
  assert.ok(body.includes(CONNECTED_PROOF_COMMAND));
});

test("approved nested guidance exists and stays local and small", () => {
  for (const path of nested) {
    assert.ok(existsSync(resolve(root, path)), path + " must exist");
    const body = read(path);
    assert.ok(lineCount(body) <= 60, path + " lines=" + lineCount(body));
    assert.doesNotMatch(body, /^## (Start|Change|Verify|Finish)$/m);
  }
});

test("route docs sources and npm scripts resolve", () => {
  const pkg = JSON.parse(read("package.json"));
  for (const route of ROUTES) {
    for (const path of [...route.docs, ...route.sources]) {
      assert.ok(existsSync(resolve(root, path)), route.id + " missing " + path);
    }
    const commandText = route.focusedChecks.join("\n") + "\n" + route.broadGate;
    for (const script of npmScriptsIn(commandText)) {
      assert.ok(pkg.scripts[script], route.id + " missing npm script " + script);
    }
    for (const command of [...route.focusedChecks, route.broadGate]) {
      if (!command.startsWith("npm run ")) {
        assert.ok(
          allowedNonNpmCommands.has(command),
          route.id + " has unapproved command " + command,
        );
      }
    }
  }
});

test("guidance npm commands exist in package scripts", () => {
  const pkg = JSON.parse(read("package.json"));
  const bodies = [read("AGENTS.md"), ...nested.map(read)];
  for (const script of npmScriptsIn(bodies.join("\n"))) {
    assert.ok(pkg.scripts[script], "guidance references missing " + script);
  }
});

test("high-risk routes retain canonical invariants", () => {
  const byId = Object.fromEntries(
    ROUTES.map((route) => [route.id, route]),
  );
  assert.equal(byId["android-runtime"].connectedProof, true);
  assert.equal(byId.release.connectedProof, true);
  assert.ok(
    byId.console.focusedChecks.includes(
      "node scripts/build-console-assets.mjs --check",
    ),
  );
  assert.ok(byId.handoff.docs.includes("docs/reference/output-schema.md"));
  assert.ok(byId.release.focusedChecks.includes("npm run release:reality"));
  assert.equal(byId["release-docs"].connectedProof, false);
  assert.deepEqual(
    selectRoutes({
      changedFiles: ["AGENTS.md"],
    }).map((route) => route.id),
    ["agent-guidance"],
  );
  assert.deepEqual(
    selectRoutes({
      changedFiles: ["fixthis-compose-core/AGENTS.md"],
    }).map((route) => route.id),
    ["agent-guidance"],
  );
  assert.deepEqual(
    selectRoutes({
      changedFiles: ["README.md"],
    }).map((route) => route.id),
    ["docs"],
  );
});

const repoSkills = [
  ".agents/skills/fixthis-repository-change/SKILL.md",
  ".agents/skills/fixthis-release-maintenance/SKILL.md",
];
const codexWriteVerb = "(?:write|change|edit|modify|update|create)(?:d|s|ing)?";
const codexPath =
  "(?:~\\/\\.codex(?:\\/|\\b)|(?:project[- ]local|global)\\s+\\.codex(?:\\/|\\b)|\\.codex(?:\\/|\\b))";
const codexWriteInstruction = new RegExp(
  "(?:" + codexWriteVerb + "[^\\n]{0,160}" + codexPath + "|" +
    codexPath + "[^\\n]{0,160}" + codexWriteVerb + ")",
  "i",
);
const externalAppBootstrapInstruction =
  /(?:\bfixthis\s+(?:install-agent|init|doctor)\b|\bscripts\/bootstrap-mcp\.sh\b|\b(?:install|bootstrap|initialize|set up)(?:d|s|ing)?\b[^\n]{0,120}\b(?:FixThis|external(?:\s+Android)?\s+app)\b[^\n]{0,120}\b(?:FixThis|external(?:\s+Android)?\s+app)\b)/i;

function actionableSkillText(body) {
  return body
    .split(/(?<=[.!?])\s+|\n/)
    .filter(
      (sentence) =>
        !/^\s*(?:never|do not|don't)\s+/i.test(sentence),
    )
    .join("\n");
}

function assertMaintainerSkillContract(body) {
  const actionable = actionableSkillText(body);
  assert.match(body, /FixThis source checkout/i);
  assert.doesNotMatch(actionable, codexWriteInstruction);
  assert.doesNotMatch(actionable, externalAppBootstrapInstruction);
}

test("repo skills expose metadata and canonical routing", () => {
  for (const path of repoSkills) {
    const body = read(path);
    assert.match(
      body,
      /^---\nname: [a-z0-9-]+\ndescription: .+\n---\n/,
    );
    assert.match(body, /^description: Use when\b/m);
    assert.match(body, /npm run agent:route/);
    assertMaintainerSkillContract(body);
  }
});

test("maintainer skill contract rejects configuration writes and external app bootstrap variants", () => {
  const safeSkill = [
    "---",
    "name: fixture-skill",
    "description: Use when maintaining the FixThis source checkout.",
    "---",
    "",
    "# Fixture",
    "Use for FixThis source checkout maintenance.",
    "Run npm run agent:route -- --task agent-kit --json.",
  ].join("\n");
  const forbiddenInstructions = [
    "Write ~/.codex/config.toml before starting.",
    "Change the project-local .codex/settings.json before starting.",
    "Create the project-local .codex/settings.json before starting.",
    "Modify global .codex/settings.json before starting.",
    "The global .codex configuration must be updated.",
    "Edit .codex/config.toml before starting.",
    "Bootstrap an external Android app with FixThis.",
    "Run fixthis install-agent for an external app.",
    "Run fixthis init for an external app.",
    "Run fixthis doctor for an external app.",
    "Run scripts/bootstrap-mcp.sh --package com.example.app.",
  ];

  for (const instruction of forbiddenInstructions) {
    assert.throws(
      () => assertMaintainerSkillContract(safeSkill + "\n" + instruction),
      undefined,
      "must reject: " + instruction,
    );
  }
  assert.doesNotThrow(() =>
    assertMaintainerSkillContract(
      safeSkill + "\nNever modify ~/.codex/config.toml.",
    ),
  );
});

test("release skill separates reality proof and state changes", () => {
  const body = read(
    ".agents/skills/fixthis-release-maintenance/SKILL.md",
  );
  for (const command of [
    "npm run release:reality",
    "npm run evidence:release",
    "npm run release:check",
  ]) {
    assert.ok(body.includes(command), "release skill missing " + command);
  }
  assert.match(
    body,
    /Tag, publish, push, or edit a downstream repository only when the user explicitly requests/,
  );
});
