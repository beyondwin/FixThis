# Unreleased changes

This page summarizes current `main` after the latest tagged release. It is not a
tagged release; use the GitHub Release page and registry listings as release
evidence. It is not a tagged release.

`CHANGELOG.md` remains the chronological source of truth.

## Highlights

- No unreleased changes yet.

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
npm run release:gate
npm run release:gate:test
node scripts/check-release-readiness.mjs
```

For v0.6-style claims, keep the release notes narrowed to evidence that has a
fresh passing command result. If the Handoff Intelligence, Studio Reliability,
or Release Grade evidence is missing, remove or narrow the corresponding claim
before tagging.

Connected Android evidence remains local-only and is recorded through
`npm run android:proof -- --strict` plus the release-gate report. If Android SDK
or an unlocked emulator is unavailable, attach the non-strict release-gate report
and record the connected evidence as deferred with the proof report reason.
