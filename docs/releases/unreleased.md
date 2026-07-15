# Unreleased changes

This page tracks user-visible changes after v1.5.0.
It is not a tagged release. Use the GitHub Release page and registry listings
as release evidence.

`CHANGELOG.md` remains the chronological source of truth.

## Highlights

- **Accurate Gradle Plugin Portal recovery.** Release automation now verifies
  the exact `io.github.beyondwin.fixthis.compose` version page instead of the
  Portal `/m2/` proxy marker, which can appear before the plugin listing is
  actually public. HTTP 400 and 404 responses remain the expected
  not-yet-published signals; other unexpected responses defer the check rather
  than triggering an unsafe duplicate publication attempt.

## Compatibility Notes

- External Android apps should use the latest tagged Gradle plugin release until
  the next release candidate updates this page.
- The plugin resolves the debug-only sidekick from Maven Central.
- The current source tree builds the sidekick and sample with Android
  `compileSdk` 34, `targetSdk` 34, and `minSdk` 23 from
  `gradle/libs.versions.toml`.
- The current source tree uses Compose BOM `2025.01.01` and Compose UI test
  artifacts `1.7.8` to keep the debug sidekick consumable by Android
  14-pinned apps.
- Bridge protocol version is `1.3`.
- Runtime evidence is a host ADB/CLI capability. It does not add an app-side
  bridge capability and does not change Bridge protocol `1.3`.

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
npm run release:drift
npm run release:gate:test
node scripts/check-release-readiness.mjs
```

Features that require connected Android proof must pass both focused and
aggregate strict validation before release notes claim them as delivered:

```bash
npm run runtime-evidence:smoke:test
npm run runtime-evidence:smoke -- --strict
npm run android:proof -- --strict
npm run external-fixture:matrix -- --strict
npm run release:check
```

The focused strict run must call `fixthis_collect_runtime_evidence` and verify
artifact containment/redaction, item linkage, Auto Save-to-MCP, and restart
replay. Deferred output or generic direct logcat capture is not connected
product-path proof.

For v0.6-style claims and later feature claims, keep release notes narrowed to
evidence that has a fresh passing command result. If required product-path
evidence is missing, remove or narrow the corresponding claim before tagging.

Connected Android evidence remains local-only and is recorded through
`npm run android:proof -- --strict` plus the release-gate report. If Android SDK
or an unlocked emulator is unavailable, attach the non-strict release-gate report
and record the connected evidence as deferred with the proof report reason.
The strict release gate also verifies public tags and registries, so it becomes
a required green post-release check only after every intended channel is live.
