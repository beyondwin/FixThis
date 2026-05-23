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
- `wrong_top1`: a case required a top-1 match and the first candidate did not match.
- `missing_source_signal`: expected typed source signal was missing.
- `overconfident`: observed confidence was higher than the case allowed.
- `underconfident`: observed confidence was lower than the case expected.
- `missing_warning`: a required target warning was absent.
- `unexpected_warning`: a warning that should not appear was present.
- `unexpected_high_confidence`: a case marked `mustNotHighConfidence` became high confidence.
- `weak_evidence_promoted`: weak evidence carried a risk or warning but still became high confidence.
- `fixture_build_failed`: the external fixture did not build in this local environment.
- `source_index_missing`: Gradle completed without producing the expected FixThis source index.
- `fixture_drift`: the pinned upstream fixture no longer matches the case contract and should be re-pinned or corrected.
- `case_contract_invalid`: the committed manifest case is invalid.

Device-backed capture is not enabled in the first local lab implementation.
Reports mark it as `not_configured`.

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
