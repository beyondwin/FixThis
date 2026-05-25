# Unreleased changes

This page summarizes current `main`. It is not a tagged release; use the
GitHub Release page and registry listings as release evidence.

`CHANGELOG.md` remains the chronological source of truth.

## Highlights

- Compose `Layout(...)` / `SubcomposeLayout(...)` call sites are now indexed
  as a typed `LAYOUT_RENDERER` signal and surfaced as medium-confidence
  edit-surface hints for strict `comp:` test-tag selections, with conservative
  scanning rules that avoid false signals from comments, strings, declarations,
  and same-name non-Compose locals.
- Compact handoff preserves the source-matching confidence token on the
  rank-1 candidate and renders trust caveats as a `note:` line, making
  source-matching trust legible from the compact prompt.
- The local source-matching fixture lab classifies trust regressions, fails
  on unmatched confidence expectations, and now supports a runtime trust
  evaluation mode that exercises `RuntimeTargetResolver` against fixture
  indexes (plus a strict local gate invocation for CI-like enforcement).
  See [`docs/guides/source-matching-fixture-lab.md`](../guides/source-matching-fixture-lab.md).
- Bug fixes preserve ambiguous-owner candidate confidence, original
  fixture candidate order, and the `UNTYPED_FALLBACK` risk on weak
  owner-function matches; owner-function source evidence is now explicitly
  capped at medium confidence.

## Compatibility Notes

- External Android apps should use Gradle plugin
  `io.github.beyondwin.fixthis.compose` version `0.7.0`.
- The plugin resolves the debug-only sidekick from Maven Central.
- The current source tree builds the sidekick and sample with Android
  `compileSdk` 34, `targetSdk` 34, and `minSdk` 23 from
  `gradle/libs.versions.toml`.
- The current source tree uses Compose BOM `2025.01.01` and Compose UI test
  artifacts `1.7.8` to keep the debug sidekick consumable by Android
  14-pinned apps.
- Homebrew installs the matching CLI/MCP GitHub Release package on macOS.
- npm installs the matching CLI/MCP GitHub Release package through
  `@beyondwin/fixthis`.
- Bridge protocol version is `1.3`.
- Persisted JSON changes are additive. `editSurfaceCandidates[].role` is
  optional and older sessions omit it.
- Current source-index assets use schema version `1.2` and preserve the legacy
  source-index field lists while adding typed `signals`.

## Validation Surface

Before tagging the next release, run the current contributor checklist in
[`CONTRIBUTING.md`](../../CONTRIBUTING.md). The top-level local mirror is:

```bash
npm run ci:local
```

That command covers the required Gradle matrix, release-readiness checks,
console bundle freshness, console JS tests, package installer tests, and
whitespace checks. If a release changes CLI commands or agent-facing setup
docs, also run:

```bash
npm run docs:agent-bootstrap:test
bash scripts/check-docs-cli-surface.sh
npm run release:version:check
```

If Android SDK or Compose compatibility docs change, also run:

```bash
npm run release:compat:test
```

For console layout or agent-state UI changes, also run:

```bash
npm run console:smoke
npm run console:responsive:stress
npm run console:reliability:test
npm run console:browser:reliability
```

For v0.6-style release claims, also run and capture:

```bash
npm run handoff:eval:test
npm run console:browser:reliability
npm run release:v06:evidence:test
node scripts/check-release-readiness.mjs
npm run checks:observation -- --json
```
