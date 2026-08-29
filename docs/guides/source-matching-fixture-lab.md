# Source Matching Fixture Lab

Local-only check that source-index and source-hint changes stay trustworthy
on the sample app and pinned external Compose samples.

Use it when a change touches source matching, target reliability,
edit-surface confidence, shared-component call-site guidance, visual-area
caveats, AndroidView/WebView boundary context, or release evidence that
cites those behaviors.

Not a CI gate, not branch protection, not part of the public install path.
Release-readiness docs can cite its commands as local evidence.

## What it uses

The fixture manifest pins official Google Android Compose sample repos by
full commit SHA. The runner clones them under `.fixthis/eval-fixtures/`,
prepares disposable copies, applies the local Gradle plugin with
`addDebugRuntime` disabled, and generates a source index.

```text
.fixthis/eval-fixtures/repos/
.fixthis/eval-fixtures/work/
build/reports/fixthis-source-matching/
```

These paths are gitignored.

Two fixture sources:

- `external-github` — clone a pinned Android sample.
- `local-project` — this checkout (bundled sample). No clone, no patch.

## Runtime trust cases

Every `runtime-trust` case has `trustPurpose`: the failure mode it protects.
Cases may assert a positive candidate, or that confidence stays low/medium,
a warning stays present, or an exact-source claim is not made.

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

Interop-risk cases should land on the boundary host and require
`POSSIBLE_VIEW_INTEROP`. Shared-component cases should assert
`expectedRecommendedEditSiteContains` only when a single
`recommendedEditSite=true` call site is emitted.

## Commands

```bash
npm run source-matching:fixtures:prepare
npm run source-matching:fixtures
npm run source-matching:fixtures:runtime
npm run source-matching:fixtures:runtime -- --strict
npm run source-matching:fixtures:report
npm run source-matching:fixtures:test
```

`fixthis-sample-copy-data-source-index` keeps copy/data text visible in the
index without a device. Layout-renderer evidence is covered by
`KotlinSourceScannerTest` and `SourceMatcherTest`.

Initial runtime set: Reply (external happy path), bundled sample (controlled
identity), Jetsnack (resource-backed launch). Now in Android stays
source-index-only until a stable launch-state selector exists.

## Reading results

```text
build/reports/fixthis-source-matching/report.json
build/reports/fixthis-source-matching/report.md
```

Useful labels: `missing_top3`, `wrong_top1`, `overconfident`,
`underconfident`, `missing_warning`, `unexpected_high_confidence`,
`target_not_found`, `target_ambiguous`, `fixture_build_failed`,
`source_index_missing`.

Schema v2: source-index cases cannot contain runtime trust expectations.
Missing runtime observations fail. `--strict` turns environment downgrades
into a non-zero gate.

If a multi-module case produces `missing_top3`, either the matcher
regressed or the upstream `:app → :feature` graph drifted. Inspect
`.fixthis/eval-fixtures/work/<fixture>/`.

Treat outcomes as classifications: product failure, docs drift, environment
downgrade, strict runtime failure, fixture drift, or a caveated pass
(`SHARED_COMPONENT`, `VISUAL_AREA_ONLY`, `POSSIBLE_VIEW_INTEROP`).
