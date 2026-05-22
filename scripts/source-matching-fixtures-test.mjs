import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const repoRoot = fileURLToPath(new URL("..", import.meta.url));

function read(path) {
  return readFileSync(join(repoRoot, path), "utf8");
}

function readJson(path) {
  return JSON.parse(read(path));
}

test("gitignore explicitly excludes local fixture cache and reports", () => {
  const text = read(".gitignore");
  assert.match(text, /^\.fixthis\/eval-fixtures\/$/m);
  assert.match(text, /^build\/reports\/fixthis-source-matching\/$/m);
});

test("package.json exposes local fixture scripts", () => {
  const pkg = readJson("package.json");
  assert.equal(pkg.scripts["source-matching:fixtures:prepare"], "node scripts/source-matching-fixtures.mjs prepare");
  assert.equal(pkg.scripts["source-matching:fixtures"], "node scripts/source-matching-fixtures.mjs run");
  assert.equal(pkg.scripts["source-matching:fixtures:report"], "node scripts/source-matching-fixtures.mjs report");
  assert.equal(pkg.scripts["source-matching:fixtures:test"], "node --test scripts/source-matching-fixtures-test.mjs");
});

test("fixture manifest uses immutable pinned commits", () => {
  const manifest = readJson("fixtures/source-matching/manifest.json");
  assert.equal(manifest.schemaVersion, 1);
  assert.equal(manifest.fixtures.length, 3);
  for (const fixture of manifest.fixtures) {
    assert.match(fixture.repo, /^https:\/\/github\.com\/android\//);
    assert.match(fixture.commit, /^[a-f0-9]{40}$/);
    assert.ok(fixture.id.length > 0);
    assert.ok(fixture.projectDir.length > 0);
    assert.ok(fixture.modulePath.startsWith(":"));
    assert.ok(fixture.variant.length > 0);
    assert.ok(fixture.applicationId.length > 0);
    assert.ok(fixture.cases.length > 0);
  }
});
