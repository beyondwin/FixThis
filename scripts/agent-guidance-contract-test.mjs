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
