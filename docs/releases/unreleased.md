# Unreleased changes

This page summarizes current `main`. It is not a tagged release; use the
GitHub Release page and registry listings as release evidence.

`CHANGELOG.md` remains the chronological source of truth.

## Highlights

- Release version drift is now guarded end-to-end. The local package version
  source of truth remains `gradle.properties` (`FIXTHIS_VERSION`), and
  `npm run release:version:sync` / `npm run release:version:check` keep public
  install docs, CLI defaults, npm metadata, and MCP Registry metadata aligned.
- CLI/MCP release packaging now passes the requested version into Gradle with
  `-PFIXTHIS_VERSION=...`, so release tarballs and workflow-built packages
  report the version being cut.
- Android SDK levels are centralized in the version catalog and validated by
  `npm run release:compat:test`. The sidekick AAR bundle gate checks
  `minCompileSdk` against the same catalog value before producing Central
  Portal upload artifacts.
- The next patch release keeps the debug sidekick consumable by apps compiling
  with Android 14 (`compileSdk` 34). The CLI still warns when explicitly asked
  to install the previous patch runtime, whose dependency metadata requires
  `compileSdk` 36.
- The Gradle Plugin Portal workflow now tests the requested version contract
  before publishing, reducing the chance of publishing plugin metadata that
  points at the wrong runtime version.

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
