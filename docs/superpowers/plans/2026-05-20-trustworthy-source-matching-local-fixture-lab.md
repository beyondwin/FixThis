# Trustworthy Source Matching Local Fixture Lab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a local-only fixture lab that pins official Google Compose samples, prepares gitignored disposable working copies, evaluates generated FixThis source indexes, and reports trust-oriented source matching evidence.

**Architecture:** Add a manifest-driven Node runner under `scripts/` and a small committed fixture manifest under `fixtures/source-matching/`. The runner owns manifest validation, local fixture preparation, FixThis Gradle plugin injection with runtime disabled for source-index-only evaluation, source-index checks, and JSON/Markdown report output under ignored build paths.

**Tech Stack:** Node.js 20 ESM, `node:test`, Git CLI, Gradle wrapper, FixThis Gradle plugin via `pluginManagement { includeBuild(...) }`, JSON source-index assets.

---

## File Structure

- Modify `.gitignore`
  - Explicitly ignore `.fixthis/eval-fixtures/` and `build/reports/fixthis-source-matching/`.
- Modify `package.json`
  - Add local-only npm scripts for prepare/run/report/test.
- Create `fixtures/source-matching/manifest.json`
  - Pinned external fixture definitions and source-index evaluation cases.
- Create `scripts/source-matching-fixtures.mjs`
  - Importable helper module plus CLI entrypoint for manifest validation, preparation, evaluation, and reporting.
- Create `scripts/source-matching-fixtures-test.mjs`
  - Fast offline tests for manifest validation, path safety, Gradle patch helpers, report classification, and source-index evaluation.
- Create `docs/guides/source-matching-fixture-lab.md`
  - Local workflow and report interpretation.
- Modify `docs/index.md`
  - Link the local fixture lab guide from the documentation index.

The first implementation intentionally does not perform device-backed capture. It records device-backed capture as `not_configured` and keeps the useful source-index path working without an emulator.

---

### Task 1: Add Local Fixture Guardrails And Manifest

**Files:**
- Modify: `.gitignore`
- Modify: `package.json`
- Create: `fixtures/source-matching/manifest.json`
- Create: `scripts/source-matching-fixtures-test.mjs`

- [ ] **Step 1: Write failing contract tests for ignored paths, scripts, and manifest pins**

Create `scripts/source-matching-fixtures-test.mjs` with these initial tests:

```js
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
```

- [ ] **Step 2: Run the new tests to verify they fail**

Run:

```bash
node --test scripts/source-matching-fixtures-test.mjs
```

Expected: FAIL because `.gitignore`, `package.json`, and `fixtures/source-matching/manifest.json` do not yet contain the new contracts.

- [ ] **Step 3: Add explicit gitignore entries**

Append these lines to `.gitignore` in the FixThis local capture artifacts section:

```gitignore
.fixthis/eval-fixtures/
build/reports/fixthis-source-matching/
```

- [ ] **Step 4: Add npm scripts**

Modify `package.json` inside the existing `"scripts"` object after `handoff:eval:test`:

```json
"source-matching:fixtures:prepare": "node scripts/source-matching-fixtures.mjs prepare",
"source-matching:fixtures": "node scripts/source-matching-fixtures.mjs run",
"source-matching:fixtures:report": "node scripts/source-matching-fixtures.mjs report",
"source-matching:fixtures:test": "node --test scripts/source-matching-fixtures-test.mjs",
```

Keep valid JSON commas around neighboring entries.

- [ ] **Step 5: Add the initial fixture manifest**

Create `fixtures/source-matching/manifest.json`:

```json
{
  "schemaVersion": 1,
  "fixtures": [
    {
      "id": "reply",
      "repo": "https://github.com/android/compose-samples.git",
      "commit": "d3ff757b289f7036815978a8f7b16706ee3423b0",
      "projectDir": "Reply",
      "modulePath": ":app",
      "variant": "debug",
      "applicationId": "com.example.reply",
      "cases": [
        {
          "id": "reply-main-activity-owner",
          "mode": "source-index",
          "expectedEntryPathContains": "Reply/app/src/main/java/com/example/reply/ui/MainActivity.kt",
          "expectedSignal": {
            "kind": "COMPOSABLE_SYMBOL",
            "value": "ReplyApp"
          }
        },
        {
          "id": "reply-list-content-owner",
          "mode": "source-index",
          "expectedEntryPathContains": "Reply/app/src/main/java/com/example/reply/ui/ReplyListContent.kt",
          "expectedSignal": {
            "kind": "COMPOSABLE_SYMBOL",
            "value": "ReplyEmailList"
          }
        }
      ]
    },
    {
      "id": "jetsnack",
      "repo": "https://github.com/android/compose-samples.git",
      "commit": "d3ff757b289f7036815978a8f7b16706ee3423b0",
      "projectDir": "Jetsnack",
      "modulePath": ":app",
      "variant": "debug",
      "applicationId": "com.example.jetsnack",
      "cases": [
        {
          "id": "jetsnack-home-owner",
          "mode": "source-index",
          "expectedEntryPathContains": "Jetsnack/app/src/main/java/com/example/jetsnack/ui/home/Home.kt",
          "expectedSignal": {
            "kind": "COMPOSABLE_SYMBOL",
            "value": "Home"
          }
        },
        {
          "id": "jetsnack-search-category-copy",
          "mode": "source-index",
          "expectedEntryPathContains": "Jetsnack/app/src/main/java/com/example/jetsnack/model/Search.kt",
          "expectedSignal": {
            "kind": "ARBITRARY_STRING_LITERAL",
            "value": "Desserts"
          }
        }
      ]
    },
    {
      "id": "nowinandroid",
      "repo": "https://github.com/android/nowinandroid.git",
      "commit": "7d45eae4f8720a0c77f507712ba2437ff974b6ed",
      "projectDir": ".",
      "modulePath": ":app",
      "variant": "demoDebug",
      "applicationId": "com.google.samples.apps.nowinandroid.demo.debug",
      "cases": [
        {
          "id": "nia-for-you-title-resource",
          "mode": "source-index",
          "expectedEntryPathContains": "feature/foryou/api/src/main/res/values/strings.xml",
          "expectedSignal": {
            "kind": "STRING_RESOURCE_RESOLVED",
            "value": "For you"
          }
        },
        {
          "id": "nia-navigation-component-owner",
          "mode": "source-index",
          "expectedEntryPathContains": "core/designsystem/src/main/kotlin/com/google/samples/apps/nowinandroid/core/designsystem/component/Navigation.kt",
          "expectedSignal": {
            "kind": "COMPOSABLE_SYMBOL",
            "value": "NiaNavigationBar"
          }
        }
      ]
    }
  ]
}
```

- [ ] **Step 6: Run the contract tests**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: PASS for the three contract tests.

- [ ] **Step 7: Commit Task 1**

Run:

```bash
git add .gitignore package.json fixtures/source-matching/manifest.json scripts/source-matching-fixtures-test.mjs
git commit -m "test: add local source matching fixture contracts"
```

---

