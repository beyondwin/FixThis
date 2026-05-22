import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, readFileSync as readTempFile, writeFileSync as writeTempFile } from "node:fs";
import { tmpdir } from "node:os";
import { join, join as joinPath } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

import {
  classifyCaseOutcome,
  fixturePaths,
  patchAppBuildFileText,
  patchSettingsText,
  reportStatus,
  safeRelativePath,
  validateManifest,
  variantTaskSuffix,
} from "./source-matching-fixtures.mjs";

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

test("validateManifest rejects floating commits and unsafe paths", () => {
  assert.throws(
    () => validateManifest({
      schemaVersion: 1,
      fixtures: [{
        id: "bad",
        repo: "https://github.com/android/compose-samples.git",
        commit: "main",
        projectDir: "../outside",
        modulePath: ":app",
        variant: "debug",
        applicationId: "com.example.bad",
        cases: [],
      }],
    }),
    /commit.*40-character SHA|projectDir escapes fixture root|cases must contain/,
  );
});

test("safeRelativePath rejects absolute paths and traversal", () => {
  assert.equal(safeRelativePath("Reply"), "Reply");
  assert.equal(safeRelativePath("."), ".");
  assert.throws(() => safeRelativePath("/tmp/nope"), /must be relative/);
  assert.throws(() => safeRelativePath("../nope"), /escapes fixture root/);
});

test("variantTaskSuffix converts Gradle variants to task suffixes", () => {
  assert.equal(variantTaskSuffix("debug"), "Debug");
  assert.equal(variantTaskSuffix("demoDebug"), "DemoDebug");
});

test("classifyCaseOutcome flags top hits, missing warnings, and overconfidence", () => {
  assert.deepEqual(
    classifyCaseOutcome({
      expectedTop3PathContains: ["Home.kt"],
      expectedConfidence: "low-or-medium",
      mustWarn: ["POSSIBLE_VIEW_INTEROP"],
    }, {
      candidates: [
        { path: "sample/Home.kt" },
        { path: "sample/Other.kt" },
      ],
      confidence: "high",
      warnings: [],
    }).failures,
    ["overconfident", "missing_warning"],
  );

  assert.deepEqual(
    classifyCaseOutcome({
      expectedTop3PathContains: ["Home.kt"],
      expectedConfidence: "medium-or-high",
      mustNotWarn: ["POSSIBLE_VIEW_INTEROP"],
    }, {
      candidates: [{ path: "sample/Home.kt" }],
      confidence: "medium",
      warnings: [],
    }).metrics,
    ["top1_hit", "top3_hit", "confidence_calibrated"],
  );
});

test("reportStatus distinguishes pass, fail, and environment downgrade", () => {
  assert.equal(reportStatus([{ failures: [], environment: [] }]), "pass");
  assert.equal(reportStatus([{ failures: [], environment: ["device_unavailable"] }]), "pass_with_environment_downgrade");
  assert.equal(reportStatus([{ failures: ["missing_top3"], environment: [] }]), "fail");
});

test("fixturePaths keeps repos and work under ignored local root", () => {
  const paths = fixturePaths({
    id: "reply",
    repo: "https://github.com/android/compose-samples.git",
    commit: "d3ff757b289f7036815978a8f7b16706ee3423b0",
    projectDir: "Reply",
  });
  assert.match(paths.repoDir, /\.fixthis\/eval-fixtures\/repos\/[a-f0-9]{12}$/);
  assert.match(paths.workDir, /\.fixthis\/eval-fixtures\/work\/reply$/);
});

test("patchSettingsText inserts the FixThis Gradle plugin included build once", () => {
  const original = [
    "pluginManagement {",
    "    repositories {",
    "        google()",
    "    }",
    "}",
    "rootProject.name = \"Reply\"",
    "",
  ].join("\n");
  const patched = patchSettingsText(original, "/repo/fixthis-gradle-plugin");
  assert.match(patched, /pluginManagement \{\n    includeBuild\("\/repo\/fixthis-gradle-plugin"\)/);
  assert.equal(patchSettingsText(patched, "/repo/fixthis-gradle-plugin"), patched);
});

test("patchAppBuildFileText applies FixThis without runtime dependency", () => {
  const original = [
    "plugins {",
    "    alias(libs.plugins.android.application)",
    "}",
    "",
  ].join("\n");
  const patched = patchAppBuildFileText(original);
  assert.match(patched, /id\("io\.github\.beyondwin\.fixthis\.compose"\)/);
  assert.match(patched, /addDebugRuntime\.set\(false\)/);
  assert.equal(patchAppBuildFileText(patched), patched);
});
