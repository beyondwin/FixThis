# Unreleased changes

This page summarizes current `main`. It is not a tagged release; use the
GitHub Release page and registry listings as release evidence.

`CHANGELOG.md` remains the chronological source of truth.

## Highlights

- Agent setup and run commands now handle flavored Android application ids
  end-to-end. Gradle `applicationIdSuffix` values are considered installable
  candidates, combined flavor/build-type suffixes are detected, and explicit
  `--package` overrides no longer reuse stale variant metadata from
  `.fixthis/project.json`.
- Source matching is stronger for real Compose projects. The source index now
  scans active-variant project dependencies, records typed semantic signals for
  Compose text/content-description/role/test-tag/resource usage, and gives
  selected `stateDescription` evidence a medium-confidence source hint.
- The debug sidekick and sample app now carry `minSdk` 23 compatibility, with
  the docs and compatibility checks aligned to that lower bound.
- Release guardrails now cover the Homebrew tap and release helper fixtures in
  addition to the existing version-sync checks, reducing the chance of shipping
  stale package coordinates or fixture-only hardcoded versions.
- Generated MCP config is less brittle for Homebrew installs because it avoids
  versioned Cellar executable paths when the stable `fixthis` command is
  available.

## Compatibility Notes

- External Android apps should use Gradle plugin
  `io.github.beyondwin.fixthis.compose` version `0.6.1`.
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