### Task 2: Implement Manifest Validation And Report Classification

**Files:**
- Create: `scripts/source-matching-fixtures.mjs`
- Modify: `scripts/source-matching-fixtures-test.mjs`

- [ ] **Step 1: Add failing tests for validation, path safety, variants, and classification**

Append to `scripts/source-matching-fixtures-test.mjs`:

```js
import {
  classifyCaseOutcome,
  reportStatus,
  safeRelativePath,
  validateManifest,
  variantTaskSuffix,
} from "./source-matching-fixtures.mjs";

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
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run:

```bash
node --test scripts/source-matching-fixtures-test.mjs
```

Expected: FAIL with `Cannot find module './source-matching-fixtures.mjs'`.

- [ ] **Step 3: Create the initial runner module**

Create `scripts/source-matching-fixtures.mjs`:

```js
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, normalize, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

export const repoRoot = fileURLToPath(new URL("..", import.meta.url));
export const defaultManifestPath = join(repoRoot, "fixtures/source-matching/manifest.json");
export const defaultReportDir = join(repoRoot, "build/reports/fixthis-source-matching");

const fullShaPattern = /^[a-f0-9]{40}$/;
const allowedConfidence = new Set(["high", "medium", "low", "unknown"]);
const confidenceExpectations = new Set(["high", "medium-or-high", "low-or-medium", "low", "unknown"]);

export function safeRelativePath(value, fieldName = "path") {
  if (typeof value !== "string" || value.length === 0) {
    throw new Error(`${fieldName} must be a non-empty string`);
  }
  if (value === ".") return ".";
  if (value.startsWith("/") || /^[A-Za-z]:[\\/]/.test(value)) {
    throw new Error(`${fieldName} must be relative`);
  }
  const normalized = normalize(value);
  if (normalized === ".." || normalized.startsWith(`..${sep}`) || normalized.includes(`${sep}..${sep}`)) {
    throw new Error(`${fieldName} escapes fixture root`);
  }
  return normalized.replaceAll("\\", "/");
}

export function variantTaskSuffix(variant) {
  if (typeof variant !== "string" || variant.length === 0) {
    throw new Error("variant must be a non-empty string");
  }
  return variant.charAt(0).toUpperCase() + variant.slice(1);
}

export function sourceIndexTaskPath(fixture) {
  return `${fixture.modulePath}:generate${variantTaskSuffix(fixture.variant)}FixThisSourceIndex`;
}

export function generatedSourceIndexPath(projectRoot, fixture) {
  const moduleDir = fixture.modulePath.replace(/^:/, "").replaceAll(":", "/");
  return join(projectRoot, moduleDir, "build/generated/fixthis", fixture.variant, "assets/fixthis/fixthis-source-index.json");
}

export function loadManifest(path = defaultManifestPath) {
  return validateManifest(JSON.parse(readFileSync(path, "utf8")));
}

export function validateManifest(manifest) {
  const errors = [];
  if (!manifest || typeof manifest !== "object") errors.push("manifest must be an object");
  if (manifest?.schemaVersion !== 1) errors.push("schemaVersion must be 1");
  if (!Array.isArray(manifest?.fixtures) || manifest.fixtures.length === 0) {
    errors.push("fixtures must contain at least one fixture");
  }
  for (const fixture of manifest?.fixtures || []) {
    if (!fixture.id || !/^[a-z0-9-]+$/.test(fixture.id)) errors.push("fixture id must use lowercase slug syntax");
    if (!fixture.repo || !fixture.repo.startsWith("https://github.com/android/")) errors.push(`${fixture.id || "fixture"} repo must be an Android HTTPS GitHub URL`);
    if (!fullShaPattern.test(fixture.commit || "")) errors.push(`${fixture.id || "fixture"} commit must be a 40-character SHA`);
    try { safeRelativePath(fixture.projectDir, `${fixture.id || "fixture"} projectDir`); } catch (error) { errors.push(error.message); }
    if (!fixture.modulePath || !fixture.modulePath.startsWith(":")) errors.push(`${fixture.id || "fixture"} modulePath must start with :`);
    if (!fixture.variant) errors.push(`${fixture.id || "fixture"} variant must be present`);
    if (!fixture.applicationId) errors.push(`${fixture.id || "fixture"} applicationId must be present`);
    if (!Array.isArray(fixture.cases) || fixture.cases.length === 0) errors.push(`${fixture.id || "fixture"} cases must contain at least one case`);
    for (const entry of fixture.cases || []) {
      if (!entry.id || !/^[a-z0-9-]+$/.test(entry.id)) errors.push(`${fixture.id || "fixture"} case id must use lowercase slug syntax`);
      if (entry.mode !== "source-index") errors.push(`${entry.id || "case"} mode must be source-index`);
      if (entry.expectedConfidence && !confidenceExpectations.has(entry.expectedConfidence)) {
        errors.push(`${entry.id} expectedConfidence is unsupported`);
      }
      if (!entry.expectedEntryPathContains && !entry.expectedTop3PathContains) {
        errors.push(`${entry.id || "case"} must define expectedEntryPathContains or expectedTop3PathContains`);
      }
      if (entry.expectedSignal) {
        if (!entry.expectedSignal.kind || !entry.expectedSignal.value) {
          errors.push(`${entry.id || "case"} expectedSignal must include kind and value`);
        }
      }
    }
  }
  if (errors.length) {
    throw new Error(errors.join("; "));
  }
  return manifest;
}

export function classifyCaseOutcome(expectation, observed) {
  const metrics = [];
  const failures = [];
  const candidates = observed.candidates || [];
  const warnings = new Set(observed.warnings || []);
  const top1Needles = arrayOf(expectation.expectedTop1PathContains || expectation.expectedEntryPathContains);
  const top3Needles = arrayOf(expectation.expectedTop3PathContains || expectation.expectedEntryPathContains);

  if (top1Needles.length && candidates[0]?.path && top1Needles.some((needle) => candidates[0].path.includes(needle))) {
    metrics.push("top1_hit");
  } else if (expectation.expectedTop1PathContains) {
    failures.push("wrong_top1");
  }

  if (top3Needles.length && candidates.slice(0, 3).some((candidate) => top3Needles.some((needle) => candidate.path.includes(needle)))) {
    metrics.push("top3_hit");
  } else if (top3Needles.length) {
    failures.push("missing_top3");
  }

  if (expectation.expectedConfidence && observed.confidence) {
    if (confidenceMatches(expectation.expectedConfidence, observed.confidence)) {
      metrics.push("confidence_calibrated");
    } else if (observed.confidence === "high" && expectation.expectedConfidence === "low-or-medium") {
      failures.push("overconfident");
    }
  }

  for (const warning of expectation.mustWarn || []) {
    if (warnings.has(warning)) metrics.push("warning_present");
    else failures.push("missing_warning");
  }
  for (const warning of expectation.mustNotWarn || []) {
    if (warnings.has(warning)) failures.push("unexpected_warning");
    else metrics.push("warning_absent");
  }

  return { metrics, failures, environment: observed.environment || [] };
}

