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
const trustObservationNotConfigured = "trust_observation_not_configured";
const targetWarnings = new Set([
  "VISUAL_AREA_ONLY",
  "NO_MEANINGFUL_COMPOSE_TARGET",
  "POSSIBLE_VIEW_INTEROP",
  "LOW_SOURCE_CANDIDATE_MARGIN",
  "SOURCE_INDEX_STALE",
  "SCREEN_FINGERPRINT_MISMATCH_FORCED",
  "SCREEN_FINGERPRINT_UNAVAILABLE",
  "SENSITIVE_TEXT_REDACTED",
]);
const sourceRiskFlags = new Set([
  "AMBIGUOUS",
  "AREA_SELECTION",
  "TEXT_ONLY",
  "NEARBY_ONLY",
  "ARBITRARY_LITERAL",
  "ACTIVITY_ONLY",
  "LEGACY_FALLBACK",
  "UNTYPED_FALLBACK",
]);

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
      validateAllowedValues(entry.mustWarn, targetWarnings, `${entry.id || "case"} mustWarn`, errors);
      validateAllowedValues(entry.mustNotWarn, targetWarnings, `${entry.id || "case"} mustNotWarn`, errors);
      validateAllowedValues(entry.expectedRiskFlags, sourceRiskFlags, `${entry.id || "case"} expectedRiskFlags`, errors);
      if (entry.mustNotHighConfidence !== undefined && typeof entry.mustNotHighConfidence !== "boolean") {
        errors.push(`${entry.id || "case"} mustNotHighConfidence must be boolean`);
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
  const environment = [...(observed.environment || [])];
  const candidates = observed.candidates || [];
  const hasConfidenceObservation = typeof observed.confidence === "string" && observed.confidence.length > 0;
  const hasWarningObservation = hasOwn(observed, "warnings");
  const hasRiskObservation = hasOwn(observed, "riskFlags");
  const warnings = new Set(hasWarningObservation ? observed.warnings || [] : []);
  const riskFlags = new Set(hasRiskObservation ? observed.riskFlags || [] : []);
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

  if (expectation.expectedConfidence && hasConfidenceObservation) {
    if (confidenceMatches(expectation.expectedConfidence, observed.confidence)) {
      metrics.push("confidence_calibrated");
    } else if (observed.confidence === "high" && ["low-or-medium", "low", "unknown"].includes(expectation.expectedConfidence)) {
      failures.push("overconfident");
    } else if (observed.confidence === "low" && expectation.expectedConfidence === "medium-or-high") {
      failures.push("underconfident");
    }
  } else if (expectation.expectedConfidence) {
    addUnique(environment, trustObservationNotConfigured);
  }

  const expectedRiskFlags = expectation.expectedRiskFlags || [];
  if (expectedRiskFlags.length > 0 && !hasRiskObservation) {
    addUnique(environment, trustObservationNotConfigured);
  } else {
    for (const riskFlag of expectedRiskFlags) {
      if (riskFlags.has(riskFlag)) metrics.push("risk_flag_present");
      else failures.push("missing_risk_flag");
    }
  }

  const requiredWarnings = expectation.mustWarn || [];
  const forbiddenWarnings = expectation.mustNotWarn || [];
  if ((requiredWarnings.length > 0 || forbiddenWarnings.length > 0) && !hasWarningObservation) {
    addUnique(environment, trustObservationNotConfigured);
  } else {
    for (const warning of requiredWarnings) {
      if (warnings.has(warning)) metrics.push("warning_present");
      else failures.push("missing_warning");
    }
    for (const warning of forbiddenWarnings) {
      if (warnings.has(warning)) failures.push("unexpected_warning");
    }
  }

  if (expectation.mustNotHighConfidence === true) {
    if (observed.confidence === "high") {
      failures.push("unexpected_high_confidence");
      if (riskFlags.size > 0 || warnings.size > 0) {
        failures.push("weak_evidence_promoted");
      }
    } else if (hasConfidenceObservation) {
      metrics.push("high_confidence_avoided");
    } else {
      addUnique(environment, trustObservationNotConfigured);
    }
  }

  return { metrics, failures, environment };
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

function hasOwn(value, key) {
  return Object.prototype.hasOwnProperty.call(value || {}, key);
}

function addUnique(values, value) {
  if (!values.includes(value)) values.push(value);
}

function validateAllowedValues(values, allowed, fieldName, errors) {
  for (const value of arrayOf(values)) {
    if (!allowed.has(value)) {
      errors.push(`${fieldName} contains unsupported value ${value}`);
    }
  }
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

export function evaluateSourceIndexCase(testCase, sourceIndex) {
  const entries = Array.isArray(sourceIndex?.entries) ? sourceIndex.entries : [];
  const pathNeedles = [
    ...arrayOf(testCase.expectedEntryPathContains),
    ...arrayOf(testCase.expectedTop3PathContains),
  ];
  const matchingEntries = entries.filter((entry) =>
    typeof entry.file === "string" &&
    pathNeedles.some((needle) => entry.file.includes(needle)),
  );
  const observed = {
    candidates: matchingEntries.slice(0, 3).map((entry) => ({
      path: entry.file,
      line: entry.line || null,
      signals: entry.signals || [],
    })),
    environment: [],
  };
  const outcome = classifyCaseOutcome(testCase, observed);

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

function countLabels(cases, fieldName) {
  return cases
    .flatMap((testCase) => testCase[fieldName] || [])
    .reduce((counts, label) => {
      counts[label] = (counts[label] || 0) + 1;
      return counts;
    }, {});
}

export function buildFixtureReport(fixtures, generatedAt = new Date().toISOString()) {
  const caseResults = fixtures.flatMap((fixture) => fixture.cases || []);
  return {
    schemaVersion: 1,
    generatedAt,
    status: reportStatus(caseResults),
    deviceBackedCapture: "not_configured",
    summary: {
      totalCases: caseResults.length,
      failedCases: caseResults.filter((testCase) => (testCase.failures || []).length > 0).length,
      environmentCases: caseResults.filter((testCase) => (testCase.environment || []).length > 0).length,
      failureCounts: countLabels(caseResults, "failures"),
      environmentCounts: countLabels(caseResults, "environment"),
    },
    fixtures,
  };
}

export function markdownReport(report) {
  const lines = [
    "# FixThis Source Matching Fixture Report",
    "",
    `Status: ${report.status}`,
    `Generated: ${report.generatedAt}`,
    "",
  ];
  if (report.summary) {
    lines.push("## Summary");
    lines.push("");
    lines.push(`- Total cases: ${report.summary.totalCases}`);
    lines.push(`- Failed cases: ${report.summary.failedCases}`);
    lines.push(`- Environment downgrade cases: ${report.summary.environmentCases}`);
    lines.push(`- Failure counts: ${formatCounts(report.summary.failureCounts)}`);
    lines.push(`- Environment counts: ${formatCounts(report.summary.environmentCounts)}`);
    lines.push("");
  }
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

function formatCounts(counts) {
  const entries = Object.entries(counts || {});
  return entries.length === 0
    ? "-"
    : entries.map(([label, count]) => `${label}=${count}`).join(", ");
}

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
  const report = buildFixtureReport(fixtures);
  writeJson(join(defaultReportDir, "report.json"), report);
  writeFileSync(join(defaultReportDir, "report.md"), markdownReport(report));
  return report;
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
    const failed = [];
    for (const fixture of manifest.fixtures) {
      try {
        const paths = prepareFixture(fixture, { stdio: "inherit" });
        prepared.push({
          fixtureId: fixture.id,
          repoDir: relative(repoRoot, paths.repoDir),
          workDir: relative(repoRoot, paths.workDir),
        });
      } catch (error) {
        failed.push({ fixtureId: fixture.id, error: error.message });
      }
    }
    writeJson(join(defaultReportDir, "prepare.json"), {
      schemaVersion: 1,
      status: failed.length === 0 ? "prepared" : "partial",
      prepared,
      ...(failed.length > 0 ? { failed } : {}),
    });
    return failed.length === 0 ? 0 : 1;
  }
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
