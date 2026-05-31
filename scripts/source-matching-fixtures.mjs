import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { cpSync, existsSync, mkdirSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join, normalize, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { resolveAndroidEnvironment } from "./evidence-runner.mjs";

export const repoRoot = fileURLToPath(new URL("..", import.meta.url));
export const defaultManifestPath = join(repoRoot, "fixtures/source-matching/manifest.json");
export const defaultReportDir = join(repoRoot, "build/reports/fixthis-source-matching");
export const fixtureRoot = join(repoRoot, ".fixthis/eval-fixtures");
export const fixtureRepoRoot = join(fixtureRoot, "repos");
export const fixtureWorkRoot = join(fixtureRoot, "work");

const fullShaPattern = /^[a-f0-9]{40}$/;
const confidenceRank = new Map([
  ["unknown", 0],
  ["low", 1],
  ["medium", 2],
  ["high", 3],
]);
const confidenceExpectations = new Set(["high", "medium-or-high", "low-or-medium", "low", "unknown"]);
const trustObservationNotConfigured = "trust_observation_not_configured";
const manifestSchemaVersion = 2;
const externalFixtureSource = "external-github";
const localFixtureSource = "local-project";
const fixtureSources = new Set([externalFixtureSource, localFixtureSource]);
const sourceIndexCaseFields = new Set([
  "id",
  "mode",
  "expectedEntryPathContains",
  "expectedTop1PathContains",
  "expectedTop3PathContains",
  "expectedSignal",
]);
const runtimeOnlyCaseFields = new Set([
  "runtimeTarget",
  "navigation",
  "trustPurpose",
  "expectedConfidence",
  "expectedSourceConfidence",
  "expectedRiskFlags",
  "expectedRecommendedEditSiteContains",
  "expectedBoundaryContextKinds",
  "mustWarn",
  "mustNotWarn",
  "mustNotHighConfidence",
]);
const runtimeTrustCaseFields = new Set([
  "id",
  "mode",
  ...runtimeOnlyCaseFields,
  "expectedTop1PathContains",
  "expectedTop3PathContains",
  "navigateBefore",
]);
const runtimeTargetFields = new Set(["text", "testTag", "contentDescription", "role", "visualArea"]);
const runtimeNavigationTargetFields = new Set(["text", "testTag", "contentDescription", "role"]);
const visualAreaFields = new Set(["left", "top", "right", "bottom"]);
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
  const moduleDir = fixture.moduleDir
    ? safeRelativePath(fixture.moduleDir, `${fixture.id || "fixture"} moduleDir`)
    : fixture.modulePath.replace(/^:/, "").replaceAll(":", "/");
  return join(projectRoot, moduleDir, "build/generated/fixthis", fixture.variant, "assets/fixthis/fixthis-source-index.json");
}

export function loadManifest(path = defaultManifestPath) {
  return validateManifest(JSON.parse(readFileSync(path, "utf8")));
}