export function reportStatus(results) {
  if (results.some((result) => result.failures.length > 0)) return "fail";
  if (results.some((result) => result.environment.length > 0)) return "pass_with_environment_downgrade";
  return "pass";
}

export function ensureDir(path) {
  mkdirSync(path, { recursive: true });
}

export function writeJson(path, value) {
  ensureDir(dirname(path));
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`);
}

function confidenceMatches(expected, actual) {
  if (!allowedConfidence.has(actual)) return false;
  if (expected === actual) return true;
  if (expected === "medium-or-high") return actual === "medium" || actual === "high";
  if (expected === "low-or-medium") return actual === "low" || actual === "medium";
  return false;
}

function arrayOf(value) {
  if (!value) return [];
  return Array.isArray(value) ? value : [value];
}

function usage() {
  return "Usage: node scripts/source-matching-fixtures.mjs <prepare|run|report>";
}

export async function main(argv = process.argv.slice(2)) {
  const command = argv[0];
  if (!command || !["prepare", "run", "report"].includes(command)) {
    console.error(usage());
    return 2;
  }
  const manifest = loadManifest();
  ensureDir(defaultReportDir);
  const marker = join(defaultReportDir, `${command}-validation.json`);
  writeJson(marker, {
    schemaVersion: 1,
    command,
    status: "validated",
    fixtureCount: manifest.fixtures.length,
  });
  console.log(`Wrote ${relative(repoRoot, marker)}`);
  return 0;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const status = await main();
  process.exitCode = status;
}
```

- [ ] **Step 4: Run tests**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: PASS.

- [ ] **Step 5: Commit Task 2**

Run:

```bash
git add scripts/source-matching-fixtures.mjs scripts/source-matching-fixtures-test.mjs
git commit -m "feat: validate local source matching fixture manifests"
```

---

### Task 3: Implement Fixture Preparation And Gradle Injection

**Files:**
- Modify: `scripts/source-matching-fixtures.mjs`
- Modify: `scripts/source-matching-fixtures-test.mjs`

- [ ] **Step 1: Add failing tests for Gradle patches and fixture paths**

Append to `scripts/source-matching-fixtures-test.mjs`:

```js
import { mkdtempSync, readFileSync as readTempFile, writeFileSync as writeTempFile } from "node:fs";
import { tmpdir } from "node:os";
import { join as joinPath } from "node:path";
import {
  fixturePaths,
  patchAppBuildFileText,
  patchSettingsText,
} from "./source-matching-fixtures.mjs";

test("fixturePaths keeps repos and work under ignored local root", () => {
  const paths = fixturePaths({
    id: "reply",
    repo: "https://github.com/android/compose-samples.git",
    commit: "d3ff757b289f7036815978a8f7b16706ee3423b0",
    projectDir: "Reply",
  });
  assert.match(paths.repoDir, /\.fixthis\/eval-fixtures\/repos\/reply$/);
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: FAIL because `fixturePaths`, `patchSettingsText`, and `patchAppBuildFileText` are not exported.

- [ ] **Step 3: Add path and patch helpers**

Add these exports to `scripts/source-matching-fixtures.mjs`:

```js
export const fixtureRoot = join(repoRoot, ".fixthis/eval-fixtures");
export const fixtureRepoRoot = join(fixtureRoot, "repos");
export const fixtureWorkRoot = join(fixtureRoot, "work");

export function fixturePaths(fixture) {
  return {
    repoDir: join(fixtureRepoRoot, fixture.id),
    workDir: join(fixtureWorkRoot, fixture.id),
    projectWorkDir: fixture.projectDir === "."
      ? join(fixtureWorkRoot, fixture.id)
      : join(fixtureWorkRoot, fixture.id, safeRelativePath(fixture.projectDir, `${fixture.id} projectDir`)),
  };
}

export function patchSettingsText(text, fixThisGradlePluginDir) {
  const includeLine = `    includeBuild(${JSON.stringify(fixThisGradlePluginDir)})`;
  if (text.includes(includeLine) || text.includes(`includeBuild(${JSON.stringify(fixThisGradlePluginDir)})`)) {
    return text;
  }
  const pluginManagement = text.match(/pluginManagement\s*\{/);
  if (!pluginManagement) {
    return `pluginManagement {\n${includeLine}\n}\n\n${text}`;
  }
  const insertAt = text.indexOf("\n", pluginManagement.index + pluginManagement[0].length);
  return `${text.slice(0, insertAt + 1)}${includeLine}\n${text.slice(insertAt + 1)}`;
}

export function patchAppBuildFileText(text) {
  const pluginLine = '    id("io.github.beyondwin.fixthis.compose")';
  const configBlock = [
    "",
    "fixthis {",
    "    addDebugRuntime.set(false)",
    "    generateSourceIndex.set(true)",
    "    generateProjectMetadata.set(true)",
    "}",
    "",
  ].join("\n");
  let next = text;
  if (!next.includes("io.github.beyondwin.fixthis.compose")) {
    const pluginsMatch = next.match(/plugins\s*\{/);
    if (!pluginsMatch) throw new Error("Could not find plugins block in app build file");
    const insertAt = next.indexOf("\n", pluginsMatch.index + pluginsMatch[0].length);
    next = `${next.slice(0, insertAt + 1)}${pluginLine}\n${next.slice(insertAt + 1)}`;
  }
  if (!next.includes("addDebugRuntime.set(false)")) {
    next = `${next.trimEnd()}\n${configBlock}`;
  }
  return next;
}
```

- [ ] **Step 4: Add process and copy helpers**

Add these imports at the top of `scripts/source-matching-fixtures.mjs`:

```js
import { spawnSync } from "node:child_process";
import {
  cpSync,
  existsSync,
  mkdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
```

Replace the existing `node:fs` import with the combined import above. Then add:

```js
export function runCommand(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd || repoRoot,
    encoding: "utf8",
    stdio: options.stdio || "pipe",
    env: { ...process.env, ...(options.env || {}) },
  });
  if (result.status !== 0) {
    const detail = [result.stdout, result.stderr].filter(Boolean).join("\n").trim();
    throw new Error(`${command} ${args.join(" ")} failed${detail ? `\n${detail}` : ""}`);
  }
  return result;
}

export function prepareFixture(fixture, options = {}) {
  const paths = fixturePaths(fixture);
  ensureDir(fixtureRepoRoot);
  ensureDir(fixtureWorkRoot);

  if (!existsSync(paths.repoDir)) {
    runCommand("git", ["clone", "--filter=blob:none", fixture.repo, paths.repoDir], { stdio: options.stdio || "pipe" });
  }
  runCommand("git", ["fetch", "origin", fixture.commit, "--depth=1"], { cwd: paths.repoDir, stdio: options.stdio || "pipe" });
  runCommand("git", ["checkout", "--detach", fixture.commit], { cwd: paths.repoDir, stdio: options.stdio || "pipe" });

  rmSync(paths.workDir, { recursive: true, force: true });
  ensureDir(paths.workDir);
  const sourceDir = fixture.projectDir === "." ? paths.repoDir : join(paths.repoDir, safeRelativePath(fixture.projectDir, `${fixture.id} projectDir`));
  cpSync(sourceDir, paths.projectWorkDir, {
    recursive: true,
    filter: (source) => !source.split(sep).some((part) => part === ".git" || part === ".gradle" || part === "build"),
  });

  const settingsPath = join(paths.projectWorkDir, "settings.gradle.kts");
  const appBuildPath = join(paths.projectWorkDir, fixture.modulePath.replace(/^:/, "").replaceAll(":", "/"), "build.gradle.kts");
  writeFileSync(
    settingsPath,
    patchSettingsText(readFileSync(settingsPath, "utf8"), join(repoRoot, "fixthis-gradle-plugin")),
  );
  writeFileSync(appBuildPath, patchAppBuildFileText(readFileSync(appBuildPath, "utf8")));

  return paths;
}
```

- [ ] **Step 5: Wire `prepare` command**

Replace `main` with:

```js
export async function main(argv = process.argv.slice(2)) {
  const command = argv[0];
  if (!command || !["prepare", "run", "report"].includes(command)) {
    console.error(usage());
    return 2;
  }
  const manifest = loadManifest();
  ensureDir(defaultReportDir);
  if (command === "prepare") {
    const prepared = [];
    for (const fixture of manifest.fixtures) {
      const paths = prepareFixture(fixture, { stdio: "inherit" });
      prepared.push({
        fixtureId: fixture.id,
        repoDir: relative(repoRoot, paths.repoDir),
        workDir: relative(repoRoot, paths.workDir),
      });
    }
    writeJson(join(defaultReportDir, "prepare.json"), {
      schemaVersion: 1,
      status: "prepared",
      prepared,
    });
    console.log(`Prepared ${prepared.length} source matching fixtures`);
    return 0;
  }
  writeJson(join(defaultReportDir, `${command}-validation.json`), {
    schemaVersion: 1,
    command,
    status: "validated",
    fixtureCount: manifest.fixtures.length,
  });
  return 0;
}
```

- [ ] **Step 6: Run tests**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: PASS.

- [ ] **Step 7: Optionally smoke prepare one fixture locally**

Run:

```bash
node scripts/source-matching-fixtures.mjs prepare
```

Expected: The command clones the pinned repositories under `.fixthis/eval-fixtures/repos/`, writes disposable work copies under `.fixthis/eval-fixtures/work/`, and writes `build/reports/fixthis-source-matching/prepare.json`.

- [ ] **Step 8: Commit Task 3**

Run:

```bash
git add scripts/source-matching-fixtures.mjs scripts/source-matching-fixtures-test.mjs
git commit -m "feat: prepare local source matching fixtures"
```

---

### Task 4: Implement Source-Index Evaluation And Reports

**Files:**
- Modify: `scripts/source-matching-fixtures.mjs`
- Modify: `scripts/source-matching-fixtures-test.mjs`

- [ ] **Step 1: Add failing tests for source-index matching and Markdown reports**

Append to `scripts/source-matching-fixtures-test.mjs`:

```js
import {
  evaluateSourceIndexCase,
  markdownReport,
} from "./source-matching-fixtures.mjs";

test("evaluateSourceIndexCase finds expected path and signal", () => {
  const sourceIndex = {
    schemaVersion: "1.2",
    entries: [
      {
        file: "Reply/app/src/main/java/com/example/reply/ui/MainActivity.kt",
        line: 52,
        signals: [
          { kind: "COMPOSABLE_SYMBOL", value: "ReplyApp" },
        ],
      },
    ],
  };
  const result = evaluateSourceIndexCase({
    id: "reply-main-activity-owner",
    mode: "source-index",
    expectedEntryPathContains: "Reply/app/src/main/java/com/example/reply/ui/MainActivity.kt",
    expectedSignal: { kind: "COMPOSABLE_SYMBOL", value: "ReplyApp" },
  }, sourceIndex);
  assert.deepEqual(result.failures, []);
  assert.deepEqual(result.metrics, ["top1_hit", "top3_hit", "source_signal_present"]);
});

test("evaluateSourceIndexCase reports missing signal", () => {
  const result = evaluateSourceIndexCase({
    id: "missing",
    mode: "source-index",
    expectedEntryPathContains: "Nope.kt",
    expectedSignal: { kind: "COMPOSABLE_SYMBOL", value: "Missing" },
  }, { entries: [] });
  assert.deepEqual(result.failures, ["missing_top3", "missing_source_signal"]);
});

test("markdownReport summarizes status, fixtures, and case failures", () => {
  const text = markdownReport({
    schemaVersion: 1,
    status: "fail",
    fixtures: [{
      fixtureId: "reply",
      status: "evaluated",
      cases: [{
        caseId: "missing",
        metrics: [],
        failures: ["missing_top3"],
        environment: [],
      }],
    }],
  });
  assert.match(text, /# FixThis Source Matching Fixture Report/);
  assert.match(text, /Status: fail/);
  assert.match(text, /reply/);
  assert.match(text, /missing_top3/);
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: FAIL because `evaluateSourceIndexCase` and `markdownReport` are missing.

- [ ] **Step 3: Add source-index case evaluation**

Add these functions to `scripts/source-matching-fixtures.mjs`:

```js
export function evaluateSourceIndexCase(testCase, sourceIndex) {
  const entries = Array.isArray(sourceIndex?.entries) ? sourceIndex.entries : [];
  const pathNeedle = testCase.expectedEntryPathContains;
  const matchingEntries = entries.filter((entry) => typeof entry.file === "string" && entry.file.includes(pathNeedle));
  const observed = {
    candidates: matchingEntries.slice(0, 3).map((entry) => ({
      path: entry.file,
      line: entry.line || null,
      signals: entry.signals || [],
    })),
    warnings: [],
    environment: [],
  };
  const outcome = classifyCaseOutcome({
    expectedTop3PathContains: pathNeedle,
  }, observed);

  if (testCase.expectedSignal) {
    const foundSignal = matchingEntries.some((entry) =>
      (entry.signals || []).some((signal) =>
        signal.kind === testCase.expectedSignal.kind &&
        signal.value === testCase.expectedSignal.value,
      ),
    );
    if (foundSignal) outcome.metrics.push("source_signal_present");
    else outcome.failures.push("missing_source_signal");
  }
  return {
    caseId: testCase.id,
    mode: testCase.mode,
    metrics: outcome.metrics,
    failures: outcome.failures,
    environment: outcome.environment,
    observed: {
      candidates: observed.candidates,
    },
  };
}

export function evaluateFixtureSourceIndex(fixture, sourceIndex) {
  const cases = fixture.cases.map((testCase) => evaluateSourceIndexCase(testCase, sourceIndex));
  return {
    fixtureId: fixture.id,
    mode: "source-index",
    status: cases.some((testCase) => testCase.failures.length > 0) ? "fail" : "evaluated",
    sourceIndexSchemaVersion: sourceIndex.schemaVersion || null,
    cases,
  };
}
```

- [ ] **Step 4: Add report rendering**

Add:

```js
export function markdownReport(report) {
  const lines = [
    "# FixThis Source Matching Fixture Report",
    "",
    `Status: ${report.status}`,
    `Generated: ${report.generatedAt}`,
    "",
  ];
  for (const fixture of report.fixtures || []) {
    lines.push(`## ${fixture.fixtureId}`);
    lines.push("");
    lines.push(`- Status: ${fixture.status}`);
    if (fixture.sourceIndexSchemaVersion) {
      lines.push(`- Source index schema: ${fixture.sourceIndexSchemaVersion}`);
    }
    lines.push("");
    lines.push("| Case | Metrics | Failures | Environment |");
    lines.push("| --- | --- | --- | --- |");
    for (const testCase of fixture.cases || []) {
      lines.push([
        testCase.caseId,
        (testCase.metrics || []).join(", ") || "-",
        (testCase.failures || []).join(", ") || "-",
        (testCase.environment || []).join(", ") || "-",
      ].join(" | ").replace(/^/, "| ").replace(/$/, " |"));
    }
    lines.push("");
  }
  return `${lines.join("\n").trimEnd()}\n`;
}
```

- [ ] **Step 5: Add Gradle source-index execution**

Add:

```js
export function runSourceIndexEvaluation(fixture) {
  const paths = fixturePaths(fixture);
  if (!existsSync(paths.projectWorkDir)) {
    prepareFixture(fixture, { stdio: "inherit" });
  }
  const task = sourceIndexTaskPath(fixture);
  runCommand("./gradlew", [task, "--no-daemon"], {
    cwd: paths.projectWorkDir,
    stdio: "inherit",
  });
  const sourceIndexPath = generatedSourceIndexPath(paths.projectWorkDir, fixture);
  if (!existsSync(sourceIndexPath)) {
    return {
      fixtureId: fixture.id,
      mode: "source-index",
      status: "source_index_missing",
      cases: fixture.cases.map((testCase) => ({
        caseId: testCase.id,
        metrics: [],
        failures: ["source_index_missing"],
        environment: [],
      })),
    };
  }
  const sourceIndex = JSON.parse(readFileSync(sourceIndexPath, "utf8"));
  return evaluateFixtureSourceIndex(fixture, sourceIndex);
}

export function writeFixtureReport(fixtures) {
  const caseResults = fixtures.flatMap((fixture) => fixture.cases || []);
  const report = {
    schemaVersion: 1,
    generatedAt: new Date().toISOString(),
    status: reportStatus(caseResults),
    deviceBackedCapture: "not_configured",
    fixtures,
  };
  writeJson(join(defaultReportDir, "report.json"), report);
  writeFileSync(join(defaultReportDir, "report.md"), markdownReport(report));
  return report;
}
```

- [ ] **Step 6: Wire `run` and `report` commands**

Update `main` command handling:

```js
  if (command === "run") {
    const fixtures = [];
    for (const fixture of manifest.fixtures) {
      try {
        fixtures.push(runSourceIndexEvaluation(fixture));
      } catch (error) {
        fixtures.push({
          fixtureId: fixture.id,
          mode: "source-index",
          status: "fixture_build_failed",
          error: error.message,
          cases: fixture.cases.map((testCase) => ({
            caseId: testCase.id,
            metrics: [],
            failures: ["fixture_build_failed"],
            environment: [],
          })),
        });
      }
    }
    const report = writeFixtureReport(fixtures);
    console.log(`Source matching fixture report: ${report.status}`);
    return report.status === "fail" ? 1 : 0;
  }

  if (command === "report") {
    const reportPath = join(defaultReportDir, "report.json");
    if (!existsSync(reportPath)) {
      console.error("No source matching fixture report exists. Run `npm run source-matching:fixtures` first.");
      return 1;
    }
    const report = JSON.parse(readFileSync(reportPath, "utf8"));
    writeFileSync(join(defaultReportDir, "report.md"), markdownReport(report));
    console.log(readFileSync(join(defaultReportDir, "report.md"), "utf8"));
    return report.status === "fail" ? 1 : 0;
  }
```

Keep the existing `prepare` branch before these branches.

- [ ] **Step 7: Run tests**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: PASS.

- [ ] **Step 8: Run a local source-index fixture evaluation**

Run:

```bash
npm run source-matching:fixtures
```

Expected: The runner prepares fixtures if needed, runs each `generate<Variant>FixThisSourceIndex` Gradle task, writes `build/reports/fixthis-source-matching/report.json`, writes `build/reports/fixthis-source-matching/report.md`, and exits `0` if all source-index cases pass. If a fixture cannot build in the local environment, the report records `fixture_build_failed` with the error message.

- [ ] **Step 9: Commit Task 4**

Run:

```bash
git add scripts/source-matching-fixtures.mjs scripts/source-matching-fixtures-test.mjs
git commit -m "feat: evaluate local source matching fixture indexes"
```

---

### Task 5: Document Local Workflow And Final Verification

**Files:**
- Create: `docs/guides/source-matching-fixture-lab.md`
- Modify: `docs/index.md`

- [ ] **Step 1: Add documentation test first**

Append to `scripts/source-matching-fixtures-test.mjs`:

```js
test("docs explain that fixture lab is local-only and gitignored", () => {
  const guide = read("docs/guides/source-matching-fixture-lab.md");
  assert.match(guide, /local-only/i);
  assert.match(guide, /\.fixthis\/eval-fixtures\//);
  assert.match(guide, /build\/reports\/fixthis-source-matching\//);
  assert.match(guide, /not a release gate/i);
  assert.match(guide, /npm run source-matching:fixtures/);

  const index = read("docs/index.md");
  assert.match(index, /source-matching-fixture-lab\.md/);
});
```

- [ ] **Step 2: Run tests to verify docs are missing**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: FAIL because the guide and index link are missing.

- [ ] **Step 3: Create the local fixture lab guide**

Create `docs/guides/source-matching-fixture-lab.md`:

```markdown
# Source Matching Fixture Lab

The source matching fixture lab is a local-only developer tool for checking
whether FixThis source-index and source-hint changes remain trustworthy on
external Compose apps.

It is not a release gate, not a CI requirement, and not part of the public
install path.

## What It Uses

The fixture manifest pins official Google Android Compose sample repositories
by full commit SHA. The runner clones those repositories under
`.fixthis/eval-fixtures/`, prepares disposable working copies, applies the
current local FixThis Gradle plugin with `addDebugRuntime` disabled, and runs
source-index generation.

Local paths:

```text
.fixthis/eval-fixtures/repos/
.fixthis/eval-fixtures/work/
build/reports/fixthis-source-matching/
```

These paths are gitignored.

## Commands

Prepare fixtures:

```bash
npm run source-matching:fixtures:prepare
```

Run source-index evaluation:

```bash
npm run source-matching:fixtures
```

Print the latest Markdown report:

```bash
npm run source-matching:fixtures:report
```

Run fast offline tests for the runner:

```bash
npm run source-matching:fixtures:test
```

## Reading Results

The runner writes:

```text
build/reports/fixthis-source-matching/report.json
build/reports/fixthis-source-matching/report.md
```

Important failure labels:

- `missing_top3`: expected source entry did not appear in the evaluated source index.
- `missing_source_signal`: expected typed source signal was missing.
- `overconfident`: an observed confidence value was high where the manifest expected low or medium confidence.
- `fixture_build_failed`: the external fixture did not build in this local environment.
- `source_index_missing`: Gradle completed without producing the expected FixThis source index.

Device-backed capture is not enabled in the first local lab implementation.
Reports mark it as `not_configured`.
```

- [ ] **Step 4: Link the guide from `docs/index.md`**

Add this row under the Guides section:

```markdown
- [Source matching fixture lab](guides/source-matching-fixture-lab.md) — local-only external fixture evaluation for source-index trust checks.
```

Preserve the existing style in `docs/index.md`.

- [ ] **Step 5: Run focused tests**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: PASS.

- [ ] **Step 6: Run formatting and diff checks**

Run:

```bash
git diff --check
```

Expected: no output.

Run:

```bash
node --test scripts/source-matching-fixtures-test.mjs
```

Expected: PASS.

- [ ] **Step 7: Run optional local fixture evaluation**

Run:

```bash
npm run source-matching:fixtures
```

Expected: PASS if the local Android/Gradle environment can build all pinned fixtures. If a fixture fails because of local SDK/JDK/dependency state, inspect `build/reports/fixthis-source-matching/report.md` and confirm the failure is classified as `fixture_build_failed` rather than an unhandled script crash.

- [ ] **Step 8: Commit Task 5**

Run:

```bash
git add docs/guides/source-matching-fixture-lab.md docs/index.md scripts/source-matching-fixtures-test.mjs
git commit -m "docs: document local source matching fixture lab"
```

---

## Review Findings (2026-05-20)

A pre-implementation review of this plan against the FixThis Gradle plugin
source (`fixthis-gradle-plugin/src/main/kotlin/.../FixThisGradlePlugin.kt`,
`FixThisExtension.kt`, `task/GenerateFixThisSourceIndexTask.kt`,
`source/SourceIndexAssets.kt`) surfaced the defects below. Every defect maps
to a concrete corrective patch in the same task/step where the original
defect lives; treat the patches as authoritative when they conflict with the
inline snippets above. Severity classes: **B**lock (TDD red→green stalls
without it), **C**orrectness (silent wrong evidence), **R**obustness (works
on the happy path, breaks on a realistic edge case).

### F1 (B). `classifyCaseOutcome` test and implementation disagree

The Task 2 Step 1 test expects metrics `["top1_hit", "top3_hit",
"confidence_calibrated"]` when only `expectedTop3PathContains` is provided
and warnings is empty. The implementation in Task 2 Step 3 will instead
emit `["top3_hit", "confidence_calibrated", "warning_absent"]` because:

- `top1Needles` falls back only to `expectedEntryPathContains`, never to
  `expectedTop3PathContains`, so `top1_hit` is never pushed.
- The `mustNotWarn` loop unconditionally pushes `warning_absent` as a metric
  when the disallowed warning is absent.

Resolution. Replace the `classifyCaseOutcome` body in Task 2 Step 3 with the
version below. The behavior preserved by the test (`top1_hit` when the rank-1
candidate matches any path needle; `warning_absent` recorded only as a
non-failure outcome, not as a metric advertised on the success path) becomes
the contract:

```js
export function classifyCaseOutcome(expectation, observed) {
  const metrics = [];
  const failures = [];
  const candidates = observed.candidates || [];
  const warnings = new Set(observed.warnings || []);
  const top1Needles = arrayOf(
    expectation.expectedTop1PathContains
      || expectation.expectedEntryPathContains
      || expectation.expectedTop3PathContains,
  );
  const top3Needles = arrayOf(
    expectation.expectedTop3PathContains
      || expectation.expectedEntryPathContains,
  );

  if (top1Needles.length && candidates[0]?.path && top1Needles.some((needle) => candidates[0].path.includes(needle))) {
    metrics.push("top1_hit");
  } else if (expectation.expectedTop1PathContains) {
    failures.push("wrong_top1");
  }

  if (top3Needles.length && candidates.slice(0, 3).some((candidate) => top3Needles.some((needle) => candidate.path.includes(needle)))) {
    metrics.push("top3_hit");
  } else if (top3Needles.length) {
    failures.push("missing_top3");
  }

  if (expectation.expectedConfidence && observed.confidence) {
    if (confidenceMatches(expectation.expectedConfidence, observed.confidence)) {
      metrics.push("confidence_calibrated");
    } else if (observed.confidence === "high" && expectation.expectedConfidence === "low-or-medium") {
      failures.push("overconfident");
    }
  }

  for (const warning of expectation.mustWarn || []) {
    if (warnings.has(warning)) metrics.push("warning_present");
    else failures.push("missing_warning");
  }
  for (const warning of expectation.mustNotWarn || []) {
    if (warnings.has(warning)) failures.push("unexpected_warning");
  }

  return { metrics, failures, environment: observed.environment || [] };
}
```

### F2 (C). Initial manifest cases do not exercise trust calibration

The design (`docs/superpowers/specs/...-design.md` §Product Goal and
§Metrics) makes overclaim prevention and confidence calibration the primary
trust signals, yet every committed case in Task 1 Step 5 declares only
`expectedEntryPathContains` plus `expectedSignal`. The lab therefore reports
`top1_hit`/`top3_hit` only and cannot fail an overconfident regression on
day one. Self-Review Checklist line "Reports treat overconfidence as a
first-class failure whenever confidence evidence is supplied" is satisfied
vacuously because no case supplies confidence evidence.

Resolution. When applying Task 1 Step 5, attach at least one
`expectedConfidence` (and at least one warning expectation across the
fixture set) to ground the calibration claim in committed evidence. The
augmented case suggestions below should replace the original case blocks
inline. Path needles still need verification against the pinned SHA — see
F3.

```json
{
  "id": "reply-main-activity-owner",
  "mode": "source-index",
  "expectedEntryPathContains": "Reply/app/src/main/java/com/example/reply/ui/MainActivity.kt",
  "expectedConfidence": "medium-or-high",
  "expectedSignal": { "kind": "COMPOSABLE_SYMBOL", "value": "ReplyApp" }
},
{
  "id": "reply-list-content-owner",
  "mode": "source-index",
  "expectedEntryPathContains": "Reply/app/src/main/java/com/example/reply/ui/ReplyListContent.kt",
  "expectedConfidence": "medium-or-high",
  "expectedSignal": { "kind": "COMPOSABLE_SYMBOL", "value": "ReplyEmailList" }
}
```

For Jetsnack, the ambiguous-string case should explicitly demand a cautious
confidence band so a future matcher that promotes literal-string matches
to `high` is caught as `overconfident`:

```json
{
  "id": "jetsnack-search-category-copy",
  "mode": "source-index",
  "expectedEntryPathContains": "Jetsnack/app/src/main/java/com/example/jetsnack/model/Search.kt",
  "expectedConfidence": "low-or-medium",
  "expectedSignal": { "kind": "ARBITRARY_STRING_LITERAL", "value": "Desserts" }
}
```

For Now in Android, keep at least one case that pins a warning the matcher
must not raise on a confidently identified Compose owner so view-interop
overflagging shows up:

```json
{
  "id": "nia-navigation-component-owner",
  "mode": "source-index",
  "expectedEntryPathContains": "core/designsystem/src/main/kotlin/com/google/samples/apps/nowinandroid/core/designsystem/component/NiaNavigation.kt",
  "expectedConfidence": "medium-or-high",
  "mustNotWarn": ["POSSIBLE_VIEW_INTEROP"],
  "expectedSignal": { "kind": "COMPOSABLE_SYMBOL", "value": "NiaNavigationBar" }
}
```

### F3 (C). Pinned commits and expected paths are placeholders

The SHAs `d3ff757b289f7036815978a8f7b16706ee3423b0` (compose-samples) and
`7d45eae4f8720a0c77f507712ba2437ff974b6ed` (nowinandroid) are placeholders;
the plan does not show how they were resolved against upstream. Several
expected file paths are unverified and at least one is likely wrong:

- Now in Android does not generally publish per-feature `api` source sets
  with `res/values/strings.xml`. The string `For you` lives in
  `feature/foryou/src/main/res/values/strings.xml` in the contemporary
  layout. The `feature/foryou/api/src/main/res/values/strings.xml` path the
  plan asserts will return `missing_top3` against current `main`.
- The NiA `Navigation.kt` filename is `NiaNavigation.kt` in recent commits
  (`core/designsystem/.../component/NiaNavigation.kt`), not `Navigation.kt`.
- Reply's `applicationId` is `com.example.reply.debug` for the debug
  variant when `applicationIdSuffix = ".debug"` is set in the upstream
  build. The manifest currently records the base id; either the field needs
  the suffix or the schema needs to make explicit that `applicationId` is
  the base id.

Resolution. Insert a **Task 0: Pin commits and paths against upstream**
before Task 1. Task 0 is non-coding work that produces the manifest input
for Task 1 Step 5 and the path needles consumed by Tasks 4–5:

- [ ] **Step 1:** For each upstream repo, resolve the head commit on the
  branch the lab will track (`main` for `android/compose-samples` and
  `main` for `android/nowinandroid`) via
  `gh api repos/android/compose-samples/commits/main --jq .sha` (and the
  equivalent for nowinandroid). Record the SHA, the resolution timestamp,
  and the human checking the SHA.
- [ ] **Step 2:** Using the resolved SHAs, walk each fixture's tree
  (`gh api repos/<org>/<repo>/git/trees/<sha>?recursive=1 --jq '.tree[].path'`)
  and confirm every `expectedEntryPathContains` exists in the tree exactly
  once. Reject any case whose path appears zero times or more than once
  without a more specific needle.
- [ ] **Step 3:** Confirm the variant resolves to the expected
  `applicationId` by inspecting the upstream `applicationIdSuffix` and
  `flavorDimensions` (the Reply and NiA cases above are flagged today).
- [ ] **Step 4:** Replace the placeholder commits and paths in the Task 1
  Step 5 manifest snippet with the verified values, and quote the
  verification commands in the implementer commit message so reviewers can
  re-run them.

Document this in the local fixture lab guide as the "Re-pinning" workflow,
referencing this task.

### F4 (R). `runCommand` error string renders `null` under inherited stdio

The Task 3 Step 4 `runCommand` helper builds its error message from
`result.stdout` and `result.stderr`. Both are `null` when the caller passes
`stdio: "inherit"` (which is the normal mode for prepare/run, see Step 5
and Task 4 Step 5). The current `[result.stdout, result.stderr].filter(Boolean).join("\n").trim()`
filters `null` but the typeof check is wasted because the actual values
are always falsy when inherited.

Resolution. Defensive coercion plus an explicit hint when no captured
output exists, so the operator sees the failed argv even when stdio was
inherited:

```js
export function runCommand(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd || repoRoot,
    encoding: "utf8",
    stdio: options.stdio || "pipe",
    env: { ...process.env, ...(options.env || {}) },
  });
  if (result.error) {
    throw new Error(`${command} ${args.join(" ")} failed to spawn: ${result.error.message}`);
  }
  if (result.status !== 0) {
    const stdout = typeof result.stdout === "string" ? result.stdout : "";
    const stderr = typeof result.stderr === "string" ? result.stderr : "";
    const detail = [stdout, stderr].join("\n").trim();
    const suffix = detail
      ? `\n${detail}`
      : "\n(no captured output; rerun with stdio: 'pipe' to capture details)";
    throw new Error(`${command} ${args.join(" ")} exited ${result.status}${suffix}`);
  }
  return result;
}
```

### F5 (R). CLI main guard breaks on realpath and Windows

`import.meta.url === \`file://${process.argv[1]}\`` fails when the script
is invoked through a symlink (Homebrew/asdf node), through `npm exec`'s
`.bin` shim, or on Windows (paths use drive letters and the `file://`
encoding differs). The result is the runner silently exits without doing
anything when run via `npm run`.

Resolution. Update the main guard at the bottom of
`scripts/source-matching-fixtures.mjs` to compare resolved paths:

```js
import { realpathSync } from "node:fs";

const invokedAsCli = process.argv[1]
  ? fileURLToPath(import.meta.url) === realpathSync(process.argv[1])
  : false;
if (invokedAsCli) {
  const status = await main();
  process.exitCode = status;
}
```

### F6 (R). Fixture clone cache redundantly clones the same upstream

`fixturePaths` keys the on-disk clone directory by `fixture.id`. Reply and
Jetsnack both originate from `android/compose-samples`, so the runner
clones the same upstream twice (extra ~minutes, extra disk) and any
re-pinning step must update two checkouts in lockstep.

Resolution. Hash the repo URL and key the clone by the hash; keep
`workDir` keyed by fixture id since each fixture mutates Gradle files
independently. Replace `fixturePaths` in Task 3 Step 3:

```js
import { createHash } from "node:crypto";

export const fixtureRepoRoot = join(fixtureRoot, "repos");
export const fixtureWorkRoot = join(fixtureRoot, "work");

export function repoCacheKey(repoUrl) {
  return createHash("sha1").update(repoUrl).digest("hex").slice(0, 12);
}

export function fixturePaths(fixture) {
  const repoDir = join(fixtureRepoRoot, `${repoCacheKey(fixture.repo)}`);
  const workDir = join(fixtureWorkRoot, fixture.id);
  const projectWorkDir = fixture.projectDir === "."
    ? workDir
    : join(workDir, safeRelativePath(fixture.projectDir, `${fixture.id} projectDir`));
  return { repoDir, workDir, projectWorkDir };
}
```

Update the matching test in Task 3 Step 1 so it asserts the repos directory
ends with a hex hash rather than `reply`, e.g. `/repos\/[a-f0-9]{12}$/`.

### F7 (R). Patcher assumes `build.gradle.kts`

`patchAppBuildFileText` is fed via `readFileSync(appBuildPath, "utf8")`
where `appBuildPath` is hard-coded to `build.gradle.kts`. The Kotlin DSL
pattern matchers (`plugins\s*\{`, the `id("...")` line) silently corrupt
the file if a Groovy DSL fixture is added later, and `readFileSync` throws
an `ENOENT` with no explanatory wrapper today.

Resolution. Detect the build file shape and fail fast with a typed error
in `prepareFixture` (Task 3 Step 4):

```js
const moduleRel = fixture.modulePath.replace(/^:/, "").replaceAll(":", "/");
const kotlinBuild = join(paths.projectWorkDir, moduleRel, "build.gradle.kts");
const groovyBuild = join(paths.projectWorkDir, moduleRel, "build.gradle");
if (!existsSync(kotlinBuild)) {
  if (existsSync(groovyBuild)) {
    throw new Error(`${fixture.id}: Groovy build.gradle is not supported by this lab. Pin a Kotlin DSL sample or add a Groovy patcher.`);
  }
  throw new Error(`${fixture.id}: expected ${moduleRel}/build.gradle.kts in fixture working copy`);
}
const appBuildPath = kotlinBuild;
```

### F8 (C). Cross-module source-index aggregation is undocumented for NiA

`FixThisGradlePlugin.kt:71-114` aggregates source from the `:app` module's
resolved project dependencies. The Now in Android case
`nia-for-you-title-resource` only succeeds because `:app` depends on the
`:feature:foryou` module (directly or transitively), and the source-index
task picks up `feature/foryou/src/main/res/values/strings.xml` through that
dependency walk. The current plan does not state this requirement; if a
future NiA refactor moves the For You feature behind a runtime contract
without a compile-time dependency, the case will fail with
`missing_top3` and the failure mode will be unclear from the report alone.

Resolution. Append a short paragraph to `docs/guides/source-matching-fixture-lab.md`
(Task 5 Step 3, after the Reading Results block) explaining that
multi-module fixtures rely on the `:app` module's project dependencies and
that a missing cross-module hit may indicate either a regression in
matching or a real upstream graph change.

### F9 (R). `cpSync` filter receives absolute paths and may miss nested copies

The `cpSync` filter in Task 3 Step 4 splits on `sep` and rejects any path
segment named `.git`/`.gradle`/`build`. This works on macOS/Linux because
`sep` is `/`, but the filter receives the **source** path, not the path
relative to `sourceDir`. As a result, if any ancestor directory contains
`build` in its name (e.g., `~/source/android/FixThis/.fixthis/eval-fixtures/repos/.../build/...`),
the filter rejects everything below it. The current path
`.fixthis/eval-fixtures/repos/<hash>` contains no such ancestor in
practice, but the filter should still operate on the segment under
`sourceDir`.

Resolution. Update the filter to compute the relative segment first:

```js
cpSync(sourceDir, paths.projectWorkDir, {
  recursive: true,
  filter: (source) => {
    const rel = relative(sourceDir, source);
    if (!rel) return true; // root
    return !rel.split(sep).some((part) => part === ".git" || part === ".gradle" || part === "build");
  },
});
```

### F10 (R). Manifest `applicationId` semantics are implicit

The validator requires `applicationId` to be present but no runner code
reads it; only the docstring (in the design) explains that it is the
post-suffix variant id. Reply's debug variant id may include
`.debug`, NiA's is `com.google.samples.apps.nowinandroid.demo.debug`. The
field is harmless today but will be a trap once device-backed capture
lands (handoff evidence keyed by app id).

Resolution. Add a contract test that the `applicationId` ends with the
build-type / flavor suffixes implied by the variant, **or** rename the
field to `expectedDebugApplicationId` and document it as captured-but-not-
asserted in v1. The smaller change is to update the design doc and add a
comment in the manifest snippet noting the field is informational in v1.

### F11 (R). `git fetch origin <sha> --depth=1` after `--filter=blob:none`

`git clone --filter=blob:none` already creates a partial clone with all
refs; the subsequent shallow fetch of a specific SHA is redundant and, on
hosts without `uploadpack.allowReachableSHA1InWant`, will fail with a
confusing `Server does not allow request for unadvertised object` error.
GitHub does allow it for the two upstreams here, but the helper should
degrade gracefully.

Resolution. Prefer the simpler pair (idempotent across reruns):

```js
if (!existsSync(paths.repoDir)) {
  runCommand("git", ["clone", "--filter=blob:none", "--no-checkout", fixture.repo, paths.repoDir], { stdio: options.stdio || "pipe" });
}
runCommand("git", ["fetch", "--filter=blob:none", "origin", fixture.commit], { cwd: paths.repoDir, stdio: options.stdio || "pipe" });
runCommand("git", ["checkout", "--detach", fixture.commit], { cwd: paths.repoDir, stdio: options.stdio || "pipe" });
```

This omits `--depth=1` (which the partial clone already approximates for
blobs) so the SHA-by-SHA fetch is always reachable from refs the
provider advertises.

### Cross-cutting follow-ups

- Add one negative path test for `validateManifest` covering
  `expectedConfidence: "wrong"` so the validator's confidence-band guard is
  exercised by the test suite.
- After Task 0 and the manifest patch in F2/F3 land, the Task 4 Step 8
  smoke command should be re-run end-to-end before declaring Task 4 done;
  the implementer should attach `report.md` to the PR description so the
  initial calibration baseline is reviewable.
- The Self-Review Checklist line "Reports treat overconfidence as a
  first-class failure whenever confidence evidence is supplied" should be
  upgraded to "...is supplied by at least one case per fixture" to lock the
  F2 fix into the acceptance loop.

---

## Final Verification

Run:

```bash
npm run source-matching:fixtures:test
git diff --check
```

Expected:

- `npm run source-matching:fixtures:test` passes.
- `git diff --check` prints no whitespace errors.

Then run the local evidence command when network and Gradle are available:

```bash
npm run source-matching:fixtures
```

Expected:

- `build/reports/fixthis-source-matching/report.json` exists.
- `build/reports/fixthis-source-matching/report.md` exists.
- Fixture source remains under `.fixthis/eval-fixtures/` and is not shown by `git status --short`.

If `npm run source-matching:fixtures` cannot build an external fixture, record the failure classification from the report in the implementation summary instead of hiding it.

## Self-Review Checklist

- Every spec non-goal is preserved: no vendored sample source, no CI gate, no release claim, no persisted MCP JSON rename, no broad matcher scoring rewrite.
- Every committed path is small and intentional.
- The runner tests avoid network and Android device dependencies.
- The local runner uses pinned full SHAs only.
- Source-index-only evaluation works without a device.
- Device-backed capture is explicitly marked `not_configured`, not silently implied.
- Reports treat overconfidence as a first-class failure whenever confidence evidence is supplied.
