# Release Process

How to cut a FixThis release. Until Maven Central / Gradle Plugin Portal
publication is enabled, releases are GitHub source releases: a commit on
`main`, an annotated `vX.Y.Z` tag, and a GitHub Release whose notes point to
the matching CHANGELOG section. This document also serves as the working draft
for the eventual artifact-publication playbook — see
[`release-readiness.md`](release-readiness.md) for what's still blocking that
publish.

## Versioning

FixThis follows [Semantic Versioning 2.0](https://semver.org/spec/v2.0.0.html).
The full policy is at the top of [`CHANGELOG.md`](../../CHANGELOG.md).

The bridge wire protocol carries its own `BridgeProtocol.VERSION`
independent of the package version — bumped per the checklist in
[`docs/reference/bridge-protocol.md`](../reference/bridge-protocol.md).

## Where the version lives

For source-only GitHub releases, the annotated tag and CHANGELOG heading are
the public version source of truth. When Gradle artifact publishing is enabled,
the package version will also be set in the root build configuration:

| Location | Bumped on | Notes |
|----------|-----------|-------|
| `gradle.properties` (`version=…`) | every release | The single source of truth for `:fixthis-compose-sidekick`, `:fixthis-gradle-plugin`, `:fixthis-cli`, `:fixthis-mcp`. |
| `BridgeProtocol.VERSION` | wire-visible bridge changes only | Independent of package version. |
| `CHANGELOG.md` | every release | Move "Unreleased" entries under a new dated heading. |

> The `gradle.properties` `version` line is added as part of the publish-to-Maven
> work. Until then, the repo carries no externally-visible Gradle package
> version string and consumers wire FixThis via composite build. GitHub source
> releases are still versioned by annotated tags.

## Pre-release checklist

Before tagging:

- [ ] Full Gradle matrix passes: `./gradlew spotlessCheck detekt :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest :fixthis-gradle-plugin:test :app:assembleDebug :fixthis-cli:installDist :fixthis-mcp:installDist --no-daemon`
- [ ] Console asset bundle is current: `node scripts/build-console-assets.mjs --check`
- [ ] Console JS syntax check passes: `node --check fixthis-mcp/src/main/resources/console/app.js`
- [ ] Console JS harnesses pass: `node --test scripts/console-availability-test.mjs scripts/pendingItemRecovery-test.mjs scripts/beforeunloadGuard-test.mjs scripts/undoRedo-test.mjs scripts/undoKeymatch-test.mjs scripts/activityDrift-test.mjs scripts/previewStaleness-test.mjs`
- [ ] Whitespace clean: `git diff --check`
- [ ] Gradle help has no deprecation warnings when build logic changed: `./gradlew help --warning-mode all --no-daemon`
- [ ] Connected smoke harness on a real device or unlocked emulator: `scripts/fixthis-smoke.sh --package io.beyondwin.fixthis.sample`
- [ ] CI green on `main` for the commit you're about to tag: see `.github/workflows/ci.yml`
- [ ] `CHANGELOG.md` "Unreleased" section reviewed for accuracy
- [ ] No `TODO` / `FIXME` / `// TEMP` markers in code paths shipped this release (`grep -rn TODO src/main`)
- [ ] [`release-readiness.md`](release-readiness.md) blockers all checked

## Cut a release

1. Move CHANGELOG `Unreleased` entries under a new dated heading:

   ```markdown
   ## [0.2.0] - 2026-MM-DD

   ### Added
   - …
   ```

   Leave a fresh empty `## Unreleased` block at the top.

2. Create or update the matching human release note under `docs/releases/`.

3. (Once publishing is enabled) bump `version=` in `gradle.properties`.

4. Commit:

   ```bash
   git add CHANGELOG.md docs/releases
   git commit -m "release: 0.2.0"
   ```

   Include `gradle.properties` in the staged files once artifact publishing is
   enabled.

5. Tag:

   ```bash
   git tag -a v0.2.0 -m "FixThis 0.2.0"
   git push origin main v0.2.0
   ```

6. Create the GitHub Release from the tag using the new CHANGELOG section as
   the release body:

   ```bash
   gh release create v0.2.0 --title "FixThis v0.2.0" --notes-file docs/releases/v0.2.0.md
   ```

7. (Once publishing is enabled) publish artifacts:

   ```bash
   ./gradlew :fixthis-compose-sidekick:publish :fixthis-gradle-plugin:publishPlugins
   ```

## Post-release

- Verify the GitHub Release appears on the repository Releases page.
- If artifact publishing is enabled, verify Maven Central / Plugin Portal
  listings appear and are installable.
- Bump README compatibility matrix if needed for the next development cycle.
- File follow-up issues for anything deferred from this release.

## Hotfixes

For urgent fixes against a released version:

1. Branch from the release tag: `git checkout -b hotfix/0.2.1 v0.2.0`
2. Apply the minimal fix.
3. Bump the patch version, update CHANGELOG, tag, push.
4. Cherry-pick the fix back into `main` if `main` has diverged.

## See also

- [`CONTRIBUTING.md`](../../CONTRIBUTING.md) — local PR checks.
- [`release-readiness.md`](release-readiness.md) — what's still blocking the
  first external publish.
- [`docs/reference/bridge-protocol.md`](../reference/bridge-protocol.md) —
  bridge-version bump procedure (independent of package version).
- [`CHANGELOG.md`](../../CHANGELOG.md) — versioning policy and history.