export function validateManifest(manifest) {
  const errors = [];
  if (!manifest || typeof manifest !== "object") errors.push("manifest must be an object");
  if (manifest?.schemaVersion !== manifestSchemaVersion) errors.push(`schemaVersion must be ${manifestSchemaVersion}`);
  if (!Array.isArray(manifest?.fixtures) || manifest.fixtures.length === 0) {
    errors.push("fixtures must contain at least one fixture");
  }
  for (const fixture of manifest?.fixtures || []) {
    if (!fixture.id || !/^[a-z0-9-]+$/.test(fixture.id)) errors.push("fixture id must use lowercase slug syntax");
    const source = fixture.source || externalFixtureSource;
    if (!fixtureSources.has(source)) {
      errors.push(`${fixture.id || "fixture"} source must be external-github or local-project`);
    }
    if (source === externalFixtureSource) {
      if (!fixture.repo || !fixture.repo.startsWith("https://github.com/android/")) errors.push(`${fixture.id || "fixture"} repo must be an Android HTTPS GitHub URL`);
      if (!fullShaPattern.test(fixture.commit || "")) errors.push(`${fixture.id || "fixture"} commit must be a 40-character SHA`);
    } else {
      if (fixture.repo !== undefined) errors.push(`${fixture.id || "fixture"} local-project fixture must not define repo`);
      if (fixture.commit !== undefined) errors.push(`${fixture.id || "fixture"} local-project fixture must not define commit`);
    }
    try { safeRelativePath(fixture.projectDir, `${fixture.id || "fixture"} projectDir`); } catch (error) { errors.push(error.message); }
    if (fixture.moduleDir !== undefined) {
      try { safeRelativePath(fixture.moduleDir, `${fixture.id || "fixture"} moduleDir`); } catch (error) { errors.push(error.message); }
    }
    if (!fixture.modulePath || !fixture.modulePath.startsWith(":")) errors.push(`${fixture.id || "fixture"} modulePath must start with :`);
    if (!fixture.variant) errors.push(`${fixture.id || "fixture"} variant must be present`);
    if (!fixture.applicationId) errors.push(`${fixture.id || "fixture"} applicationId must be present`);
    if (!Array.isArray(fixture.cases) || fixture.cases.length === 0) errors.push(`${fixture.id || "fixture"} cases must contain at least one case`);
    for (const entry of fixture.cases || []) {
      if (!entry.id || !/^[a-z0-9-]+$/.test(entry.id)) errors.push(`${fixture.id || "fixture"} case id must use lowercase slug syntax`);
      if (!["source-index", "runtime-trust"].includes(entry.mode)) {
        errors.push(`${entry.id || "case"} mode must be source-index or runtime-trust`);
      }
      if (entry.mode === "source-index") {
        for (const field of Object.keys(entry)) {
          if (runtimeOnlyCaseFields.has(field)) {
            errors.push(`${entry.id || "case"} source-index case contains runtime-only field ${field}`);
          } else if (!sourceIndexCaseFields.has(field)) {
            errors.push(`${entry.id || "case"} source-index case contains unsupported field ${field}`);
          }
        }
        if (!entry.expectedEntryPathContains && !entry.expectedTop1PathContains && !entry.expectedTop3PathContains) {
          errors.push(`${entry.id || "case"} must define expectedEntryPathContains, expectedTop1PathContains, or expectedTop3PathContains`);
        }
        if (entry.expectedSignal) {
          if (!entry.expectedSignal.kind || !entry.expectedSignal.value) {
            errors.push(`${entry.id || "case"} expectedSignal must include kind and value`);
          }
        }
      }
      if (entry.mode === "runtime-trust") {
        for (const field of Object.keys(entry)) {
          if (!runtimeTrustCaseFields.has(field)) {
            errors.push(`${entry.id || "case"} runtime-trust case contains unsupported field ${field}`);
          }
        }
        if (typeof entry.trustPurpose !== "string" || entry.trustPurpose.length === 0) {
          errors.push(`${entry.id || "case"} runtime-trust case must define trustPurpose`);
        }
        validateRuntimeTarget(entry.runtimeTarget, entry.id || "case", errors);
        if (entry.navigateBefore !== undefined) {
          validateRuntimeTarget(entry.navigateBefore, `${entry.id || "case"} navigateBefore`, errors, { allowVisualArea: false });
        }
        if (entry.expectedConfidence && !confidenceExpectations.has(entry.expectedConfidence)) {
          errors.push(`${entry.id} expectedConfidence is unsupported`);
        }
        if (entry.expectedSourceConfidence && !confidenceExpectations.has(entry.expectedSourceConfidence)) {
          errors.push(`${entry.id} expectedSourceConfidence is unsupported`);
        }
        validateAllowedValues(entry.expectedRiskFlags, sourceRiskFlags, `${entry.id || "case"} expectedRiskFlags`, errors);
        validateAllowedValues(entry.mustWarn, targetWarnings, `${entry.id || "case"} mustWarn`, errors);
        validateAllowedValues(entry.mustNotWarn, targetWarnings, `${entry.id || "case"} mustNotWarn`, errors);
        validateAllowedValues(
          entry.expectedBoundaryContextKinds,
          new Set(["host", "ancestor", "context"]),
          `${entry.id || "case"} expectedBoundaryContextKinds`,
          errors,
        );
        if (entry.mustNotHighConfidence !== undefined && typeof entry.mustNotHighConfidence !== "boolean") {
          errors.push(`${entry.id || "case"} mustNotHighConfidence must be boolean`);
        }
        if (entry.expectedRecommendedEditSiteContains !== undefined
          && (typeof entry.expectedRecommendedEditSiteContains !== "string"
            || entry.expectedRecommendedEditSiteContains.length === 0)) {
          errors.push(`${entry.id || "case"} expectedRecommendedEditSiteContains must be a non-empty string`);
        }
      }
    }
  }
  if (errors.length) {
    throw new Error(errors.join("; "));
  }
  return manifest;
}

