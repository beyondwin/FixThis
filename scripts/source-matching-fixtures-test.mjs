import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

import {
  buildFixtureReport,
  classifyCaseOutcome,
  classifyRuntimeTrustOutcome,
  evaluateSourceIndexCase,
  fixturePaths,
  markdownReport,
  patchAppBuildFileText,
  patchSettingsText,
  reportStatus,
  runtimeFixtureInput,
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
  assert.equal(pkg.scripts["source-matching:fixtures:runtime"], "node scripts/source-matching-fixtures.mjs runtime");
  assert.equal(pkg.scripts["source-matching:fixtures:report"], "node scripts/source-matching-fixtures.mjs report");
  assert.equal(pkg.scripts["source-matching:fixtures:test"], "node --test scripts/source-matching-fixtures-test.mjs");
});

test("fixture manifest uses schema v2 with separated source-index and runtime-trust cases", () => {
  const manifest = readJson("fixtures/source-matching/manifest.json");
  assert.equal(manifest.schemaVersion, 2);
  assert.equal(manifest.fixtures.length, 3);
  const cases = manifest.fixtures.flatMap((fixture) => fixture.cases);
  assert.ok(cases.some((entry) => entry.mode === "source-index"));
  assert.ok(cases.some((entry) => entry.mode === "runtime-trust"));
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
  for (const entry of cases.filter((testCase) => testCase.mode === "source-index")) {
    assert.equal(entry.expectedConfidence, undefined);
    assert.equal(entry.expectedSourceConfidence, undefined);
    assert.equal(entry.expectedRiskFlags, undefined);
    assert.equal(entry.mustWarn, undefined);
    assert.equal(entry.mustNotWarn, undefined);
    assert.equal(entry.mustNotHighConfidence, undefined);
    assert.equal(entry.runtimeTarget, undefined);
  }
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
