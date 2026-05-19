# Release Process

How to cut a FixThis release.

FixThis releases can ship GitHub release assets, Homebrew formulas, Maven
Central artifacts, Gradle Plugin Portal artifacts, npm wrappers, and MCP
Registry metadata. Publishing still requires explicit maintainer approval
because it uses signing keys and registry tokens.

See [`release-readiness.md`](release-readiness.md) before changing public
install instructions.

## Release Types

| Type | What ships | Status |
| --- | --- | --- |
| GitHub release | Git tag, GitHub Release, release notes, CLI/MCP tarball | Supported today |
| Artifact release | Maven Central and/or Gradle Plugin Portal artifacts | Supported through manual workflows |
| Homebrew release | Tap formula update pointing at the GitHub Release CLI/MCP tarball | Supported today |
| npm wrapper release | Public `@beyondwin/fixthis` npm package that downloads the matching GitHub Release CLI/MCP tarball | Supported through manual workflow |
| MCP Registry release | Registry metadata pointing to the public npm wrapper | Supported through manual workflow after npm publish |

## Versioning

FixThis follows [Semantic Versioning 2.0](https://semver.org/spec/v2.0.0.html).
The full policy is at the top of [`CHANGELOG.md`](../../CHANGELOG.md).

The bridge wire protocol carries its own `BridgeProtocol.VERSION`
independent of the package version — bumped per the checklist in
[`docs/reference/bridge-protocol.md`](../reference/bridge-protocol.md).

## Where the version lives

`gradle.properties` (`FIXTHIS_VERSION=…`) is the editable local source of
truth for the package version. After changing it, run
`npm run release:version:sync` to update npm, MCP registry metadata, and current
install docs. `npm run release:version:check` fails if any generated/default
version site drifts.

The annotated tag and CHANGELOG heading still record the public release. Gradle
artifact workflows receive the same version through `-PFIXTHIS_VERSION`.

| Location | Bumped on | Notes |
|----------|-----------|-------|
| `gradle.properties` (`FIXTHIS_VERSION=…`) | every release | The local artifact version source of truth for root publications such as `:fixthis-compose-sidekick` and `:fixthis-compose-core`. |
| `BridgeProtocol.VERSION` | wire-visible bridge changes only | Independent of package version. |
| `CHANGELOG.md` | every release | Move "Unreleased" entries under a new dated heading. |

## Branch-protection policy

`main` is protected and requires four GitHub Actions status checks
(`CI`, `CodeQL`, `Console JS`, `Gradle verification`) before merge.

- **Normal flow:** open a PR, let the four checks run, merge through the
  GitHub UI. Do not push directly to `main`.
- **Release / hotfix surge (rare):** maintainers with admin rights may push
  release-prep commits straight to `main` to keep the tag, GitHub Release,
  and dispatch workflows on the same SHA. Every bypassed push must come
  with the `npm run ci:local` (`--full`) gate green locally and is logged
  in the GitHub audit log as `Bypassed rule violations for refs/heads/main`.
  Document the reason in the release issue or commit body so the bypass
  trail is reviewable after the fact.

When in doubt, route through PR. The cache-safe `--full` gate is the only
local approximation of the protected-branch checks; cache poisoning has
been observed to mask real failures (see `staleMarkerSuffix` on
2026-05-18), so the bypass path is meant for release-coordination,
not for skipping inconvenient feedback.

## Pre-release checklist

Before tagging:

- [ ] Full Gradle matrix passes: `./gradlew spotlessCheck detekt :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest :fixthis-gradle-plugin:test :app:assembleDebug :fixthis-cli:installDist :fixthis-mcp:installDist --no-daemon`
- [ ] Console asset bundle is current: `node scripts/build-console-assets.mjs --check`
- [ ] Console JS syntax check passes: `node --check fixthis-mcp/src/main/resources/console/app.js`
- [ ] Console JS harnesses pass: `npm run console:test:all` (the group-to-file source of truth is `scripts/console-tests.json`)
- [ ] Console browser smoke passes when console layout, connection, or handoff UI changed: `npm run console:smoke`
- [ ] Console responsive stress passes when layout, global status, activity-drift, or agent-state UI changed: `npm run console:responsive:stress`
- [ ] CLI/MCP package script tests pass: `npm run release:package:test`
- [ ] Whitespace clean: `git diff --check`
- [ ] Gradle help has no deprecation warnings when build logic changed: `./gradlew help --warning-mode all --no-daemon`
- [ ] Connected smoke harness on a real device or unlocked emulator: `scripts/fixthis-smoke.sh --package io.github.beyondwin.fixthis.sample`
- [ ] CI green on `main` for the commit you're about to tag: see `.github/workflows/ci.yml`
- [ ] `CHANGELOG.md` "Unreleased" section reviewed for accuracy
- [ ] No `TODO` / `FIXME` / `// TEMP` markers in code paths shipped this release (`grep -rn TODO src/main`)
- [ ] [`release-readiness.md`](release-readiness.md) blockers all checked

## Cut a GitHub Release

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

   For a v0.6 release, copy
   [`v06-release-evidence-template.md`](v06-release-evidence-template.md) into the
   release issue and fill every command result before finalizing release notes.
   Claims without evidence are removed or narrowed before tagging.

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

6. Build and attach the CLI/MCP package. The `Release CLI/MCP Package`
   workflow runs on `v*.*.*` tags and creates draft release assets:

   - `fixthis-cli-mcp-X.Y.Z.tar.gz`
   - `fixthis-cli-mcp-X.Y.Z.tar.gz.sha256`

   The shell installer (`scripts/install-fixthis.sh`) and the npm postinstall
   verify the SHA-256 sidecar before extraction, so both files must be
   attached together. If running locally instead:

   ```bash
   ./scripts/package-cli-release.sh --version v0.2.0
   gh release upload v0.2.0 \
       build/release/fixthis-cli-mcp-v0.2.0.tar.gz \
       build/release/fixthis-cli-mcp-v0.2.0.tar.gz.sha256 --clobber
   ```

7. Create or update the GitHub Release from the tag using the new CHANGELOG section as
   the release body:

   ```bash
   gh release create v0.2.0 --title "FixThis v0.2.0" --notes-file docs/releases/v0.2.0.md
   ```

## Artifact Release Gate

Before publishing Maven Central or Gradle Plugin Portal artifacts:

1. Verify Maven Central namespace ownership for `io.github.beyondwin`.
2. Verify Gradle Plugin Portal ownership for `io.github.beyondwin.fixthis.compose`.
3. Configure signing and publishing secrets outside the repository.
4. Run local/dry-run packaging validation:

   ```bash
   scripts/create-central-portal-bundle.sh X.Y.Z build/central-repo build/central-bundle.zip
   ./gradlew -p fixthis-gradle-plugin publishToMavenLocal --dry-run --no-daemon
   ./gradlew :fixthis-gradle-plugin:validatePlugins --no-daemon
   ```

5. Publish from an explicitly approved manual workflow or local maintainer
   machine. Maven Central artifacts are published through
   `.github/workflows/publish-maven-central.yml`, which builds a signed Central
   Portal bundle for `:fixthis-compose-core`, `:fixthis-compose-sidekick`,
   `:fixthis-gradle-plugin`, and the Gradle plugin marker publication, uploads
   it to the Publisher API, and waits for the deployment to reach `PUBLISHED`.
6. Verify from a clean external consumer project that:

   ```kotlin
   plugins {
       id("io.github.beyondwin.fixthis.compose") version "X.Y.Z"
   }

   dependencies {
       debugImplementation("io.github.beyondwin:fixthis-compose-sidekick:X.Y.Z")
   }
   ```

   resolves from public registries.

7. After verification, update README and getting-started docs if the public
   install command changes.

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
- Verify Maven Central / Plugin Portal listings appear and are installable when
  artifact workflows were run.
- Verify Homebrew when the tap was updated:
  `brew upgrade beyondwin/tools/fixthis && brew test beyondwin/tools/fixthis`.
- Verify npm only after `NPM_TOKEN` is configured:
  `npm view @beyondwin/fixthis version dist.tarball`.
- Verify MCP Registry only after npm is public:
  `mcp-publisher validate`, then registry search for
  `io.github.beyondwin/fixthis`.
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
- [`release-readiness.md`](release-readiness.md) — current publication and
  registry status.
- [`docs/reference/bridge-protocol.md`](../reference/bridge-protocol.md) —
  bridge-version bump procedure (independent of package version).
- [`CHANGELOG.md`](../../CHANGELOG.md) — versioning policy and history.
