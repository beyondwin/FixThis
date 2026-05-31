# Unreleased changes

This page summarizes current `main`. It is not a tagged release; use the
GitHub Release page and registry listings as release evidence.

`CHANGELOG.md` remains the chronological source of truth.

## Highlights

This cycle's user-facing highlights are now captured in the latest per-release
notes under [`docs/releases/`](.) — covering the layout-renderer edit hints,
shared-component call-site ranking, fallback-only console polling, doctor
`--json` readiness, and the Cursor MCP target. `CHANGELOG.md` remains the full
chronological record.

`main` carries one fix beyond the latest per-release notes: `fixthis
install-agent --target all` run outside an Android project now retains the
project-local Cursor config instead of silently dropping it — the global-scope
guard falls back to a new `local` target (`claude` + `cursor`) and reports only
the global Codex target as skipped. See `CHANGELOG.md` for details.

Current `main` carries the v1.1 Trust Loop evidence pack: release reality
checks, external agent lifecycle smoke, and runtime source-trust calibration.
It is a post-v1.0 hardening line and does not claim a new tagged release until
the evidence commands pass or are explicitly deferred in the release issue.

The next evidence line adds a release gate report, deeper interop boundary
context, and explicit SSE reliability reporting. These changes keep
AndroidView/WebView-risk handoffs caveated while giving maintainers one local
artifact for release decisions.

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

For named local evidence reports, use:

```bash
npm run evidence:fast -- --dry-run
npm run evidence:test
```

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
