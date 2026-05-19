# Trustworthy Source Matching Local Fixture Lab Design

Date: 2026-05-20
Status: Ready for user review
Scope: local-only source matching evaluation fixtures, runner, reports, and
trust calibration gates

## Summary

The next trust-focused FixThis improvement should be a local fixture lab for
source matching, not a broad matcher rewrite.

FixThis v0.7.0 improved source-index coverage for active debug variants,
multi-module project dependencies, typed Compose signals, owner composables,
and default-locale string-resource values. The next risk is confidence
calibration: as the matcher learns more source patterns, it must not become
more overconfident when the selected UI is ambiguous, visual-only, repeated,
or interop-adjacent.

This design adds a local-only evaluation harness that downloads pinned Google
Compose sample apps into gitignored fixture directories, installs the current
FixThis tooling into disposable working copies, evaluates source-index and
optional device-backed handoff evidence, and writes local JSON/Markdown reports
under gitignored build output.

The release artifact and user install path do not include the sample apps or
reports. This is a developer evidence lab for local use.

## Product Goal

Create a repeatable local way to answer:

> Did a matcher or source-index change make FixThis source hints more useful
> without increasing false confidence?

The success metric is not "every sampled target has the exact top-1 file." The
success metric is that useful cases improve while ambiguous, visual-area, weak
semantics, repeated-item, and possible interop cases stay cautious.

## Sources For External Fixtures

The fixture set should start with official Google Android Compose samples:

- Android Developers lists Compose samples as working samples for Compose UI
  and highlights Now in Android as a fully functional Compose reference app:
  <https://developer.android.google.cn/develop/ui/compose/samples?hl=en>
- `android/compose-samples` is the official Jetpack Compose sample repository
  and is Apache-2.0 licensed:
  <https://github.com/android/compose-samples>
- `android/nowinandroid` is a fully functional Kotlin/Compose reference app,
  supports `demoDebug` for local exploration, and is Apache-2.0 licensed:
  <https://github.com/android/nowinandroid>

The initial three fixtures should be:

1. `JetNews` or `Reply` from `android/compose-samples` for common text,
   list, and detail-screen matching.
2. `Jetsnack` from `android/compose-samples` for repeated cards, custom UI,
   visual/style edit-surface hints, and layout-heavy screens.
3. `Now in Android` `demoDebug` for a real-world multi-module and flavored
   app structure.

The implementation can choose `JetNews` or `Reply` after checking which sample
builds fastest and exposes better stable evaluation targets locally.

## Non-Goals

- Do not vendor external sample app source into the repository.
- Do not add CI, nightly, or release-gate execution in this iteration.
- Do not advertise local fixture results as a public release claim.
- Do not add a new package channel.
- Do not implement full View hierarchy or WebView DOM source targeting.
- Do not perform a broad source matcher scoring rewrite in the same change.
- Do not rename persisted MCP JSON fields.
- Do not include fixture source, generated `.fixthis/` output, or reports in
  release artifacts.

## Local Directory Layout

Only small configuration and runner files are committed. External source and
reports stay local.

```text
fixtures/source-matching/manifest.json
scripts/source-matching-fixtures.mjs
scripts/source-matching-fixtures-test.mjs
.fixthis/eval-fixtures/repos/
.fixthis/eval-fixtures/work/
build/reports/fixthis-source-matching/
```

Committed files:

- `fixtures/source-matching/manifest.json` defines pinned fixture repositories,
  commits, sample project directories, variants, application ids, and
  evaluation cases.
- `scripts/source-matching-fixtures.mjs` prepares, runs, and reports the local
  fixture evaluation.
- focused script tests cover manifest parsing, path resolution, downgrade
  behavior, report classification, and report schema.

Gitignored local files:

- `.fixthis/eval-fixtures/repos/` stores upstream clone caches.
- `.fixthis/eval-fixtures/work/` stores disposable working copies where
  FixThis can patch Gradle files, generate `.fixthis/project.json`, and build
  source indexes.
- `build/reports/fixthis-source-matching/` stores JSON/Markdown output and
  optional before/after comparison records.

## Manifest Shape

The manifest is intentionally about expected evidence, not only commands.

```json
{
  "fixtures": [
    {
      "id": "jetsnack",
      "repo": "https://github.com/android/compose-samples.git",
      "commit": "0123456789abcdef0123456789abcdef01234567",
      "projectDir": "Jetsnack",
      "variant": "debug",
      "applicationId": "com.example.jetsnack",
      "cases": [
        {
          "id": "snack-card-title",
          "targetText": "Example visible text",
          "expectedTop3PathContains": ["Snack", "Card"],
          "expectedConfidence": "medium-or-high",
          "mustNotWarn": ["POSSIBLE_VIEW_INTEROP"]
        }
      ]
    }
  ]
}
```

