import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const repoRoot = fileURLToPath(new URL("..", import.meta.url));

function read(path) {
  return readFileSync(join(repoRoot, path), "utf8");
}

function androidVersion(name) {
  const match = read("gradle/libs.versions.toml").match(new RegExp(`^${name}\\s*=\\s*"([^"]+)"`, "m"));
  assert.ok(match, `${name} is missing from gradle/libs.versions.toml`);
  return match[1];
}

test("Android SDK levels are centralized in the version catalog", () => {
  assert.equal(androidVersion("androidCompileSdk"), "34");
  assert.equal(androidVersion("androidTargetSdk"), "34");
  assert.equal(androidVersion("androidMinSdk"), "23");

  const sidekickBuild = read("fixthis-compose-sidekick/build.gradle.kts");
  const sampleBuild = read("sample/build.gradle.kts");

  assert.match(sidekickBuild, /compileSdk\s*=\s*libs\.versions\.androidCompileSdk\s*\.get\(\)\s*\.toInt\(\)/);
  assert.match(sampleBuild, /compileSdk\s*=\s*libs\.versions\.androidCompileSdk\s*\.get\(\)\s*\.toInt\(\)/);
  assert.match(sampleBuild, /targetSdk\s*=\s*libs\.versions\.androidTargetSdk\s*\.get\(\)\s*\.toInt\(\)/);

  assert.doesNotMatch(sidekickBuild, /compileSdk\s*=\s*34/);
  assert.doesNotMatch(sampleBuild, /compileSdk\s*=\s*34/);
});

test("Android compatibility docs match the catalog compileSdk floor", () => {
  const requiredCompileSdk = androidVersion("androidCompileSdk");
  const files = {
    "docs/reference/compatibility.md": new RegExp(
      `Android \`compileSdk\`\\s*\\|\\s*\\*\\*${requiredCompileSdk}\\*\\*\\s*\\|\\s*${requiredCompileSdk}`,
    ),
    "docs/getting-started/try-the-sample.md": new RegExp(
      `Android \`targetSdk\` / \`compileSdk\`\\s*\\|\\s*${requiredCompileSdk}`,
    ),
  };

  const mismatches = Object.entries(files)
    .filter(([path, pattern]) => !pattern.test(read(path)))
    .map(([path]) => path);

  assert.deepEqual(
    mismatches,
    [],
    `FixThis 0.6.x sidekick artifacts require compileSdk ${requiredCompileSdk}`,
  );
});

test("Android compatibility docs match the catalog minSdk floor", () => {
  const requiredMinSdk = androidVersion("androidMinSdk");
  const files = {
    "docs/reference/compatibility.md": new RegExp(
      `Android \`minSdk\`\\s*\\|\\s*\\*\\*${requiredMinSdk}\\*\\*\\s*\\|\\s*${requiredMinSdk}`,
    ),
    "docs/getting-started/add-to-your-app.md": new RegExp(`\`minSdk\` ${requiredMinSdk}`),
    "docs/getting-started/try-the-sample.md": new RegExp(
      `Android \`minSdk\`\\s*\\|\\s*${requiredMinSdk}`,
    ),
  };

  const mismatches = Object.entries(files)
    .filter(([path, pattern]) => !pattern.test(read(path)))
    .map(([path]) => path);

  assert.deepEqual(
    mismatches,
    [],
    `FixThis 0.6.x sidekick artifacts require minSdk ${requiredMinSdk}`,
  );
});

test("Central Portal bundle rejects sidekick artifacts with a drifted minCompileSdk", () => {
  const script = read("scripts/create-central-portal-bundle.sh");

  assert.match(script, /fixthis-compose-sidekick-\$version\.aar/);
  assert.match(script, /repo_dir_parent/);
  assert.match(script, /bundle_path_parent/);
  assert.match(script, /androidCompileSdk/);
  assert.match(script, /expected_compile_sdk/);
  assert.match(script, /minCompileSdk=\$expected_compile_sdk/);
  assert.doesNotMatch(script, /minCompileSdk=34/);
});
