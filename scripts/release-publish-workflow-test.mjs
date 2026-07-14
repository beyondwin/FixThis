import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const repoRoot = fileURLToPath(new URL("..", import.meta.url));
const read = (path) => readFileSync(join(repoRoot, path), "utf8");

function shellRunBlocks(workflow) {
  const lines = workflow.split("\n");
  const blocks = [];
  for (let index = 0; index < lines.length; index += 1) {
    const match = lines[index].match(/^(\s*)run:\s*\|\s*$/);
    if (!match) continue;
    const indent = match[1].length;
    const body = [];
    for (index += 1; index < lines.length; index += 1) {
      const line = lines[index];
      if (line.trim() && line.search(/\S/) <= indent) {
        index -= 1;
        break;
      }
      body.push(line);
    }
    blocks.push(body.join("\n"));
  }
  return blocks;
}

test("release shell steps never interpolate workflow-dispatch inputs directly", () => {
  const paths = [
    ".github/workflows/publish-npm-mcp.yml",
    ".github/workflows/publish-maven-central.yml",
    ".github/workflows/publish-gradle-plugin.yml",
    ".github/workflows/promote-maven-central-staging.yml",
    ".github/workflows/release-cli-mcp.yml",
  ];

  for (const path of paths) {
    for (const block of shellRunBlocks(read(path))) {
      assert.doesNotMatch(block, /\$\{\{\s*inputs\./, path);
    }
  }
});

test("manual CLI package reruns checkout the requested release ref", () => {
  const workflow = read(".github/workflows/release-cli-mcp.yml");

  assert.match(
    workflow,
    /uses: actions\/checkout@v5[\s\S]*?ref: \$\{\{ github\.event_name == 'workflow_dispatch' && format\('refs\/tags\/\{0\}', inputs\.version\) \|\| github\.ref \}\}/,
  );
  assert.match(workflow, /git rev-parse "refs\/tags\/\$tag\^\{commit\}"/);
  assert.match(workflow, /test "\$\(git rev-parse HEAD\)" = "\$tag_commit"/);
});

test("npm and MCP publication can resume after npm already published", () => {
  const workflow = read(".github/workflows/publish-npm-mcp.yml");

  assert.match(workflow, /RELEASE_VERSION: \$\{\{ inputs\.version \}\}/);
  assert.match(workflow, /id: npm-status[\s\S]*?case "\$status" in[\s\S]*?200\)[\s\S]*?404\)[\s\S]*?\*\)/);
  assert.match(workflow, /already_published=true/);
  assert.match(workflow, /if: steps\.npm-status\.outputs\.already_published != 'true'/);
  assert.match(workflow, /run: npm publish --access public/);
  assert.doesNotMatch(workflow, /npm view[\s\S]*?\|\| true/);
});

test("MCP publisher download is version-pinned and checksum-verified", () => {
  const workflow = read(".github/workflows/publish-npm-mcp.yml");

  assert.match(workflow, /MCP_PUBLISHER_VERSION: "1\.8\.0"/);
  assert.match(workflow, /MCP_PUBLISHER_SHA256_LINUX_AMD64: "[a-f0-9]{64}"/);
  assert.match(workflow, /releases\/download\/v\$MCP_PUBLISHER_VERSION\/mcp-publisher_linux_amd64\.tar\.gz/);
  assert.match(workflow, /sha256sum --check/);
  assert.doesNotMatch(workflow, /releases\/latest\/download/);
});

test("Maven publication can resume an existing Central deployment", () => {
  const workflow = read(".github/workflows/publish-maven-central.yml");

  assert.match(workflow, /deployment_id:[\s\S]*?required: false/);
  assert.match(workflow, /RESUME_DEPLOYMENT_ID: \$\{\{ inputs\.deployment_id \}\}/);
  assert.match(workflow, /if \[\[ -n "\$RESUME_DEPLOYMENT_ID" \]\]; then[\s\S]*?deployment_id=\$RESUME_DEPLOYMENT_ID/);
  assert.match(workflow, /deployment-id-\$\{\{ steps\.bundle\.outputs\.version \}\}/);
  assert.match(workflow, /deployment_id="\$\(tr -d '[^']*' <"\$response"\)"[\s\S]*?Invalid deployment ID from Maven Central/);
  assert.match(workflow, /name: Record Maven Central deployment ID[\s\S]*?env:[\s\S]*?DEPLOYMENT_ID: \$\{\{ steps\.upload\.outputs\.deployment_id \}\}/);
  assert.match(workflow, /name: Wait for Maven Central publication[\s\S]*?env:[\s\S]*?DEPLOYMENT_ID: \$\{\{ steps\.upload\.outputs\.deployment_id \}\}/);
  assert.doesNotMatch(workflow, /deployment_id="\$\{\{ steps\.upload\.outputs\.deployment_id \}\}"/);
});

