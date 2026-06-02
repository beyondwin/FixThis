import assert from "node:assert/strict";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

import {
  buildFixtureReport,
  classifyCaseOutcome,
  classifyRuntimeTrustOutcome,
  loadManifest,
  evaluateSourceIndexCase,
  fixturePaths,
  installRuntimeFixture,
  markdownReport,
  patchAppBuildFileText,
  patchSettingsText,
  reportStatus,
  runRuntimeTrustEvaluation,
  runtimeTrustFailureCase,
  runtimeFixtureInput,
  runtimeFixtures,
  runtimeInstallGradleArgs,
  runtimeSourceIndexGradleArgs,
  runtimeTrustFixtureGradleArgs,
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
  assert.equal(pkg.scripts["release:reality"], "node scripts/release-reality-check.mjs");
  assert.equal(pkg.scripts["source-matching:fixtures:prepare"], "node scripts/source-matching-fixtures.mjs prepare");
  assert.equal(pkg.scripts["source-matching:fixtures"], "node scripts/source-matching-fixtures.mjs run");
  assert.equal(pkg.scripts["source-matching:fixtures:runtime"], "node scripts/source-matching-fixtures.mjs runtime");
  assert.equal(pkg.scripts["source-matching:fixtures:report"], "node scripts/source-matching-fixtures.mjs report");
  assert.equal(pkg.scripts["source-matching:fixtures:test"], "node --test scripts/source-matching-fixtures-test.mjs");
  assert.equal(pkg.scripts["agent-loop:smoke"], "node scripts/agent-loop-smoke.mjs");
  assert.equal(pkg.scripts["agent-loop:smoke:test"], "node --test scripts/agent-loop-smoke-test.mjs");
});

