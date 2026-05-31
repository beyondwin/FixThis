# Unreleased changes

This page summarizes current `main`. It is not a tagged release; use the
GitHub Release page and registry listings as release evidence.

`CHANGELOG.md` remains the chronological source of truth.

## Highlights

No unreleased changes have landed after v1.1.0 yet. See
[`v1.1.0.md`](v1.1.0.md) and `CHANGELOG.md` for the latest tagged release
summary.

## Compatibility Notes

- External Android apps should use Gradle plugin
  `io.github.beyondwin.fixthis.compose` version `1.1.0`.
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
- Persisted JSON changes are additive. Older sessions may omit fields such as
  `editSurfaceCandidates[].role`, `editSurfaceCandidates[].confidenceBasis`,
  ranked shared-component call-site markers, and interop boundary context rows.
- Current source-index assets use schema version `1.2` and preserve the legacy
  source-index field lists while adding typed `signals`.

## Validation Surface

Before tagging the next release, run the current contributor checklist in
[`CONTRIBUTING.md`](../../CONTRIBUTING.md). The top-level local mirror is:

```bash
npm run ci:local
```

For named local evidence reports, use:

```bash
npm run evidence:fast -- --dry-run
npm run evidence:test
npm run agent-loop:smoke:test
npm run release:gate
npm run release:gate:test
```

When validating Trust Loop Completion on a connected Android device or unlocked
emulator, also run:

```bash
npm run source-matching:fixtures:runtime -- --strict
npm run agent-loop:smoke -- --strict
npm run real-copy-prompt:smoke -- --strict
```

If the Android environment is unavailable, attach the non-strict release-gate
report and record the connected evidence as deferred with the report reason.

`npm run ci:local` covers the required Gradle matrix, release-readiness checks,
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
