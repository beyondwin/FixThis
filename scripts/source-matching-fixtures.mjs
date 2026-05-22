import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { cpSync, existsSync, mkdirSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join, normalize, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";

export const repoRoot = fileURLToPath(new URL("..", import.meta.url));
export const defaultManifestPath = join(repoRoot, "fixtures/source-matching/manifest.json");
export const defaultReportDir = join(repoRoot, "build/reports/fixthis-source-matching");
export const fixtureRoot = join(repoRoot, ".fixthis/eval-fixtures");
export const fixtureRepoRoot = join(fixtureRoot, "repos");
export const fixtureWorkRoot = join(fixtureRoot, "work");

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

// F1-corrected: top1Needles falls back to expectedTop3PathContains; warning_absent NOT pushed as metric.
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

export function prepareFixture(fixture, options = {}) {
  const paths = fixturePaths(fixture);
  ensureDir(fixtureRepoRoot);
  ensureDir(fixtureWorkRoot);

  if (!existsSync(paths.repoDir)) {
    runCommand("git", ["clone", "--filter=blob:none", "--no-checkout", fixture.repo, paths.repoDir], { stdio: options.stdio || "pipe" });
  }
  runCommand("git", ["fetch", "--filter=blob:none", "origin", fixture.commit], { cwd: paths.repoDir, stdio: options.stdio || "pipe" });
  runCommand("git", ["checkout", "--detach", fixture.commit], { cwd: paths.repoDir, stdio: options.stdio || "pipe" });

  rmSync(paths.workDir, { recursive: true, force: true });
  ensureDir(paths.workDir);
  const sourceDir = fixture.projectDir === "." ? paths.repoDir : join(paths.repoDir, safeRelativePath(fixture.projectDir, `${fixture.id} projectDir`));

  cpSync(sourceDir, paths.projectWorkDir, {
    recursive: true,
    filter: (source) => {
      const rel = relative(sourceDir, source);
      if (!rel) return true;
      return !rel.split(sep).some((part) => part === ".git" || part === ".gradle" || part === "build");
    },
  });

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

  const settingsPath = join(paths.projectWorkDir, "settings.gradle.kts");
  writeFileSync(
    settingsPath,
    patchSettingsText(readFileSync(settingsPath, "utf8"), join(repoRoot, "fixthis-gradle-plugin")),
  );
  writeFileSync(appBuildPath, patchAppBuildFileText(readFileSync(appBuildPath, "utf8")));

  return paths;
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

// F5-corrected: realpathSync comparison so symlink/npm-exec/Windows invocations work.
const invokedAsCli = process.argv[1]
  ? fileURLToPath(import.meta.url) === realpathSync(process.argv[1])
  : false;
if (invokedAsCli) {
  const status = await main();
  process.exitCode = status;
}