Rules:

- `commit` must be an immutable full SHA, not a branch or tag.
- `repo` must be an HTTPS Git URL.
- `projectDir` is relative to the fixture repository root.
- `variant` is the Gradle variant to build or install.
- `applicationId` is the expected debug app id after variant suffixes are
  accounted for.
- `cases` describe source-hint expectations using stable visible text,
  content descriptions, test tags, source path substrings, confidence
  expectations, and warning expectations.

The first implementation should keep the manifest schema small. Add new fields
only when a case needs them.

## Local Commands

Add local-only npm scripts:

```text
npm run source-matching:fixtures:prepare
npm run source-matching:fixtures
npm run source-matching:fixtures:report
npm run source-matching:fixtures:test
```

`prepare`:

1. Reads the manifest.
2. Clones or fetches each fixture repo into `.fixthis/eval-fixtures/repos/`.
3. Checks out the pinned commit.
4. Creates or refreshes a disposable work copy under
   `.fixthis/eval-fixtures/work/`.
5. Installs the local FixThis tooling into that work copy using the current
   repo's local CLI/Gradle plugin path or generated local artifacts.

`fixtures`:

1. Runs prepare when needed.
2. Builds the fixture variant.
3. Generates and reads the FixThis source index.
4. If a ready Android device or emulator is available, optionally installs the
   app and captures live handoff evidence.
5. If no device is available, downgrades to build/source-index-only evaluation
   and records `device_unavailable` instead of failing the whole run.
6. Compares observed candidates, target reliability, and warnings against the
   manifest cases.

`report`:

1. Writes a stable JSON report for machine comparison.
2. Writes a concise Markdown report for local review.
3. Optionally compares the current run with the previous report.

`test`:

1. Runs script/unit tests only.
2. Does not clone external repositories.
3. Does not require Android SDK, ADB, or a device.

## Metrics

The evaluation should classify evidence by trust usefulness:

- `top1_hit`: rank-1 source candidate matches the expected path or composable
  target.
- `top3_hit`: one of the first three source candidates matches the expected
  path or composable target.
- `confidence_calibrated`: expected high, medium, low, or unknown confidence
  matches the case expectation.
- `overclaim_prevented`: ambiguous, visual-area, weak semantics, repeated, or
  possible interop cases avoid high confidence and include expected warnings.
- `warning_present`: a required warning is present.
- `warning_absent`: a warning that must not appear is absent.
- `build_source_index_available`: fixture built far enough to generate a
  readable source index.
- `handoff_capture_available`: device-backed capture evidence was collected.

Failure classifications:

- `fixture_clone_failed`
- `fixture_build_failed`
- `source_index_missing`
- `wrong_top1`
- `missing_top3`
- `overconfident`
- `missing_warning`
- `unexpected_warning`
- `device_unavailable`
- `capture_failed`
- `manifest_invalid`

`device_unavailable` is a downgrade state for local runs. It should appear in
the report but should not make source-index-only evaluation unusable.

## Data Flow

### Source-Index-Only Flow

1. Runner prepares fixture work copy.
2. Runner applies or installs FixThis into the fixture.
3. Fixture Gradle build runs the source-index generation task.
4. Runner reads the generated `fixthis-source-index.json`.
5. Runner evaluates manifest cases that can be checked from source-index data
   alone.
6. Runner writes JSON and Markdown reports.

This flow is available without an emulator.

### Device-Backed Handoff Flow

1. Runner prepares and builds the fixture.
2. Runner installs and launches the fixture debug app.
3. FixThis captures current screen semantics, screenshot, source candidates,
   and target reliability.
4. Runner selects stable manifest-defined targets where possible.
5. Runner evaluates top candidates, confidence, warnings, and target
   reliability.
6. Runner writes evidence artifacts to the local report directory.

This flow is optional in the first implementation. The runner must make clear
which cases were build-only and which were device-backed.

## Error Handling

- Missing Android SDK, missing ADB, no ready device, or locked emulator:
  downgrade to source-index-only evaluation and record `device_unavailable`.
- Fixture clone or checkout failure: mark that fixture as
  `fixture_clone_failed`, keep evaluating other fixtures.
- Fixture build failure: mark that fixture as `fixture_build_failed`, include
  the build command and log path in the report.
- Missing generated source index: mark as `source_index_missing`; this is a
  trust-lab failure because source matching cannot be evaluated.
- Manifest with a floating branch, tag, missing commit, or path escape:
  fail fast with `manifest_invalid`.