function validateRuntimeTarget(target, label, errors, options = {}) {
  const allowVisualArea = options.allowVisualArea !== false;
  const allowedFields = allowVisualArea ? runtimeTargetFields : runtimeNavigationTargetFields;
  const allowedDescription = allowVisualArea
    ? "text, testTag, contentDescription, role, or visualArea"
    : "text, testTag, contentDescription, or role";
  if (!target || typeof target !== "object" || Array.isArray(target)) {
    errors.push(`${label} runtime-trust case must define runtimeTarget`);
    return;
  }
  const keys = Object.keys(target);
  const supported = keys.filter((key) => allowedFields.has(key));
  if (supported.length === 0) {
    errors.push(`${label} runtimeTarget must include ${allowedDescription}`);
  }
  for (const key of keys) {
    if (!allowedFields.has(key)) {
      errors.push(`${label} runtimeTarget contains unsupported field ${key}`);
    } else if (key === "visualArea") {
      validateVisualArea(target.visualArea, label, errors);
    } else if (typeof target[key] !== "string" || target[key].length === 0) {
      errors.push(`${label} runtimeTarget.${key} must be a non-empty string`);
    }
  }
}

function validateVisualArea(visualArea, label, errors) {
  if (!visualArea || typeof visualArea !== "object" || Array.isArray(visualArea)) {
    errors.push(`${label} runtimeTarget.visualArea must be an object`);
    return;
  }
  for (const key of Object.keys(visualArea)) {
    if (!visualAreaFields.has(key)) {
      errors.push(`${label} runtimeTarget.visualArea contains unsupported field ${key}`);
    }
  }
  for (const key of visualAreaFields) {
    if (typeof visualArea[key] !== "number") {
      errors.push(`${label} runtimeTarget.visualArea.${key} must be a number`);
    }
  }
  if (typeof visualArea.left === "number"
    && typeof visualArea.top === "number"
    && typeof visualArea.right === "number"
    && typeof visualArea.bottom === "number"
    && (visualArea.right <= visualArea.left || visualArea.bottom <= visualArea.top)) {
    errors.push(`${label} runtimeTarget.visualArea must have positive width and height`);
  }
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
  const riskFlags = new Set(hasRiskObservation ? (observed.riskFlags || []).map(normalizeRiskFlag) : []);
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
    } else {
      failures.push(confidenceMismatchFailure(expectation.expectedConfidence, observed.confidence));
    }
  } else if (expectation.expectedConfidence) {
    addUnique(environment, trustObservationNotConfigured);
  }

  const expectedRiskFlags = (expectation.expectedRiskFlags || []).map(normalizeRiskFlag);
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

