# Source Matching Fixture Lab

The source matching fixture lab is a local-only developer tool for checking
whether FixThis source-index and source-hint changes remain trustworthy on
external Compose apps and on the bundled sample app.

Use this guide when a change touches source matching, target reliability,
edit-surface confidence, shared-component call-site guidance, visual-area
caveats, AndroidView/WebView boundary context, or release evidence that cites
those behaviors.

It is not a release gate, not a CI requirement, not a branch-protection setting,
and not part of the public install path. Release-readiness docs can cite its
commands as local evidence, but the lab itself does not publish artifacts or
change any public package channel.

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

## Runtime Trust Case Purpose

Every `runtime-trust` case includes `trustPurpose`. The field explains which
trust failure mode the case protects, such as baseline runtime confidence,
controlled local component identity, external copy/data matching, selector
drift, or warning/risk observation. Reports render this purpose so failures are
actionable without reopening the manifest.

### Runtime Trust Case Purposes

Every `runtime-trust` case must describe the trust failure mode it protects in
`trustPurpose`. Cases may assert a positive source candidate, but they may also
assert that confidence remains low or medium, a warning remains present, or an
exact-source claim is not made.

Visual-area cases use:

```json
{
  "runtimeTarget": {
    "visualArea": { "left": 24, "top": 160, "right": 360, "bottom": 260 }
  },
  "mustWarn": ["VISUAL_AREA_ONLY"],
  "mustNotHighConfidence": true
}
```

Interop-risk cases should prefer a runtime target that lands on the boundary
host and require `POSSIBLE_VIEW_INTEROP`. Shared-component cases should assert
`expectedRecommendedEditSiteContains` only when the runtime observation emits a
single `recommendedEditSite=true` call site.

The lab supports two fixture sources:

- `external-github` fixtures clone a pinned Android sample repository into
  `.fixthis/eval-fixtures/`.
- `local-project` fixtures run against this checkout, currently for the bundled
  FixThis sample app. They do not clone or patch the project.

## Commands

Prepare fixtures:

```bash
npm run source-matching:fixtures:prepare
```

Run source-index evaluation:

```bash
npm run source-matching:fixtures
```

The local `fixthis-sample-copy-data-source-index` case protects V1 trust
hardening by keeping copy/data text evidence visible in the source index
without requiring a device-backed runtime observation. Layout-renderer evidence
is protected by `KotlinSourceScannerTest` and `SourceMatcherTest`, where the
scanner and matcher can control layout-specific source snippets directly.

Run device-backed runtime trust evaluation:

```bash
npm run source-matching:fixtures:runtime
```

Run runtime trust evaluation as a strict local gate:

```bash
npm run source-matching:fixtures:runtime -- --strict
```

Print the latest Markdown report:

```bash
npm run source-matching:fixtures:report
```

Run fast offline tests for the runner:

```bash
npm run source-matching:fixtures:test
```

The initial runtime set covers Reply as the external happy path, the bundled
FixThis sample app as a controlled local component-identity case, and Jetsnack
as a non-Reply resource-backed launch-screen case. Now in Android remains
source-index-only until a stable launch-state runtime selector is verified
without coordinates, scroll setup, account state, network state, or fragile
timing.

## Reading Results

The runner writes:

```text
build/reports/fixthis-source-matching/report.json
build/reports/fixthis-source-matching/report.md
```

Important result labels:

- `missing_top3`: expected source entry did not appear in the evaluated source index.
- `wrong_top1`: a case required a top-1 match and the first candidate did not match.
- `missing_source_signal`: expected typed source signal was missing.
- `overconfident`: observed confidence was higher than the case allowed.
- `underconfident`: observed confidence was lower than the case expected.
- `missing_risk_flag`: expected source risk flag was absent.
- `missing_warning`: a required target warning was absent.
- `unexpected_warning`: a warning that should not appear was present.
- `unexpected_high_confidence`: a case marked `mustNotHighConfidence` became high confidence.
- `weak_evidence_promoted`: weak evidence carried a risk or warning but still became high confidence.
- `target_not_found`: runtime selector did not match a captured semantics node.
- `target_ambiguous`: runtime selector matched more than one captured semantics node.
- `missing_confidence_observation`: runtime target reliability confidence was absent.
- `missing_source_confidence_observation`: runtime top source candidate confidence was absent.
- `missing_risk_observation`: runtime source risk flags were absent.
- `missing_warning_observation`: runtime target reliability warnings were absent.
- `missing_call_site_observation`: runtime recommended edit-site call-site output was absent.
- `fixture_build_failed`: the external fixture did not build in this local environment.
- `source_index_missing`: Gradle completed without producing the expected FixThis source index.

Reserved labels for future fixture contract work:

- `fixture_drift`: the pinned upstream fixture no longer matches the case contract and should be re-pinned or corrected.
- `case_contract_invalid`: the committed manifest case is invalid.

`trust_observation_not_configured` was the old source-index-only downgrade for
runtime trust expectations. It is not valid in manifest schema v2. Source-index
cases cannot contain runtime trust expectations, and runtime-trust cases report
missing observations as explicit failures. When no device or capture session is
available, the runtime command records environment downgrades such as
`capture_failed`; `--strict` converts those downgrades into a non-zero local
gate.

## Multi-Module Source-Index Aggregation

The FixThis Gradle plugin aggregates source from the `:app` module's resolved
project dependencies. Multi-module fixtures (for example, Now in Android's
`feature/foryou/.../strings.xml` evaluated from `:app`) therefore implicitly
require a declared or transitive compile/runtime dependency from `:app` to
the target feature module.

If a multi-module case produces `missing_top3`, the cause is either:

1. a matcher regression (the index no longer surfaces the expected entry); or
2. an upstream graph change that removed the implicit `:app → :feature:X`
   compile-time dependency (a fixture-drift failure that requires re-pinning,
   not a matcher regression).

When in doubt, inspect the fixture working copy under
`.fixthis/eval-fixtures/work/<fixture>/` and confirm the consumer module
still depends on the target feature module.

## Classification Rules

Treat fixture outcomes as evidence classifications, not as a single pass/fail
bucket:

- Product failure: a committed source-index or runtime-trust expectation fails
  in a prepared fixture environment.
- Documentation drift: a guide, docs index, or release-readiness reference no
  longer points to the supported fixture-lab entry point.
- Environment downgrade: non-strict runtime evidence cannot run because Android
  SDK, ADB, a ready device, or app runtime prerequisites are unavailable.
- Strict runtime failure: strict runtime evidence is requested and those runtime
  prerequisites are unavailable.
- Fixture drift: a pinned external repository no longer contains the expected
  path, module graph, launch state, or semantics target.
- Caveated pass: FixThis cannot prove exact edit ownership but preserves the
  required caveat, such as `SHARED_COMPONENT`, `VISUAL_AREA_ONLY`,
  `POSSIBLE_VIEW_INTEROP`, boundary context, or a low/medium confidence cap.

Do not weaken a case to make a report green. A high-confidence result is a
failure when the case exists to prove caution, even if the source path looks
plausible.

## Re-Pinning

Manifest commits drift from upstream and so do paths. To re-pin a fixture:

1. Resolve the upstream head commit on the branch the lab tracks:
   ```bash
   gh api repos/android/compose-samples/commits/main --jq .sha
   gh api repos/android/nowinandroid/commits/main --jq .sha
   ```
2. Walk each fixture's tree at the resolved SHA and confirm every
   `expectedEntryPathContains` exists in the tree exactly once:
   ```bash
   gh api repos/android/compose-samples/git/trees/<sha>?recursive=1 \
     --jq '.tree[].path'
   ```
3. Confirm the variant resolves to the expected `applicationId` by inspecting
   the upstream `applicationIdSuffix` and `flavorDimensions`.
4. Replace the placeholder commits and paths in
   `fixtures/source-matching/manifest.json` with the verified values and
   reference the verification commands in the commit message so reviewers
   can re-run them.
