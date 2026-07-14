import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const repoRoot = fileURLToPath(new URL("..", import.meta.url));
const read = (path) => readFileSync(join(repoRoot, path), "utf8");

test("manual CLI package reruns checkout the requested release ref", () => {
  const workflow = read(".github/workflows/release-cli-mcp.yml");

  assert.match(
    workflow,
    /uses: actions\/checkout@v5[\s\S]*?ref: \$\{\{ github\.event_name == 'workflow_dispatch' && inputs\.version \|\| github\.ref \}\}/,
  );
});

test("npm and MCP publication can resume after npm already published", () => {
  const workflow = read(".github/workflows/publish-npm-mcp.yml");

  assert.match(workflow, /id: npm-status[\s\S]*?npm view "@beyondwin\/fixthis@\$\{\{ inputs\.version \}\}" version/);
  assert.match(workflow, /already_published=true/);
  assert.match(workflow, /if: steps\.npm-status\.outputs\.already_published != 'true'/);
  assert.match(workflow, /run: npm publish --access public/);
});

test("release instructions tag the synchronized origin main commit", () => {
  const processDoc = read("docs/contributing/release-process.md");

  assert.match(processDoc, /git fetch origin main/);
  assert.match(processDoc, /git merge --ff-only origin\/main/);
  assert.match(processDoc, /tag_target="\$\(git rev-parse origin\/main\)"/);
  assert.match(processDoc, /git tag -a "\$tag" "\$tag_target"/);
  assert.match(processDoc, /git push origin "\$tag"/);
  assert.doesNotMatch(processDoc, /git push origin main v0\.2\.0/);
});