export function classifyRuntimeTrustOutcome(expectation, observed) {
  const outcome = classifyCaseOutcome(expectation, observed);
  if (expectation.expectedConfidence && !hasOwn(observed, "confidence")) {
    removeLabel(outcome.environment, trustObservationNotConfigured);
    addUnique(outcome.failures, "missing_confidence_observation");
  }
  if (expectation.expectedSourceConfidence) {
    if (!hasOwn(observed, "sourceConfidence")) {
      addUnique(outcome.failures, "missing_source_confidence_observation");
    } else if (confidenceMatches(expectation.expectedSourceConfidence, observed.sourceConfidence)) {
      addUnique(outcome.metrics, "source_confidence_calibrated");
    } else {
      addUnique(outcome.failures, confidenceMismatchFailure(expectation.expectedSourceConfidence, observed.sourceConfidence));
    }
  }
  if ((expectation.expectedRiskFlags || []).length > 0 && !hasOwn(observed, "riskFlags")) {
    removeLabel(outcome.environment, trustObservationNotConfigured);
    addUnique(outcome.failures, "missing_risk_observation");
  }
  if (((expectation.mustWarn || []).length > 0 || (expectation.mustNotWarn || []).length > 0) && !hasOwn(observed, "warnings")) {
    removeLabel(outcome.environment, trustObservationNotConfigured);
    addUnique(outcome.failures, "missing_warning_observation");
  }
  if (expectation.mustNotHighConfidence === true && !hasOwn(observed, "confidence")) {
    removeLabel(outcome.environment, trustObservationNotConfigured);
    addUnique(outcome.failures, "missing_confidence_observation");
  }
  if (expectation.expectedRecommendedEditSiteContains) {
    if (!hasOwn(observed, "callSites")) {
      addUnique(outcome.failures, "missing_call_site_observation");
    } else {
      const recommended = (observed.callSites || []).filter((site) => site && site.recommendedEditSite === true);
      const needle = expectation.expectedRecommendedEditSiteContains;
      if (recommended.length === 1 && typeof recommended[0].file === "string" && recommended[0].file.includes(needle)) {
        addUnique(outcome.metrics, "recommended_edit_site_present");
      } else {
        addUnique(outcome.failures, "missing_recommended_edit_site");
      }
    }
  }
  if ((expectation.expectedBoundaryContextKinds || []).length > 0) {
    if (!hasOwn(observed, "boundaryContext")) {
      addUnique(outcome.failures, "missing_boundary_context_observation");
    } else {
      const observedKinds = new Set((observed.boundaryContext || []).map((entry) => entry && entry.kind).filter(Boolean));
      for (const kind of expectation.expectedBoundaryContextKinds || []) {
        if (observedKinds.has(kind)) {
          addUnique(outcome.metrics, "boundary_context_kind_present");
        } else {
          addUnique(outcome.failures, "missing_boundary_context_kind");
        }
      }
    }
  }
  return outcome;
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
  const actualRank = confidenceRank.get(actual);
  const bounds = confidenceExpectationBounds(expected);
  return bounds !== null && actualRank !== undefined && actualRank >= bounds.min && actualRank <= bounds.max;
}

function confidenceMismatchFailure(expected, actual) {
  const actualRank = confidenceRank.get(actual);
  const bounds = confidenceExpectationBounds(expected);
  if (bounds === null || actualRank === undefined) return "underconfident";
  return actualRank > bounds.max ? "overconfident" : "underconfident";
}