- Unexpected matcher output: record case-level failure without stopping the
  full fixture run.

## Test Strategy

### Script Unit Tests

Add tests for:

- manifest parsing and schema validation;
- rejecting floating branches, tags, short SHAs, and path escapes;
- resolving fixture repo/work/report paths under allowed local roots;
- classifying report outcomes;
- preserving stable report JSON keys;
- downgrading no-device runs without marking source-index-only cases failed.

### Contract Tests

Add tests or check scripts that verify:

- `.gitignore` excludes `.fixthis/eval-fixtures/`;
- `.gitignore` excludes `build/reports/fixthis-source-matching/`;
- committed manifest entries use pinned full SHAs;
- runner tests do not require network access;
- report schema can be parsed by future before/after comparison logic.

### Manual Evidence

The local manual evidence command is:

```bash
npm run source-matching:fixtures
```

The run is successful when it produces a report that clearly separates:

- fixture preparation status;
- build/source-index status;
- device-backed capture status, if available;
- case-level top1/top3/confidence/warning results;
- overconfidence failures;
- local environment downgrades.

## Acceptance Criteria

- External fixture source is never committed.
- Fixture cache, working copy, generated `.fixthis/` data, and reports are
  ignored by git.
- Runner uses immutable pinned commits only.
- The runner can execute source-index-only evaluation without a connected
  device.
- Device-backed evaluation is optional and clearly marked when unavailable.
- Reports flag high-confidence overclaiming as a first-class failure.
- Reports capture enough JSON detail for before/after comparison across local
  matcher changes.
- Existing FixThis release artifacts and public install instructions do not
  mention or depend on the fixture lab.

## Risks

- **Fixture drift:** upstream samples may change build requirements. Mitigate
  with pinned commits and local update-by-intent.
- **Runtime cost:** Now in Android can be heavy. Mitigate by keeping this
  local-only and adding fixture selection flags when runtime becomes a
  practical blocker.
- **False certainty from scripted targets:** manifest cases may overfit easy
  text targets. Mitigate by including ambiguous, repeated, visual, and possible
  interop cases early.
- **Scope creep into matcher rewrite:** keep this iteration focused on
  evaluation infrastructure. Matcher scoring changes should follow with a
  before/after report.
- **Local environment variance:** device-backed capture can vary by emulator
  state. Mitigate by treating no-device and capture failures as explicit report
  states, not hidden hard failures.

## Implementation Sequencing

1. Add gitignore entries for local fixture cache and reports.
2. Add manifest schema and initial pinned fixture definitions.
3. Add prepare logic for clone/fetch/checkout/work-copy creation.
4. Add FixThis install/apply logic for fixture working copies.
5. Add source-index-only evaluator and report writer.
6. Add runner unit tests and contract checks.
7. Add optional device detection and downgrade reporting.
8. Add initial device-backed capture cases only after source-index-only reports
   are useful.
9. Document the local-only workflow and interpretation of confidence failures.

## Review Findings (2026-05-20)

A pre-implementation review of the companion plan
(`docs/superpowers/plans/2026-05-20-trustworthy-source-matching-local-fixture-lab.md`)
against the FixThis Gradle plugin source surfaced several gaps that the
design must absorb so that the implementation plan and its acceptance
criteria stay coherent. The plan's "Review Findings" section carries the
authoritative corrective patches; this section captures the design-level
implications.

### D1. Trust-calibration coverage must be a manifest-level requirement

The Product Goal frames success as "useful cases improve while ambiguous,
visual, weak-semantics, repeated, or possible-interop cases stay cautious."
The initial plan, however, allowed every committed case to omit
`expectedConfidence` and warning expectations. To keep design and
implementation aligned, the acceptance criteria below are tightened:

> Each committed fixture must include at least one case that pins
> `expectedConfidence`, and the fixture set as a whole must include at least
> one `mustWarn` or `mustNotWarn` expectation. The lab must be able to fail
> the suite on a confidence regression on day one, not "once we get
> around to adding such cases."

This is reflected in the plan's manifest patch (Task 1 Step 5) under F2.

### D2. Re-pinning is a first-class workflow, not a one-time chore

Manifest commits drift from upstream and so do paths. The plan now carries
a **Task 0** that resolves the upstream SHA, walks the tree at that SHA via
`gh api`, and only then writes the manifest. The design must list re-pinning
as an explicit, repeatable workflow so that future plan revisions do not
silently regress to placeholder SHAs. The Implementation Sequencing list is
amended:

> 0. Pin upstream commits and verify expected case paths against the pinned
>    trees. Reject placeholder SHAs and unverified paths.

This step precedes the existing item 1.

### D3. Multi-module source-index dependency is part of the test contract