test("fixture manifest uses schema v2 with separated source-index and runtime-trust cases", () => {
  const manifest = readJson("fixtures/source-matching/manifest.json");
  assert.equal(manifest.schemaVersion, 2);
  assert.equal(manifest.fixtures.length, 4);
  const cases = manifest.fixtures.flatMap((fixture) => fixture.cases);
  assert.ok(cases.some((entry) => entry.mode === "source-index"));
  assert.ok(cases.some((entry) => entry.mode === "runtime-trust"));
  for (const fixture of manifest.fixtures) {
    const source = fixture.source || "external-github";
    assert.ok(["external-github", "local-project"].includes(source));
    if (source === "external-github") {
      assert.match(fixture.repo, /^https:\/\/github\.com\/android\//);
      assert.match(fixture.commit, /^[a-f0-9]{40}$/);
    } else {
      assert.equal(fixture.repo, undefined);
      assert.equal(fixture.commit, undefined);
    }
    assert.ok(fixture.id.length > 0);
    assert.ok(fixture.projectDir.length > 0);
    assert.ok(fixture.modulePath.startsWith(":"));
    assert.ok(fixture.variant.length > 0);
    assert.ok(fixture.applicationId.length > 0);
    assert.ok(fixture.cases.length > 0);
  }
  for (const entry of cases.filter((testCase) => testCase.mode === "source-index")) {
    assert.equal(entry.expectedConfidence, undefined);
    assert.equal(entry.expectedSourceConfidence, undefined);
    assert.equal(entry.expectedRiskFlags, undefined);
    assert.equal(entry.mustWarn, undefined);
    assert.equal(entry.mustNotWarn, undefined);
    assert.equal(entry.mustNotHighConfidence, undefined);
    assert.equal(entry.runtimeTarget, undefined);
  }
  for (const entry of cases.filter((testCase) => testCase.mode === "runtime-trust")) {
    assert.equal(typeof entry.trustPurpose, "string");
    assert.ok(entry.trustPurpose.length > 0);
  }
});

test("runtime trust manifest includes focused Reply, local sample, and Jetsnack cases", () => {
  const manifest = readJson("fixtures/source-matching/manifest.json");
  const cases = manifest.fixtures.flatMap((fixture) => fixture.cases.map((testCase) => ({
    fixtureId: fixture.id,
    ...testCase,
  })));

  assert.ok(cases.some((testCase) => testCase.fixtureId === "reply" && testCase.id === "reply-compose-fab-runtime"));
  assert.ok(cases.some((testCase) => testCase.fixtureId === "fixthis-sample" && testCase.id === "fixthis-sample-home-primary-runtime"));
  assert.ok(cases.some((testCase) => testCase.fixtureId === "jetsnack" && testCase.id === "jetsnack-filters-runtime"));
});

test("manifest covers every Trust Loop runtime trust risk class", () => {
  const manifest = loadManifest();
  const runtimeCases = manifest.fixtures.flatMap((fixture) =>
    fixture.cases
      .filter((entry) => entry.mode === "runtime-trust")
      .map((entry) => ({ fixtureId: fixture.id, ...entry })),
  );

  assert.ok(
    runtimeCases.some((entry) => entry.trustPurpose.includes("shared") && entry.mustNotHighConfidence === true),
    "shared component runtime trust case is required",
  );
  assert.ok(
    runtimeCases.some((entry) => (entry.mustWarn || []).includes("POSSIBLE_VIEW_INTEROP") && entry.mustNotHighConfidence === true),
    "interop-risk runtime trust case is required",
  );
  assert.ok(
    runtimeCases.some((entry) => (entry.mustWarn || []).includes("VISUAL_AREA_ONLY") && entry.mustNotHighConfidence === true),
    "visual-area runtime trust case is required",
  );
  assert.ok(
    runtimeCases.some((entry) => entry.expectedSourceConfidence === "low-or-medium"),
    "weak source-candidate runtime trust case is required",
  );
});

test("runtime trust report classifies setup capture and trust failures separately", () => {
  const setup = runtimeTrustFailureCase("fixture-setup-failed", false);
  const capture = runtimeTrustFailureCase("runtime-capture-failed", false);
  const strictCapture = runtimeTrustFailureCase("runtime-capture-failed", true);

  assert.deepEqual(setup, {
    failures: [],
    environment: ["fixture_setup_failed"],
  });
  assert.deepEqual(capture, {
    failures: [],
    environment: ["runtime_capture_failed"],
  });
  assert.deepEqual(strictCapture, {
    failures: ["runtime_capture_failed"],
    environment: [],
  });
});

test("manifest includes local copy-data source trust case", () => {
  const manifest = readJson("fixtures/source-matching/manifest.json");
  const local = manifest.fixtures.find((fixture) => fixture.id === "fixthis-sample");
  assert.ok(local, "fixthis-sample fixture is required");
  const ids = new Set(local.cases.map((entry) => entry.id));
  assert.ok(ids.has("fixthis-sample-copy-data-source-index"));
});

test("manifest pins reused StudioHeader as a SHARED_COMPONENT source-index case", () => {
  // Real manifest must validate (no stale/unsupported fields).
  const manifest = loadManifest();
  const local = manifest.fixtures.find((fixture) => fixture.id === "fixthis-sample");
  assert.ok(local, "fixthis-sample fixture is required");
  const pinned = local.cases.find((entry) => entry.id === "fixthis-sample-shared-component");
  assert.ok(pinned, "fixthis-sample-shared-component case is required");

  // This is a source-index case: the static index carries the fan-in signal the
  // Gradle SourceIndexGenerator emits when a composable is reused above threshold.
  assert.equal(pinned.mode, "source-index");

  // The case must target the single reused StudioHeader definition file.
  const definitionFile = "sample/src/main/java/io/github/beyondwin/fixthis/sample/components/StudioHeader.kt";
  assert.equal(pinned.expectedEntryPathContains, definitionFile);

  // The value equals StudioHeader's current call-site count (ReviewScreen,
  // HomeScreen, DiagnosticsScreen, ProjectScreen = 4). Update it if a call site
  // is added or removed. If fan-in detection regresses and stops flagging the
  // shared component, the SHARED_COMPONENT signal disappears and the live
  // evaluateSourceIndexCase fails this case with missing_source_signal.
  assert.deepEqual(pinned.expectedSignal, { kind: "SHARED_COMPONENT", value: "4" });
});

test("manifest pins a shared-component call-site source-index contract", () => {
  // Real manifest must validate (no stale/unsupported fields), and the source-index
  // contract must include the static call-site signal used for runtime ranking.
  const manifest = loadManifest();
  const local = manifest.fixtures.find((fixture) => fixture.id === "fixthis-sample");
  assert.ok(local, "fixthis-sample fixture is required");
  const pinned = local.cases.find((entry) => entry.id === "fixthis-sample-shared-header-diagnostics-call-site");
  assert.ok(pinned, "fixthis-sample-shared-header-diagnostics-call-site case is required");

  assert.equal(pinned.mode, "source-index");
  assert.equal(
    pinned.expectedEntryPathContains,
    "sample/src/main/java/io/github/beyondwin/fixthis/sample/components/StudioHeader.kt",
  );
  assert.deepEqual(pinned.expectedSignal, {
    kind: "SHARED_COMPONENT_CALL_SITE",
    value: "sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/DiagnosticsScreen.kt:49\tDiagnosticsScreen\tDiagnostics|Inspect selection quality and semantic coverage.|Live",
  });
});

test("manifest pins a recommended-edit-site call site on the reused StudioHeader, capped at medium", () => {
  // Real manifest must validate (no stale/unsupported fields).
  const manifest = loadManifest();
  const local = manifest.fixtures.find((fixture) => fixture.id === "fixthis-sample");
  assert.ok(local, "fixthis-sample fixture is required");
  const pinned = local.cases.find((entry) => entry.id === "fixthis-sample-shared-header-recommended-edit-site");
  assert.ok(pinned, "fixthis-sample-shared-header-recommended-edit-site case is required");

  // Recommended-edit-site is a runtime call-site concept (Task 1): the runtime
  // selection that overlaps one reused call site promotes that single site.
  assert.equal(pinned.mode, "runtime-trust");

  // Selecting the reused StudioHeader root after navigating to Diagnostics
  // avoids ambiguous duplicate "Diagnostics" text nodes while nearby evidence
  // still ranks the DiagnosticsScreen call site as the recommended edit site.
  assert.deepEqual(pinned.navigateBefore, { contentDescription: "Diagnostics tab" });
  assert.deepEqual(pinned.runtimeTarget, { testTag: "comp:StudioHeader:root" });
  assert.equal(pinned.expectedTop3PathContains, "sample/src/main/java/io/github/beyondwin/fixthis/sample/components/StudioHeader.kt");

  // The StudioHeader *definition* stays capped below HIGH even with call-site
  // ranking and a recommended edit site (the shared-component medium cap).
  assert.equal(pinned.expectedSourceConfidence, "low-or-medium");
  assert.equal(pinned.mustNotHighConfidence, true);

  // The recommended edit site must be the DiagnosticsScreen call site only, and
  // it must be the single call site flagged recommendedEditSite=true.
  assert.equal(pinned.expectedRecommendedEditSiteContains, "screens/DiagnosticsScreen.kt");
});

test("validateManifest rejects floating commits and unsafe paths", () => {
  assert.throws(
    () => validateManifest({
      schemaVersion: 2,
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

test("validateManifest rejects runtime-only fields on source-index cases", () => {
  assert.throws(
    () => validateManifest({
      schemaVersion: 2,
      fixtures: [{
        id: "reply",
        repo: "https://github.com/android/compose-samples.git",
        commit: "d3ff757b289f7036815978a8f7b16706ee3423b0",
        projectDir: "Reply",
        modulePath: ":app",
        variant: "debug",
        applicationId: "com.example.reply",
        cases: [{
          id: "bad-source-index-trust",
          mode: "source-index",
          expectedTop3PathContains: "ReplyListContent.kt",
          expectedConfidence: "medium-or-high",
        }],
      }],
    }),
    /bad-source-index-trust source-index case contains runtime-only field expectedConfidence/,
  );
});

test("validateManifest requires runtimeTarget on runtime-trust cases", () => {
  assert.throws(
    () => validateManifest({
      schemaVersion: 2,
      fixtures: [{
        id: "reply",
        repo: "https://github.com/android/compose-samples.git",
        commit: "d3ff757b289f7036815978a8f7b16706ee3423b0",
        projectDir: "Reply",
        modulePath: ":app",
        variant: "debug",
        applicationId: "com.example.reply",
        cases: [{
          id: "missing-runtime-target",
          mode: "runtime-trust",
          expectedTop3PathContains: "ReplyListContent.kt",
          expectedConfidence: "medium-or-high",
        }],
      }],
    }),
    /missing-runtime-target runtime-trust case must define runtimeTarget/,
  );
});

test("validateManifest requires trustPurpose on runtime-trust cases", () => {
  assert.throws(
    () => validateManifest({
      schemaVersion: 2,
      fixtures: [{
        id: "reply",
        repo: "https://github.com/android/compose-samples.git",
        commit: "d3ff757b289f7036815978a8f7b16706ee3423b0",
        projectDir: "Reply",
        modulePath: ":app",
        variant: "debug",
        applicationId: "com.example.reply",
        cases: [{
          id: "missing-purpose",
          mode: "runtime-trust",
          runtimeTarget: { text: "Compose" },
          expectedTop3PathContains: "ReplyListContent.kt",
          expectedConfidence: "medium-or-high",
        }],
      }],
    }),
    /missing-purpose runtime-trust case must define trustPurpose/,
  );
});

test("validateManifest accepts local-project fixtures with moduleDir", () => {
  assert.doesNotThrow(() => validateManifest({
    schemaVersion: 2,
    fixtures: [{
      id: "fixthis-sample",
      source: "local-project",
      projectDir: ".",
      modulePath: ":app",
      moduleDir: "sample",
      variant: "debug",
      applicationId: "io.github.beyondwin.fixthis.sample",
      cases: [{
        id: "fixthis-sample-home-primary-runtime",
        mode: "runtime-trust",
        trustPurpose: "controlled local strict component identity case",
        runtimeTarget: { testTag: "comp:HomePrimaryAction:primary" },
        expectedTop3PathContains: "sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/HomeScreen.kt",
        expectedConfidence: "medium-or-high",
        expectedSourceConfidence: "medium-or-high",
        mustNotWarn: ["POSSIBLE_VIEW_INTEROP"],
      }],
    }],
  }));
});

test("validateManifest rejects unsupported fixture sources and unsafe moduleDir", () => {
  assert.throws(
    () => validateManifest({
      schemaVersion: 2,
      fixtures: [{
        id: "bad-local",
        source: "zip-file",
        projectDir: ".",
        modulePath: ":app",
        moduleDir: "../sample",
        variant: "debug",
        applicationId: "io.github.beyondwin.fixthis.sample",
        cases: [{ id: "bad", mode: "source-index", expectedTop3PathContains: "HomeScreen.kt" }],
      }],
    }),
    /bad-local source must be external-github or local-project.*bad-local moduleDir escapes fixture root/,
  );
});

test("validateManifest accepts runtime trust calibration fields and rejects unsupported risk flags", () => {
  assert.doesNotThrow(() => validateManifest({
    schemaVersion: 2,
    fixtures: [{
      id: "trusty",
      repo: "https://github.com/android/compose-samples.git",
      commit: "d3ff757b289f7036815978a8f7b16706ee3423b0",
      projectDir: "Reply",
      modulePath: ":app",
      variant: "debug",
      applicationId: "com.example.reply",
      cases: [{
        id: "trust-case",
        mode: "runtime-trust",
        trustPurpose: "calibrated trust signals for compose FAB",
        runtimeTarget: { text: "Compose", role: "Button" },
        expectedTop3PathContains: ["ReplyApp.kt", "ReplyList.kt"],
        expectedConfidence: "medium-or-high",
        expectedSourceConfidence: "low-or-medium",
        expectedRiskFlags: ["AMBIGUOUS"],
        mustWarn: ["LOW_SOURCE_CANDIDATE_MARGIN"],
        mustNotWarn: ["POSSIBLE_VIEW_INTEROP"],
        mustNotHighConfidence: true,
      }],
    }],
  }));

  assert.throws(
    () => validateManifest({
      schemaVersion: 2,
      fixtures: [{
        id: "bad-risk",
        repo: "https://github.com/android/compose-samples.git",
        commit: "d3ff757b289f7036815978a8f7b16706ee3423b0",
        projectDir: "Reply",
        modulePath: ":app",
        variant: "debug",
        applicationId: "com.example.reply",
        cases: [{
          id: "bad-risk-case",
          mode: "runtime-trust",
          runtimeTarget: { text: "Compose" },
          expectedTop3PathContains: "ReplyApp.kt",
          expectedRiskFlags: ["NOT_A_REAL_RISK"],
        }],
      }],
    }),
    /bad-risk-case expectedRiskFlags contains unsupported value NOT_A_REAL_RISK/,
  );

  assert.throws(
    () => validateManifest({
      schemaVersion: 2,
      fixtures: [{
        id: "bad-warning",
        repo: "https://github.com/android/compose-samples.git",
        commit: "d3ff757b289f7036815978a8f7b16706ee3423b0",
        projectDir: "Reply",
        modulePath: ":app",
        variant: "debug",
        applicationId: "com.example.reply",
        cases: [{
          id: "bad-warning-case",
          mode: "runtime-trust",
          runtimeTarget: { text: "Compose" },
          expectedTop3PathContains: "ReplyApp.kt",
          mustWarn: ["NOT_A_REAL_WARNING"],
          mustNotWarn: ["ALSO_NOT_A_REAL_WARNING"],
          mustNotHighConfidence: "yes",
        }],
      }],
    }),
    /bad-warning-case mustWarn contains unsupported value NOT_A_REAL_WARNING.*bad-warning-case mustNotWarn contains unsupported value ALSO_NOT_A_REAL_WARNING.*bad-warning-case mustNotHighConfidence must be boolean/,
  );
});

test("validateManifest accepts visual-area runtime targets and rejects malformed bounds", () => {
  assert.doesNotThrow(() => validateManifest({
    schemaVersion: 2,
    fixtures: [{
      id: "visual",
      source: "local-project",
      projectDir: ".",
      modulePath: ":app",
      moduleDir: "sample",
      variant: "debug",
      applicationId: "io.github.beyondwin.fixthis.sample",
      cases: [{
        id: "visual-area-runtime",
        mode: "runtime-trust",
        trustPurpose: "visual area target stays caveated",
        runtimeTarget: { visualArea: { left: 10, top: 20, right: 110, bottom: 120 } },
        expectedConfidence: "low-or-medium",
        mustWarn: ["VISUAL_AREA_ONLY"],
        mustNotHighConfidence: true,
      }],
    }],
  }));

  assert.throws(() => validateManifest({
    schemaVersion: 2,
    fixtures: [{
      id: "bad-visual",
      source: "local-project",
      projectDir: ".",
      modulePath: ":app",
      moduleDir: "sample",
      variant: "debug",
      applicationId: "io.github.beyondwin.fixthis.sample",
      cases: [{
        id: "bad-visual-area-runtime",
        mode: "runtime-trust",
        trustPurpose: "bad visual area",
        runtimeTarget: { visualArea: { left: 10, top: 20, right: 9, bottom: 120 } },
      }],
    }],
  }), /runtimeTarget.visualArea must have positive width and height/);

  assert.throws(() => validateManifest({
    schemaVersion: 2,
    fixtures: [{
      id: "bad-navigation-area",
      source: "local-project",
      projectDir: ".",
      modulePath: ":app",
      moduleDir: "sample",
      variant: "debug",
      applicationId: "io.github.beyondwin.fixthis.sample",
      cases: [{
        id: "bad-navigate-before-area",
        mode: "runtime-trust",
        trustPurpose: "navigate before cannot use area selectors",
        navigateBefore: { visualArea: { left: 10, top: 20, right: 110, bottom: 120 } },
        runtimeTarget: { testTag: "comp:StudioHeader:root" },
      }],
    }],
  }), /navigateBefore runtimeTarget contains unsupported field visualArea/);
});

test("validateManifest accepts expected runtime boundary context kinds", () => {
  assert.doesNotThrow(() => validateManifest({
    schemaVersion: 2,
    fixtures: [{
      id: "local",
      source: "local-project",
      projectDir: "sample",
      modulePath: ":app",
      variant: "debug",
      applicationId: "io.github.beyondwin.fixthis.sample",
      cases: [{
        id: "interop",
        mode: "runtime-trust",
        trustPurpose: "interop boundary context evidence",
        runtimeTarget: { visualArea: { left: 1, top: 1, right: 2, bottom: 2 } },
        expectedBoundaryContextKinds: ["host", "ancestor", "context"],
      }],
    }],
  }));
});

test("validateManifest accepts top1-only path expectations", () => {
  assert.doesNotThrow(() => validateManifest({
    schemaVersion: 2,
    fixtures: [{
      id: "top1-only",
      repo: "https://github.com/android/compose-samples.git",
      commit: "d3ff757b289f7036815978a8f7b16706ee3423b0",
      projectDir: "Reply",
      modulePath: ":app",
      variant: "debug",
      applicationId: "com.example.reply",
      cases: [{
        id: "top1-case",
        mode: "source-index",
        expectedTop1PathContains: "ReplyApp.kt",
      }],
    }],
  }));
});

test("classifyRuntimeTrustOutcome fails missing runtime observations", () => {
  assert.deepEqual(
    classifyRuntimeTrustOutcome({
      expectedTop3PathContains: "ReplyListContent.kt",
      expectedConfidence: "medium-or-high",
      expectedSourceConfidence: "medium-or-high",
      expectedRiskFlags: ["ARBITRARY_LITERAL"],
      mustWarn: ["LOW_SOURCE_CANDIDATE_MARGIN"],
    }, {
      candidates: [{ path: "Reply/app/src/main/java/com/example/reply/ui/ReplyListContent.kt" }],
    }).failures,
    [
      "missing_confidence_observation",
      "missing_source_confidence_observation",
      "missing_risk_observation",
      "missing_warning_observation",
    ],
  );
});

test("classifyRuntimeTrustOutcome validates target and source confidence separately", () => {
  const result = classifyRuntimeTrustOutcome({
    expectedTop3PathContains: "ReplyListContent.kt",
    expectedConfidence: "medium-or-high",
    expectedSourceConfidence: "low-or-medium",
    mustNotWarn: ["POSSIBLE_VIEW_INTEROP"],
  }, {
    candidates: [{ path: "Reply/app/src/main/java/com/example/reply/ui/ReplyListContent.kt" }],
    confidence: "high",
    sourceConfidence: "medium",
    warnings: [],
    riskFlags: [],
  });
  assert.deepEqual(result.failures, []);
  assert.deepEqual(result.metrics, ["top1_hit", "top3_hit", "confidence_calibrated", "source_confidence_calibrated"]);
});

test("runtime trust classification verifies boundary context kinds", () => {
  const outcome = classifyRuntimeTrustOutcome(
    {
      id: "interop",
      mode: "runtime-trust",
      expectedBoundaryContextKinds: ["host"],
    },
    {
      confidence: "low",
      warnings: ["POSSIBLE_VIEW_INTEROP"],
      boundaryContext: [{ kind: "host", summary: "tag=\"comp:NativeChartHost:chart\"" }],
    },
  );

  assert.deepEqual(outcome.failures, []);
  assert.ok(outcome.metrics.includes("boundary_context_kind_present"));
});

test("runtime trust classification fails missing boundary context kinds", () => {
  const outcome = classifyRuntimeTrustOutcome(
    {
      id: "interop",
      mode: "runtime-trust",
      expectedBoundaryContextKinds: ["host"],
    },
    {
      confidence: "low",
      warnings: ["POSSIBLE_VIEW_INTEROP"],
      boundaryContext: [{ kind: "context", summary: "text=\"Native chart\"" }],
    },
  );

  assert.ok(outcome.failures.includes("missing_boundary_context_kind"));
});

test("classifyRuntimeTrustOutcome fails missing recommended edit-site observations", () => {
  const result = classifyRuntimeTrustOutcome({
    expectedRecommendedEditSiteContains: "DiagnosticsScreen.kt",
  }, {
    candidates: [{ path: "sample/components/StudioHeader.kt" }],
  });

  assert.deepEqual(result.failures, ["missing_call_site_observation"]);
  assert.deepEqual(result.environment, []);
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

test("classifyCaseOutcome differentiates confidence and risk regressions", () => {
  assert.deepEqual(
    classifyCaseOutcome({
      expectedTop1PathContains: "Home.kt",
      expectedTop3PathContains: "Home.kt",
      expectedConfidence: "medium-or-high",
    }, {
      candidates: [{ path: "sample/Home.kt" }],
      confidence: "low",
      warnings: [],
      riskFlags: [],
    }).failures,
    ["underconfident"],
  );

  assert.deepEqual(
    classifyCaseOutcome({
      expectedTop3PathContains: "Home.kt",
      expectedRiskFlags: ["ARBITRARY_LITERAL"],
    }, {
      candidates: [{ path: "sample/Home.kt" }],
      confidence: "medium",
      warnings: [],
      riskFlags: [],
    }).failures,
    ["missing_risk_flag"],
  );

  assert.deepEqual(
    classifyCaseOutcome({
      expectedTop3PathContains: "Home.kt",
      mustNotHighConfidence: true,
    }, {
      candidates: [{ path: "sample/Home.kt" }],
      confidence: "high",
      warnings: [],
      riskFlags: ["ARBITRARY_LITERAL"],
    }).failures,
    ["unexpected_high_confidence", "weak_evidence_promoted"],
  );

  assert.deepEqual(
    classifyCaseOutcome({
      expectedTop3PathContains: "Home.kt",
      mustNotHighConfidence: true,
    }, {
      candidates: [{ path: "sample/Home.kt" }],
      warnings: [],
      riskFlags: [],
    }),
    {
      metrics: ["top1_hit", "top3_hit"],
      failures: [],
      environment: ["trust_observation_not_configured"],
    },
  );

  assert.deepEqual(
    classifyCaseOutcome({
      expectedTop3PathContains: "Home.kt",
      expectedRiskFlags: ["ARBITRARY_LITERAL"],
      mustWarn: ["LOW_SOURCE_CANDIDATE_MARGIN"],
      mustNotHighConfidence: true,
    }, {
      candidates: [{ path: "sample/Home.kt" }],
      confidence: "medium",
      warnings: ["LOW_SOURCE_CANDIDATE_MARGIN"],
      riskFlags: ["ARBITRARY_LITERAL"],
    }).metrics,
    ["top1_hit", "top3_hit", "risk_flag_present", "warning_present", "high_confidence_avoided"],
  );
});

test("classifyCaseOutcome treats untyped fallback expectations as legacy fallback wire risk", () => {
  assert.deepEqual(
    classifyCaseOutcome({
      expectedTop3PathContains: "Home.kt",
      expectedRiskFlags: ["UNTYPED_FALLBACK"],
    }, {
      candidates: [{ path: "sample/Home.kt" }],
      warnings: [],
      riskFlags: ["LEGACY_FALLBACK"],
    }).metrics,
    ["top1_hit", "top3_hit", "risk_flag_present"],
  );
});

test("classifyCaseOutcome fails every observed confidence outside the expected band", () => {
  const cases = [
    ["high", "medium", "underconfident"],
    ["medium-or-high", "unknown", "underconfident"],
    ["low-or-medium", "unknown", "underconfident"],
    ["low", "medium", "overconfident"],
    ["unknown", "low", "overconfident"],
  ];

  for (const [expectedConfidence, confidence, failure] of cases) {
    assert.deepEqual(
      classifyCaseOutcome({
        expectedTop3PathContains: "Home.kt",
        expectedConfidence,
      }, {
        candidates: [{ path: "sample/Home.kt" }],
        confidence,
        warnings: [],
        riskFlags: [],
      }).failures,
      [failure],
    );
  }
});

test("classifyCaseOutcome downgrades unobserved trust expectations to environment", () => {
  assert.deepEqual(
    classifyCaseOutcome({
      expectedTop3PathContains: "Home.kt",
      expectedConfidence: "medium-or-high",
      expectedRiskFlags: ["ARBITRARY_LITERAL"],
      mustWarn: ["LOW_SOURCE_CANDIDATE_MARGIN"],
      mustNotWarn: ["POSSIBLE_VIEW_INTEROP"],
      mustNotHighConfidence: true,
    }, {
      candidates: [{ path: "sample/Home.kt" }],
    }),
    {
      metrics: ["top1_hit", "top3_hit"],
      failures: [],
      environment: ["trust_observation_not_configured"],
    },
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

test("patchAppBuildFileText can enable debug runtime for runtime fixtures", () => {
  const original = [
    "plugins {",
    "    alias(libs.plugins.android.application)",
    "}",
    "",
  ].join("\n");
  const patched = patchAppBuildFileText(original, { addDebugRuntime: true });
  assert.match(patched, /addDebugRuntime\.set\(true\)/);
  assert.equal(patchAppBuildFileText(patched, { addDebugRuntime: true }), patched);
});

test("runtimeFixtureInput contains only runtime-trust cases", () => {
  const input = runtimeFixtureInput({
    applicationId: "com.example.reply",
    cases: [
      { id: "source", mode: "source-index" },
      { id: "runtime", mode: "runtime-trust", runtimeTarget: { text: "Compose" } },
    ],
  }, "/tmp/reply", false);

  assert.equal(input.packageName, "com.example.reply");
  assert.equal(input.projectDir, "/tmp/reply");
  assert.deepEqual(input.cases, [{ caseId: "runtime", runtimeTarget: { text: "Compose" } }]);
});

test("runtimeTrustFixtureGradleArgs passes application arguments as one Gradle args value", () => {
  assert.deepEqual(
    runtimeTrustFixtureGradleArgs("/tmp/input.json", "/tmp/output.json", true),
    [
      ":fixthis-mcp:runRuntimeTrustFixture",
      "--args=--input /tmp/input.json --output /tmp/output.json --strict",
      "--no-daemon",
    ],
  );
});

test("runtimeInstallGradleArgs requests runtime-compatible source index output", () => {
  assert.deepEqual(
    runtimeInstallGradleArgs({
      modulePath: ":app",
      variant: "debug",
    }),
    [
      ":app:installDebug",
      "-Pfixthis.runtimeCompatibleSourceIndex=true",
      "--no-daemon",
    ],
  );
});

test("runtimeSourceIndexGradleArgs requests runtime-compatible source index output", () => {
  assert.deepEqual(
    runtimeSourceIndexGradleArgs({
      modulePath: ":app",
      variant: "debug",
    }),
    [
      ":app:generateDebugFixThisSourceIndex",
      "-Pfixthis.runtimeCompatibleSourceIndex=true",
      "--no-daemon",
    ],
  );
});

test("runtimeFixtures returns only installable runtime-trust fixtures in manifest order", () => {
  const manifest = {
    fixtures: [
      { id: "source-only", cases: [{ id: "source", mode: "source-index" }] },
      { id: "runtime-a", cases: [{ id: "runtime", mode: "runtime-trust", runtimeTarget: { text: "A" } }] },
      { id: "runtime-b", cases: [{ id: "runtime", mode: "runtime-trust", runtimeTarget: { text: "B" } }] },
    ],
  };

  assert.deepEqual(runtimeFixtures(manifest).map((fixture) => fixture.id), ["runtime-a", "runtime-b"]);
});

test("installRuntimeFixture prepares runtime source index before installing debug app", () => {
  const calls = [];
  const fixture = {
    id: "reply",
    modulePath: ":app",
    variant: "debug",
    applicationId: "com.example.reply",
    cases: [{ id: "runtime", mode: "runtime-trust", runtimeTarget: { text: "Inbox" } }],
  };
  const paths = { projectWorkDir: "/tmp/reply" };

  const result = installRuntimeFixture(fixture, {
    prepare: (input, options) => {
      calls.push({ type: "prepare", fixtureId: input.id, addDebugRuntime: options.addDebugRuntime });
      return paths;
    },
    run: (command, args, options) => {
      calls.push({ type: "run", command, args, cwd: options.cwd, stdio: options.stdio });
    },
    stdio: "pipe",
  });

  assert.equal(result.projectWorkDir, "/tmp/reply");
  assert.deepEqual(calls, [
    { type: "prepare", fixtureId: "reply", addDebugRuntime: true },
    {
      type: "run",
      command: "./gradlew",
      args: [":app:generateDebugFixThisSourceIndex", "-Pfixthis.runtimeCompatibleSourceIndex=true", "--no-daemon"],
      cwd: "/tmp/reply",
      stdio: "pipe",
    },
    {
      type: "run",
      command: "./gradlew",
      args: [":app:installDebug", "-Pfixthis.runtimeCompatibleSourceIndex=true", "--no-daemon"],
      cwd: "/tmp/reply",
      stdio: "pipe",
    },
  ]);
});

test("runRuntimeTrustEvaluation passes Android env patch to fixture install and runner", () => {
  const reportDir = mkdtempSync(join(tmpdir(), "fixthis-runtime-trust-"));
  const calls = [];
  const fixture = {
    id: "reply",
    modulePath: ":app",
    variant: "debug",
    applicationId: "com.example.reply",
    cases: [{
      id: "runtime",
      mode: "runtime-trust",
      trustPurpose: "env propagation",
      runtimeTarget: { text: "Inbox" },
    }],
  };

  try {
    const result = runRuntimeTrustEvaluation(fixture, {
      strict: true,
      reportDir,
      envPatch: { ANDROID_HOME: "/sdk", PATH: "/sdk/platform-tools:/bin" },
      install: (input, options) => {
        calls.push({ type: "install", fixtureId: input.id });
        options.run("./gradlew", [":app:installDebug"], {
          cwd: "/tmp/reply",
          stdio: "inherit",
          env: { EXISTING: "1" },
        });
        return { projectWorkDir: "/tmp/reply" };
      },
      run: (command, args, options) => {
        calls.push({ type: "run", command, args, env: options.env });
        const outputArg = args.find((arg) => arg.startsWith("--args="));
        const outputPath = outputArg?.match(/--output\s+(\S+)/)?.[1];
        if (outputPath) {
          writeFileSync(outputPath, JSON.stringify({ cases: [{ caseId: "runtime", observed: {} }] }));
        }
      },
    });

    assert.equal(result.status, "evaluated");
    assert.equal(calls[1].env.EXISTING, "1");
    assert.ok(calls.slice(1).every((call) => call.env.ANDROID_HOME === "/sdk"));
    assert.ok(calls.slice(1).every((call) => call.env.PATH === "/sdk/platform-tools:/bin"));
  } finally {
    rmSync(reportDir, { recursive: true, force: true });
  }
});

test("runRuntimeTrustEvaluation evaluates strict runner output when Gradle exits nonzero", () => {
  const reportDir = mkdtempSync(join(tmpdir(), "fixthis-runtime-output-"));
  const fixture = {
    id: "strict-output",
    applicationId: "io.example.fixture",
    modulePath: ":app",
    variant: "debug",
    cases: [
      {
        id: "shared",
        mode: "runtime-trust",
        trustPurpose: "strict output survives nonzero Gradle exit",
        runtimeTarget: { testTag: "comp:Shared:root" },
        expectedConfidence: "low-or-medium",
        mustNotHighConfidence: true,
      },
    ],
  };

  try {
    const result = runRuntimeTrustEvaluation(fixture, {
      strict: true,
      reportDir,
      install: () => ({ projectWorkDir: reportDir }),
      run: (_command, args) => {
        const outputArg = args.find((arg) => arg.startsWith("--args="));
        const outputPath = outputArg?.match(/--output\s+(\S+)/)?.[1];
        writeFileSync(outputPath, JSON.stringify({
          schemaVersion: 1,
          status: "fail",
          cases: [
            {
              caseId: "shared",
              observed: {
                candidates: [{ path: "app/Shared.kt", confidence: "high", riskFlags: [] }],
                confidence: "high",
                sourceConfidence: "high",
                warnings: [],
              },
            },
          ],
        }));
        throw new Error("Gradle exited 1 after writing strict output");
      },
    });

    assert.equal(result.cases[0].failures.includes("unexpected_high_confidence"), true);
  } finally {
    rmSync(reportDir, { recursive: true, force: true });
  }
});

test("runRuntimeTrustEvaluation rejects stale runtime output when runner fails before writing", () => {
  const reportDir = mkdtempSync(join(tmpdir(), "fixthis-runtime-stale-output-"));
  const fixture = {
    id: "stale-output",
    applicationId: "io.example.fixture",
    modulePath: ":app",
    variant: "debug",
    cases: [
      {
        id: "shared",
        mode: "runtime-trust",
        trustPurpose: "stale output must not satisfy strict runtime evidence",
        runtimeTarget: { testTag: "comp:Shared:root" },
        expectedConfidence: "low-or-medium",
      },
    ],
  };
  const staleOutputPath = join(reportDir, "stale-output-runtime-output.json");
  writeFileSync(staleOutputPath, JSON.stringify({
    schemaVersion: 1,
    status: "evaluated",
    cases: [{ caseId: "shared", observed: { confidence: "low" } }],
  }));

  try {
    assert.throws(() => runRuntimeTrustEvaluation(fixture, {
      strict: true,
      reportDir,
      install: () => ({ projectWorkDir: reportDir }),
      run: () => {
        throw new Error("Gradle failed before runtime output");
      },
    }), /Gradle failed before runtime output/);
    assert.equal(existsSync(staleOutputPath), false);
  } finally {
    rmSync(reportDir, { recursive: true, force: true });
  }
});

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

test("evaluateSourceIndexCase returns source-index observed shape only", () => {
  const sourceIndex = {
    schemaVersion: "1.2",
    entries: [
      {
        file: "Reply/app/src/main/java/com/example/reply/ui/MainActivity.kt",
        line: 52,
        signals: [],
      },
    ],
  };
  const result = evaluateSourceIndexCase({
    id: "reply-main-activity-owner",
    mode: "source-index",
    expectedEntryPathContains: "Reply/app/src/main/java/com/example/reply/ui/MainActivity.kt",
  }, sourceIndex);
  assert.deepEqual(result.failures, []);
  assert.deepEqual(result.metrics, ["top1_hit", "top3_hit"]);
  assert.equal(result.mode, "source-index");
  assert.deepEqual(Object.keys(result.observed), ["candidates"]);
});

test("evaluateSourceIndexCase supports top3-only path expectations", () => {
  const sourceIndex = {
    schemaVersion: "1.2",
    entries: [
      {
        file: "Reply/app/src/main/java/com/example/reply/ui/MainActivity.kt",
        line: 52,
        signals: [],
      },
      {
        file: "Reply/app/src/main/java/com/example/reply/ui/ReplyList.kt",
        line: 12,
        signals: [],
      },
    ],
  };

  const result = evaluateSourceIndexCase({
    id: "reply-list-top3",
    mode: "source-index",
    expectedTop3PathContains: "ReplyList.kt",
  }, sourceIndex);

  assert.deepEqual(result.failures, []);
  assert.deepEqual(result.metrics, ["top3_hit"]);
  assert.deepEqual(result.observed.candidates.map((candidate) => candidate.path), [
    "Reply/app/src/main/java/com/example/reply/ui/MainActivity.kt",
    "Reply/app/src/main/java/com/example/reply/ui/ReplyList.kt",
  ]);
});

test("evaluateSourceIndexCase preserves source index order for top1 expectations", () => {
  const sourceIndex = {
    schemaVersion: "1.2",
    entries: [
      {
        file: "Reply/app/src/main/java/com/example/reply/ui/Wrong.kt",
        line: 12,
        signals: [],
      },
      {
        file: "Reply/app/src/main/java/com/example/reply/ui/Expected.kt",
        line: 52,
        signals: [],
      },
    ],
  };

  const result = evaluateSourceIndexCase({
    id: "reply-expected-top1",
    mode: "source-index",
    expectedTop1PathContains: "Expected.kt",
    expectedTop3PathContains: "Expected.kt",
  }, sourceIndex);

  assert.deepEqual(result.failures, ["wrong_top1"]);
  assert.deepEqual(result.metrics, ["top3_hit"]);
  assert.deepEqual(result.observed.candidates.map((candidate) => candidate.path), [
    "Reply/app/src/main/java/com/example/reply/ui/Wrong.kt",
    "Reply/app/src/main/java/com/example/reply/ui/Expected.kt",
  ]);
});

test("evaluateSourceIndexCase enforces expected entry separately from ranking targets", () => {
  const sourceIndex = {
    schemaVersion: "1.2",
    entries: [
      {
        file: "Reply/app/src/main/java/com/example/reply/ui/PresentRankingTarget.kt",
        line: 12,
        signals: [
          { kind: "COMPOSABLE_SYMBOL", value: "WrongOwner" },
        ],
      },
    ],
  };

  const result = evaluateSourceIndexCase({
    id: "reply-missing-entry-with-present-ranking",
    mode: "source-index",
    expectedEntryPathContains: "MissingExpectedEntry.kt",
    expectedTop3PathContains: "PresentRankingTarget.kt",
    expectedSignal: { kind: "COMPOSABLE_SYMBOL", value: "ExpectedOwner" },
  }, sourceIndex);

  assert.deepEqual(result.metrics, ["top3_hit"]);
  assert.deepEqual(result.failures, ["missing_top3", "missing_source_signal"]);
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

test("evaluateSourceIndexCase resolves a lazy-list item-owner signal to the item composable", () => {
  const sourceIndex = {
    schemaVersion: "1.2",
    entries: [
      {
        file: "sample/src/main/java/com/example/orders/OrderListScreen.kt",
        line: 41,
        signals: [
          { kind: "LAZY_ITEM_OWNER", value: "OrderRow" },
        ],
      },
    ],
  };
  const result = evaluateSourceIndexCase({
    id: "lazy-list-item-owner",
    mode: "source-index",
    expectedEntryPathContains: "sample/src/main/java/com/example/orders/OrderListScreen.kt",
    expectedSignal: { kind: "LAZY_ITEM_OWNER", value: "OrderRow" },
  }, sourceIndex);
  assert.deepEqual(result.failures, []);
  assert.deepEqual(result.metrics, ["top1_hit", "top3_hit", "source_signal_present"]);
});

test("evaluateSourceIndexCase resolves a navigation destination-owner signal to the destination composable", () => {
  const sourceIndex = {
    schemaVersion: "1.2",
    entries: [
      {
        file: "sample/src/main/java/com/example/nav/AppNav.kt",
        line: 27,
        signals: [
          { kind: "NAV_DESTINATION_OWNER", value: "HomeScreen" },
        ],
      },
    ],
  };
  const result = evaluateSourceIndexCase({
    id: "nav-destination-owner",
    mode: "source-index",
    expectedEntryPathContains: "sample/src/main/java/com/example/nav/AppNav.kt",
    expectedSignal: { kind: "NAV_DESTINATION_OWNER", value: "HomeScreen" },
  }, sourceIndex);
  assert.deepEqual(result.failures, []);
  assert.deepEqual(result.metrics, ["top1_hit", "top3_hit", "source_signal_present"]);
});

test("markdownReport renders runtime trust purpose when present", () => {
  const text = markdownReport({
    schemaVersion: 2,
    generatedAt: "2026-05-25T00:00:00.000Z",
    status: "pass",
    summary: {
      totalCases: 1,
      sourceIndexCases: 0,
      runtimeTrustCases: 1,
      failedCases: 0,
      environmentCases: 0,
      failureCounts: {},
      environmentCounts: {},
    },
    fixtures: [{
      fixtureId: "fixthis-sample",
      status: "evaluated",
      cases: [{
        caseId: "fixthis-sample-home-primary-runtime",
        mode: "runtime-trust",
        trustPurpose: "controlled local strict component identity case",
        metrics: ["top3_hit"],
        failures: [],
        environment: [],
      }],
    }],
  });
  assert.match(text, /Purpose/);
  assert.match(text, /controlled local strict component identity case/);
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

test("writeFixtureReport style summary separates failures and environment downgrades", () => {
  const report = buildFixtureReport([{
    fixtureId: "reply",
    mode: "source-index",
    status: "evaluated",
    sourceIndexSchemaVersion: "1.2",
    cases: [
      {
        caseId: "ok",
        mode: "source-index",
        metrics: ["top3_hit", "confidence_calibrated"],
        failures: [],
        environment: [],
      },
      {
        caseId: "drift",
        mode: "source-index",
        metrics: [],
        failures: ["fixture_drift"],
        environment: ["upstream_path_missing"],
      },
    ],
  }], "2026-05-24T00:00:00.000Z");

  assert.equal(report.status, "fail");
  assert.deepEqual(report.summary.failureCounts, { fixture_drift: 1 });
  assert.deepEqual(report.summary.environmentCounts, { upstream_path_missing: 1 });
  assert.equal(report.summary.totalCases, 2);
  assert.equal(report.summary.sourceIndexCases, 2);
  assert.equal(report.summary.runtimeTrustCases, 0);
  assert.equal(report.summary.failedCases, 1);
  assert.equal(report.summary.environmentCases, 1);
});

test("buildFixtureReport splits source-index and runtime-trust summaries", () => {
  const report = buildFixtureReport([
    {
      fixtureId: "reply",
      mode: "mixed",
      status: "evaluated",
      cases: [
        { caseId: "source", mode: "source-index", metrics: ["top3_hit"], failures: [], environment: [] },
        { caseId: "runtime", mode: "runtime-trust", metrics: [], failures: [], environment: ["device_unavailable"] },
      ],
    },
  ], "2026-05-24T00:00:00.000Z");
  assert.equal(report.status, "pass_with_environment_downgrade");
  assert.equal(report.summary.sourceIndexCases, 1);
  assert.equal(report.summary.runtimeTrustCases, 1);
  assert.equal(report.summary.environmentCases, 1);
});

test("markdownReport prints summary counts before fixture tables", () => {
  const text = markdownReport({
    schemaVersion: 1,
    generatedAt: "2026-05-24T00:00:00.000Z",
    status: "fail",
    summary: {
      totalCases: 2,
      sourceIndexCases: 1,
      runtimeTrustCases: 1,
      failedCases: 1,
      environmentCases: 1,
      failureCounts: { overconfident: 1 },
      environmentCounts: { device_unavailable: 1 },
    },
    fixtures: [{
      fixtureId: "reply",
      status: "evaluated",
      cases: [{
      caseId: "bad-confidence",
      mode: "runtime-trust",
      metrics: [],
      failures: ["overconfident"],
      environment: ["device_unavailable"],
      }],
    }],
  });

  assert.match(text, /## Summary/);
  assert.match(text, /- Total cases: 2/);
  assert.match(text, /- Source-index cases: 1/);
  assert.match(text, /- Runtime-trust cases: 1/);
  assert.match(text, /- Failed cases: 1/);
  assert.match(text, /- Environment downgrade cases: 1/);
  assert.match(text, /- Failure counts: overconfident=1/);
  assert.match(text, /- Environment counts: device_unavailable=1/);

  const summaryIndex = text.indexOf("## Summary");
  const fixtureIndex = text.indexOf("## reply");
  assert.notEqual(summaryIndex, -1, "expected markdown report to include a Summary section");
  assert.notEqual(fixtureIndex, -1, "expected markdown report to include the reply fixture section");
  assert.ok(
    summaryIndex < fixtureIndex,
    "expected markdown report Summary section to appear before fixture sections",
  );
});

test("docs explain that fixture lab is local-only and gitignored", () => {
  const guide = read("docs/guides/source-matching-fixture-lab.md");
  assert.match(guide, /local-only/i);
  assert.match(guide, /\.fixthis\/eval-fixtures\//);
  assert.match(guide, /build\/reports\/fixthis-source-matching\//);
  assert.match(guide, /not a release gate/i);
  assert.match(guide, /npm run source-matching:fixtures/);
  assert.match(guide, /npm run source-matching:fixtures:runtime/);
  assert.match(guide, /npm run source-matching:fixtures:runtime -- --strict/);
  assert.match(guide, /target_not_found/);
  assert.match(guide, /missing_source_confidence_observation/);
  assert.match(guide, /trust_observation_not_configured.*not valid in manifest schema v2/s);

  const index = read("docs/index.md");
  assert.match(index, /source-matching-fixture-lab\.md/);
});