function confidenceExpectationBounds(expected) {
  if (!confidenceExpectations.has(expected)) return null;
  if (expected === "medium-or-high") return { min: confidenceRank.get("medium"), max: confidenceRank.get("high") };
  if (expected === "low-or-medium") return { min: confidenceRank.get("low"), max: confidenceRank.get("medium") };
  const rank = confidenceRank.get(expected);
  return { min: rank, max: rank };
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

function removeLabel(values, label) {
  const index = values.indexOf(label);
  if (index >= 0) values.splice(index, 1);
}

function normalizeRiskFlag(value) {
  return value === "UNTYPED_FALLBACK" ? "LEGACY_FALLBACK" : value;
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

function isLocalProjectFixture(fixture) {
  return (fixture.source || externalFixtureSource) === localFixtureSource;
}

export function fixturePaths(fixture) {
  if (isLocalProjectFixture(fixture)) {
    const projectWorkDir = fixture.projectDir === "."
      ? repoRoot
      : join(repoRoot, safeRelativePath(fixture.projectDir, `${fixture.id} projectDir`));
    return {
      repoDir: repoRoot,
      workDir: repoRoot,
      projectWorkDir,
    };
  }
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

export function patchAppBuildFileText(text, options = {}) {
  const addDebugRuntime = options.addDebugRuntime === true;
  const pluginLine = '    id("io.github.beyondwin.fixthis.compose")';
  const configBlock = [
    "",
    "fixthis {",
    `    addDebugRuntime.set(${addDebugRuntime ? "true" : "false"})`,
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
  if (next.includes("addDebugRuntime.set(")) {
    next = next.replace(/addDebugRuntime\.set\((true|false)\)/, `addDebugRuntime.set(${addDebugRuntime ? "true" : "false"})`);
  } else {
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
  if (isLocalProjectFixture(fixture)) {
    return paths;
  }
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
  writeFileSync(
    appBuildPath,
    patchAppBuildFileText(
      readFileSync(appBuildPath, "utf8"),
      { addDebugRuntime: options.addDebugRuntime === true },
    ),
  );

  return paths;
}

function entryPathHaystack(entry) {
  const parts = [];
  if (typeof entry.file === "string") parts.push(entry.file);
  if (typeof entry.repoFile === "string") parts.push(entry.repoFile);
  return parts;
}

function entryMatchesNeedle(entry, needle) {
  return entryPathHaystack(entry).some((candidate) => candidate.includes(needle));
}

export function evaluateSourceIndexCase(testCase, sourceIndex) {
  const entries = Array.isArray(sourceIndex?.entries) ? sourceIndex.entries : [];
  const expectedEntryNeedles = arrayOf(testCase.expectedEntryPathContains);
  const rankingExpectationNeedles = [
    ...arrayOf(testCase.expectedTop1PathContains),
    ...arrayOf(testCase.expectedTop3PathContains),
  ];
  const pathNeedles = [
    ...arrayOf(testCase.expectedEntryPathContains),
    ...arrayOf(testCase.expectedTop3PathContains),
  ];
  const hasRankingExpectation = rankingExpectationNeedles.length > 0;
  const matchingEntries = entries.filter((entry) =>
    pathNeedles.some((needle) => entryMatchesNeedle(entry, needle)),
  );
  const expectedEntryMatches = entries.filter((entry) =>
    expectedEntryNeedles.some((needle) => entryMatchesNeedle(entry, needle)),
  );
  const candidateEntries = hasRankingExpectation ? entries : matchingEntries;
  const observed = {
    candidates: candidateEntries.slice(0, 3).map((entry) => ({
      path: typeof entry.repoFile === "string" && entry.repoFile.length > 0
        ? entry.repoFile
        : entry.file,
      line: entry.line || null,
      signals: entry.signals || [],
    })),
    environment: [],
  };
  const outcome = classifyCaseOutcome(testCase, observed);

  if (expectedEntryNeedles.length > 0 && !expectedEntryNeedles.every((needle) =>
    expectedEntryMatches.some((entry) => entryMatchesNeedle(entry, needle))
  )) {
    addUnique(outcome.failures, "missing_top3");
  }

  if (testCase.expectedSignal) {
    const signalEntries = expectedEntryNeedles.length > 0 ? expectedEntryMatches : matchingEntries;
    const foundSignal = signalEntries.some((entry) =>
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
    mode: "source-index",
    metrics: outcome.metrics,
    failures: outcome.failures,
    environment: outcome.environment,
    observed: {
      candidates: observed.candidates,
    },
  };
}

export function evaluateFixtureSourceIndex(fixture, sourceIndex) {
  const cases = fixture.cases
    .filter((testCase) => testCase.mode === "source-index")
    .map((testCase) => evaluateSourceIndexCase(testCase, sourceIndex));
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
    schemaVersion: 2,
    generatedAt,
    status: reportStatus(caseResults),
    summary: {
      totalCases: caseResults.length,
      sourceIndexCases: caseResults.filter((testCase) => testCase.mode === "source-index").length,
      runtimeTrustCases: caseResults.filter((testCase) => testCase.mode === "runtime-trust").length,
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
    lines.push(`- Source-index cases: ${report.summary.sourceIndexCases}`);
    lines.push(`- Runtime-trust cases: ${report.summary.runtimeTrustCases}`);
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
    lines.push("| Case | Purpose | Metrics | Failures | Environment |");
    lines.push("| --- | --- | --- | --- | --- |");
    for (const testCase of fixture.cases || []) {
      lines.push([
        testCase.caseId,
        testCase.trustPurpose || "-",
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
      cases: fixture.cases.filter((testCase) => testCase.mode === "source-index").map((testCase) => ({
        caseId: testCase.id,
        mode: "source-index",
        metrics: [],
        failures: ["source_index_missing"],
        environment: [],
      })),
    };
  }
  const sourceIndex = JSON.parse(readFileSync(sourceIndexPath, "utf8"));
  return evaluateFixtureSourceIndex(fixture, sourceIndex);
}

export function runtimeFixtureInput(fixture, projectDir, strict = false) {
  const sourceIndexPath = fixture.modulePath && fixture.variant
    ? generatedSourceIndexPath(projectDir, fixture)
    : null;
  return {
    projectDir,
    packageName: fixture.applicationId,
    sourceIndexPath: sourceIndexPath && existsSync(sourceIndexPath) ? sourceIndexPath : null,
    strict,
    cases: (fixture.cases || [])
      .filter((testCase) => testCase.mode === "runtime-trust")
      .map((testCase) => ({
        caseId: testCase.id,
        runtimeTarget: testCase.runtimeTarget,
        ...(testCase.navigateBefore ? { navigateBefore: testCase.navigateBefore } : {}),
      })),
  };
}

export function runtimeTrustFixtureGradleArgs(inputPath, outputPath, strict = false) {
  return [
    ":fixthis-mcp:runRuntimeTrustFixture",
    `--args=--input ${inputPath} --output ${outputPath}${strict ? " --strict" : ""}`,
    "--no-daemon",
  ];
}

const runtimeCompatibleSourceIndexFlag = "-Pfixthis.runtimeCompatibleSourceIndex=true";

export function runtimeSourceIndexGradleArgs(fixture) {
  return [
    sourceIndexTaskPath(fixture),
    runtimeCompatibleSourceIndexFlag,
    "--no-daemon",
  ];
}

export function runtimeInstallGradleArgs(fixture) {
  return [
    `${fixture.modulePath}:install${variantTaskSuffix(fixture.variant)}`,
    runtimeCompatibleSourceIndexFlag,
    "--no-daemon",
  ];
}

export function hasRuntimeCases(fixture) {
  return (fixture.cases || []).some((testCase) => testCase.mode === "runtime-trust");
}

export function runtimeFixtures(manifest) {
  return (manifest.fixtures || []).filter(hasRuntimeCases);
}

export function installRuntimeFixture(fixture, options = {}) {
  const prepare = options.prepare || prepareFixture;
  const run = options.run || runCommand;
  const stdio = options.stdio || "inherit";
  const paths = prepare(fixture, { stdio, addDebugRuntime: true });
  run("./gradlew", runtimeSourceIndexGradleArgs(fixture), { cwd: paths.projectWorkDir, stdio });
  run("./gradlew", runtimeInstallGradleArgs(fixture), { cwd: paths.projectWorkDir, stdio });
  return paths;
}

function withEnvironmentPatch(options = {}, envPatch = {}) {
  return {
    ...options,
    env: {
      ...(options.env || {}),
      ...envPatch,
    },
  };
}

export function evaluateRuntimeTrustFixture(fixture, runnerOutput) {
  const casesById = new Map((runnerOutput.cases || []).map((testCase) => [testCase.caseId, testCase]));
  const cases = fixture.cases
    .filter((testCase) => testCase.mode === "runtime-trust")
    .map((testCase) => {
      const captured = casesById.get(testCase.id);
      if (!captured) {
        return {
          caseId: testCase.id,
          mode: "runtime-trust",
          trustPurpose: testCase.trustPurpose,
          metrics: [],
          failures: ["missing_runtime_case_output"],
          environment: [],
        };
      }
      if (captured.failures?.length || captured.environment?.length) {
        return {
          caseId: testCase.id,
          mode: "runtime-trust",
          trustPurpose: testCase.trustPurpose,
          metrics: [],
          failures: captured.failures || [],
          environment: captured.environment || [],
          observed: captured.observed || null,
        };
      }
      const outcome = classifyRuntimeTrustOutcome(testCase, captured.observed || {});
      return {
        caseId: testCase.id,
        mode: "runtime-trust",
        trustPurpose: testCase.trustPurpose,
        metrics: outcome.metrics,
        failures: outcome.failures,
        environment: outcome.environment,
        observed: captured.observed,
      };
    });
  return {
    fixtureId: fixture.id,
    mode: "runtime-trust",
    status: cases.some((testCase) => testCase.failures.length > 0) ? "fail" : "evaluated",
    cases,
  };
}

export function runRuntimeTrustEvaluation(fixture, options = {}) {
  const strict = options.strict === true;
  const envPatch = options.envPatch || {};
  const run = options.run || runCommand;
  const install = options.install || installRuntimeFixture;
  const reportDir = options.reportDir || defaultReportDir;
  const runWithEnvironment = (command, args, runOptions = {}) =>
    run(command, args, withEnvironmentPatch(runOptions, envPatch));
  const paths = install(fixture, { stdio: "inherit", run: runWithEnvironment });

  const inputPath = join(reportDir, `${fixture.id}-runtime-input.json`);
  const outputPath = join(reportDir, `${fixture.id}-runtime-output.json`);
  rmSync(outputPath, { force: true });
  writeJson(inputPath, runtimeFixtureInput(fixture, paths.projectWorkDir, strict));
  const args = runtimeTrustFixtureGradleArgs(inputPath, outputPath, strict);
  let runnerError = null;
  try {
    runWithEnvironment("./gradlew", args, { cwd: repoRoot, stdio: "pipe" });
  } catch (error) {
    runnerError = error;
    if (!existsSync(outputPath)) throw error;
  }
  const runnerOutput = JSON.parse(readFileSync(outputPath, "utf8"));
  if (!runnerOutput || !Array.isArray(runnerOutput.cases)) throw runnerError || new Error(`${fixture.id}: runtime fixture output is invalid`);
  return evaluateRuntimeTrustFixture(fixture, runnerOutput);
}

export function writeFixtureReport(fixtures) {
  const report = buildFixtureReport(fixtures);
  writeJson(join(defaultReportDir, "report.json"), report);
  writeFileSync(join(defaultReportDir, "report.md"), markdownReport(report));
  return report;
}

function usage() {
  return "Usage: node scripts/source-matching-fixtures.mjs <prepare|run|runtime|report> [--strict]";
}

export async function main(argv = process.argv.slice(2)) {
  const command = argv[0];
  if (!command || !["prepare", "run", "runtime", "report"].includes(command)) {
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
          cases: fixture.cases.filter((testCase) => testCase.mode === "source-index").map((testCase) => ({
            caseId: testCase.id,
            mode: "source-index",
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
  if (command === "runtime") {
    const strict = argv.includes("--strict");
    const androidEnvironment = resolveAndroidEnvironment();
    const fixtures = [];
    for (const fixture of runtimeFixtures(manifest)) {
      try {
        fixtures.push(runRuntimeTrustEvaluation(fixture, { strict, envPatch: androidEnvironment.envPatch }));
      } catch (error) {
        fixtures.push({
          fixtureId: fixture.id,
          mode: "runtime-trust",
          status: strict ? "fail" : "environment_downgrade",
          error: error.message,
          cases: fixture.cases
            .filter((testCase) => testCase.mode === "runtime-trust")
            .map((testCase) => ({
              caseId: testCase.id,
              mode: "runtime-trust",
              trustPurpose: testCase.trustPurpose,
              metrics: [],
              failures: strict ? ["capture_failed"] : [],
              environment: strict ? [] : ["capture_failed"],
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