`FixThisGradlePlugin.kt` aggregates source from the `:app` module's
resolved project dependencies. Cross-module case expectations therefore
implicitly assume the consumer module compiles in the target feature module.
The Test Strategy section is amended:

> Manifest cases that span modules (for example,
> `feature/foryou/.../strings.xml` evaluated from `:app`) must rely on a
> declared or transitive compile/runtime dependency from `:app` to the
> target module. If the upstream graph changes, the case must be re-pinned
> or moved; otherwise the resulting `missing_top3` is a fixture-drift
> failure, not a matcher regression.

### D4. Failure classification table is missing two states actually produced

The Error Handling and Metrics sections enumerate failures but the plan's
runner currently produces two additional implicit states:

- `source_index_missing` when the Gradle build succeeded but the expected
  source-index file is absent. The list mentions this; keep it.
- `wrong_top1` when an `expectedTop1PathContains` is supplied and the
  rank-1 candidate differs. The list mentions this; keep it.
- `manifest_invalid` for upstream-failed validation (already listed).

What is missing today and needs to be added to the canonical list:

- `expectedConfidence_unsupported` — surfaced by the validator (the plan's
  Task 2 `validateManifest` already throws on this; the design list should
  include it so future readers find it).
- `signal_kind_unknown` — the validator does not currently reject unknown
  `expectedSignal.kind` values, but `SourceSignalAsset` in the plugin
  defines a closed enum (`COMPOSABLE_SYMBOL`, `UI_TEXT`, `STRING_RESOURCE`,
  `TEST_TAG`, `STRICT_COMP_TEST_TAG`, `CONTENT_DESCRIPTION`, `ROLE`,
  `ACTIVITY_NAME`, `ARBITRARY_STRING_LITERAL`, `STRING_RESOURCE_RESOLVED`,
  `LAMBDA_OWNER_FUNCTION`). The runner should validate against that enum
  so a typo like `COMPOSABLE_SYMBOLL` fails fast.

### D5. `applicationId` field semantics are informational in v1

Until device-backed capture lands, the runner does not read
`applicationId`; the design previously described it as "the expected debug
app id after variant suffixes are accounted for", which both Reply (which
adds `.debug` via `applicationIdSuffix`) and Now in Android (`.demo.debug`)
satisfy. v1 should explicitly mark the field as informational so that an
inaccurate value does not silently cause future device-backed runs to fail.

Manifest Shape is amended with a note:

> `applicationId` is informational in v1. It is recorded so the device-backed
> handoff flow can resolve the running app, but the v1 source-index
> evaluator does not assert on it. Inaccurate values must be corrected
> before the device-backed flow is enabled.

### D6. Updated acceptance criteria

The Acceptance Criteria list is replaced with the following (additions
**bolded** in intent — committed as plain text):

- External fixture source is never committed.
- Fixture cache, working copy, generated `.fixthis/` data, and reports are
  ignored by git.
- Runner uses immutable pinned commits only, and the pinning workflow is
  re-runnable as documented in the local fixture lab guide.
- The runner can execute source-index-only evaluation without a connected
  device.
- Device-backed evaluation is optional and clearly marked when unavailable.
- Reports flag high-confidence overclaiming as a first-class failure.
- Every committed fixture exercises at least one `expectedConfidence`, and
  the fixture set as a whole exercises at least one `mustWarn` or
  `mustNotWarn` expectation.
- Reports capture enough JSON detail for before/after comparison across
  local matcher changes.
- The runner validates `expectedSignal.kind` against the closed enum
  exported by the FixThis Gradle plugin so manifest typos fail fast.
- Existing FixThis release artifacts and public install instructions do not
  mention or depend on the fixture lab.

### D7. Additional risks worth naming

- **Manifest placeholder drift.** Plans authored against a future schema
  may carry SHAs from training/sketching that no longer exist upstream.
  Mitigation: Task 0 re-pin step and reviewer rejection of unverified SHAs.
- **Source-signal enum churn.** Adding/removing a `SourceSignalKindAsset`
  in the plugin would silently invalidate manifest cases that referenced
  the removed kind. Mitigation: the runner's signal-kind validator should
  import the closed enum from a single, plugin-published list rather than
  hard-coding it. Track as follow-up.

---

## Approval Notes

The user approved:

1. Prioritizing technical trust over product expansion or distribution reach.
2. Using the recommended "Trustworthy Source Matching" direction.
3. Using official Google sample apps as local external fixtures.
4. Keeping fixture source and reports local-only and gitignored.
5. Using a manifest-driven local runner with JSON/Markdown reports.
6. Treating confidence calibration and overclaim prevention as primary
   evaluation goals.