test("Maven publication skips immutable coordinates that are already public", () => {
  const workflow = read(".github/workflows/publish-maven-central.yml");

  assert.match(workflow, /id: maven-status[\s\S]*?fixthis-compose-core[\s\S]*?fixthis-compose-sidekick[\s\S]*?fixthis-gradle-plugin/);
  assert.match(workflow, /already_published=true/);
  assert.match(workflow, /if: steps\.maven-status\.outputs\.already_published != 'true'/);
  assert.match(workflow, /if: steps\.maven-status\.outputs\.already_published == 'true'/);
  assert.match(workflow, /published_count=\$\(\(published_count \+ 1\)\)/);
  assert.match(workflow, /missing_count=\$\(\(missing_count \+ 1\)\)/);
  assert.match(workflow, /Partial Maven Central publication detected/);
});

test("all credentialed publish workflows verify immutable release tags", () => {
  const expectations = [
    [".github/workflows/publish-npm-mcp.yml", /ref: refs\/tags\/v\$\{\{ inputs\.version \}\}/, /refs\/tags\/v\$\{RELEASE_VERSION\}\^\{commit\}/],
    [".github/workflows/publish-maven-central.yml", /ref: refs\/tags\/\$\{\{ inputs\.version \}\}/, /refs\/tags\/\$RELEASE_TAG\^\{commit\}/],
    [".github/workflows/publish-gradle-plugin.yml", /ref: refs\/tags\/\$\{\{ inputs\.version \}\}/, /refs\/tags\/\$RELEASE_TAG\^\{commit\}/],
  ];

  for (const [path, checkoutPattern, verificationPattern] of expectations) {
    const workflow = read(path);
    assert.match(workflow, checkoutPattern, path);
    assert.match(workflow, verificationPattern, path);
  }
});

test("immutable release publication channels serialize the same version", () => {
  const expectations = [
    [".github/workflows/publish-npm-mcp.yml", /group: publish-npm-mcp-\$\{\{ inputs\.version \}\}/],
    [".github/workflows/publish-maven-central.yml", /group: publish-maven-central-\$\{\{ inputs\.version \}\}/],
    [".github/workflows/publish-gradle-plugin.yml", /group: publish-gradle-plugin-\$\{\{ inputs\.version \}\}/],
    [".github/workflows/release-cli-mcp.yml", /group: release-cli-mcp-\$\{\{/],
  ];

  for (const [path, pattern] of expectations) {
    const workflow = read(path);
    assert.match(workflow, pattern, path);
    assert.match(workflow, /cancel-in-progress: false/, path);
  }
});

test("Gradle Plugin Portal publication skips an already-public immutable version", () => {
  const workflow = read(".github/workflows/publish-gradle-plugin.yml");

  assert.match(workflow, /id: plugin-status[\s\S]*?plugins\.gradle\.org\/m2/);
  assert.match(workflow, /case "\$status" in[\s\S]*?200\)[\s\S]*?404\)[\s\S]*?\*\)/);
  assert.match(workflow, /if: steps\.plugin-status\.outputs\.already_published != 'true'/);
  assert.match(workflow, /if: steps\.plugin-status\.outputs\.already_published == 'true'/);
});

test("release docs use the current SemVer heading and no pre-1.0 exception", () => {
  const changelog = read("CHANGELOG.md");
  const processDoc = read("docs/contributing/release-process.md");

  assert.doesNotMatch(changelog, /Until the first `1\.0\.0` external release/);
  assert.match(processDoc, /## v0\.2\.0 — 2026-MM-DD/);
  assert.doesNotMatch(processDoc, /## \[0\.2\.0\] - 2026-MM-DD/);
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
