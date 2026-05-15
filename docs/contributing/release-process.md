# Release Process

How to cut a FixThis release.

FixThis currently supports **source releases**: a commit on `main`, an
annotated `vX.Y.Z` tag, and a GitHub Release whose notes point to the matching
CHANGELOG section. Maven Central and Gradle Plugin Portal publication are
future **artifact releases** and must stay behind explicit maintainer approval
until the readiness checklist is complete.

See [`release-readiness.md`](release-readiness.md) before changing public
install instructions.

## Release Types

| Type | What ships | Status |
| --- | --- | --- |
| Source release | Git tag, GitHub Release, release notes, source/composite-build usage | Supported today |
| Artifact release | Maven Central and/or Gradle Plugin Portal artifacts | Publish-ready prep only |
| MCP Registry release | Registry metadata pointing to a public package or remote server | Future work after an install package exists |

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
| `gradle.properties` (`FIXTHIS_VERSION=…`) | every release | The local artifact version source of truth for root publications such as `:fixthis-compose-sidekick` and `:fixthis-compose-core`. |
| `BridgeProtocol.VERSION` | wire-visible bridge changes only | Independent of package version. |
| `CHANGELOG.md` | every release | Move "Unreleased" entries under a new dated heading. |

> The `gradle.properties` `FIXTHIS_VERSION` value supports local packaging
> rehearsal only. Until external artifact publishing is explicitly enabled,
> consumers still wire FixThis via source or composite build. GitHub source
> releases remain versioned by annotated tags.

## Pre-release checklist

Before tagging:

- [ ] Full Gradle matrix passes: `./gradlew spotlessCheck detekt :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest :fixthis-gradle-plugin:test :app:assembleDebug :fixthis-cli:installDist :fixthis-mcp:installDist --no-daemon`
- [ ] Console asset bundle is current: `node scripts/build-console-assets.mjs --check`
- [ ] Console JS syntax check passes: `node --check fixthis-mcp/src/main/resources/console/app.js`
- [ ] Console JS harnesses pass: `npm run console:test:all` (the group-to-file source of truth is `scripts/console-tests.json`)
- [ ] Console browser smoke passes when console layout, connection, or handoff UI changed: `npm run console:smoke`
- [ ] Console responsive stress passes when layout, global status, activity-drift, or agent-state UI changed: `npm run console:responsive:stress`
- [ ] Whitespace clean: `git diff --check`
- [ ] Gradle help has no deprecation warnings when build logic changed: `./gradlew help --warning-mode all --no-daemon`
- [ ] Connected smoke harness on a real device or unlocked emulator: `scripts/fixthis-smoke.sh --package io.beyondwin.fixthis.sample`
- [ ] CI green on `main` for the commit you're about to tag: see `.github/workflows/ci.yml`
- [ ] `CHANGELOG.md` "Unreleased" section reviewed for accuracy
- [ ] No `TODO` / `FIXME` / `// TEMP` markers in code paths shipped this release (`grep -rn TODO src/main`)
- [ ] [`release-readiness.md`](release-readiness.md) blockers all checked

## Cut a Source Release

1. Move CHANGELOG `Unreleased` entries under a new dated heading:

   ```markdown
   ## [0.2.0] - 2026-MM-DD

   ### Added
   - Release readiness documentation and validation.
   ```

   Leave a fresh empty `## Unreleased` block at the top.

2. Create the matching human release note under `docs/releases/`:

   ```bash
   cp docs/releases/unreleased.md docs/releases/v0.2.0.md
   ```

   Edit the copied file so it describes the tagged release, not current `main`.

3. Run the release-readiness checks:

   ```bash
   node scripts/check-doc-consistency.mjs
   node scripts/check-release-readiness.mjs
   git diff --check
   ```

4. Commit:

   ```bash
   git add CHANGELOG.md docs/releases README.md docs/contributing
   git commit -m "release: 0.2.0"
   ```

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

## Future Artifact Release Gate

Do not run this section until `release-readiness.md` marks external artifact
release prerequisites complete.

Before changing README install instructions to public Gradle coordinates:

1. Verify Maven Central namespace ownership for `io.beyondwin.fixthis`.
2. Verify Gradle Plugin Portal ownership for `io.beyondwin.fixthis.compose`.
3. Configure signing and publishing secrets outside the repository.
4. Run local/dry-run packaging validation:

   ```bash
   ./gradlew publishToMavenLocal --dry-run --no-daemon
   ./gradlew -p fixthis-gradle-plugin publishToMavenLocal --dry-run --no-daemon
   ./gradlew :fixthis-gradle-plugin:validatePlugins --no-daemon
   ```

5. Publish from an explicitly approved manual workflow or local maintainer
   machine.
6. Verify from a clean external consumer project that:

   ```kotlin
   plugins {
       id("io.beyondwin.fixthis.compose") version "X.Y.Z"
   }

   dependencies {
       debugImplementation("io.beyondwin.fixthis:fixthis-compose-sidekick:X.Y.Z")
   }
   ```

   resolves from public registries.

7. Only after verification, update README and getting-started docs from
   source/composite-build setup to published-artifact setup.

### Publish Prep Validation

These commands are safe because they do not publish remote artifacts:

```bash
./gradlew publishToMavenLocal --dry-run --no-daemon
./gradlew -p fixthis-gradle-plugin publishToMavenLocal --dry-run --no-daemon
./gradlew :fixthis-gradle-plugin:validatePlugins --no-daemon
```

The root command covers `fixthis-compose-core` and the debug
`fixthis-compose-sidekick` AAR. The separate `-p fixthis-gradle-plugin` command
covers the included plugin build without enabling remote publication.

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
