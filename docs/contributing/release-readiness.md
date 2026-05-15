# Release Readiness

FixThis is usable today as a GitHub source release and from `main` through the
sample app, local MCP bootstrap, and Gradle composite-build wiring. Public
Gradle artifacts are not published yet.

This page is the release dashboard. It separates what users can do today from
what must be true before the README can advertise Maven Central or Gradle
Plugin Portal installation.

## Current Status

| Surface | Status | User impact |
| --- | --- | --- |
| GitHub source release | Available from `v0.1.0` | Users can inspect tagged source and release notes. |
| Current `main` | Unreleased hardening after `v0.1.0` | Users can try latest docs/code from source. |
| Sample app | Available | Users can verify the workflow before touching their app. |
| Claude Code / Codex MCP bootstrap | Available from source | `./scripts/bootstrap-mcp.sh --sample` configures local MCP for the sample. |
| CLI/MCP GitHub Release package | Prepared for next release | `scripts/package-cli-release.sh` builds the asset; `scripts/install-fixthis.sh` installs it. |
| External Gradle artifacts | Maven Central workflow prepared; not published | External apps must use source/composite-build wiring until registry verification passes. |
| MCP Registry entry | Not published | Discovery remains through the GitHub repository and docs. |

## Supported Install Paths Today

- Clone the repository and run the sample app.
- Use `./scripts/bootstrap-mcp.sh --sample` to register the local MCP server
  with Claude Code or Codex.
- Build a local CLI/MCP package with `scripts/package-cli-release.sh --version
  <version>` and test agent installation with `scripts/install-fixthis.sh
  --archive <tarball>`.
- Use Gradle composite-build or local repository wiring for an external debug
  Compose app.
- Use **Copy Prompt** for chat-style agents without MCP.

## Not Published Yet

The following install paths are intentionally not advertised as ready:

- Maven Central dependency for `io.github.beyondwin:fixthis-compose-sidekick`.
- Gradle Plugin Portal entry for `io.github.beyondwin.fixthis.compose`.
- npm/PyPI/Docker package for the MCP server.
- MCP Registry metadata entry.

Do not change README install instructions to published Gradle coordinates until
the artifacts are visible in their public registries and verified from a clean
consumer project.

## Required Before Next Source Release

- [ ] Full PR checks pass on the release commit.
- [ ] `node scripts/check-doc-consistency.mjs` passes.
- [ ] `node scripts/check-release-readiness.mjs` passes.
- [ ] `git diff --check` passes.
- [ ] `CHANGELOG.md` entries are reviewed and moved under the release heading.
- [ ] `docs/releases/vX.Y.Z.md` exists and matches the changelog summary.
- [ ] `docs/releases/unreleased.md` is reset for the next cycle after tagging.
- [ ] Connected smoke is run on a real device or unlocked emulator, or the
      release notes explicitly say it was not run.
- [ ] CLI/MCP package workflow has produced the release tarball, or the release
      notes explicitly say no desktop package is attached.
- [ ] Security, privacy, compatibility, and troubleshooting docs still match
      the release claims.

## Required Before External Artifact Release

- [ ] Public group and artifact coordinates are final:
      `io.github.beyondwin:fixthis-compose-sidekick` and
      `io.github.beyondwin:fixthis-compose-core` if core is published.
- [ ] Gradle plugin id remains `io.github.beyondwin.fixthis.compose`.
- [x] One local version source of truth is established for root published
      modules: `FIXTHIS_GROUP` and `FIXTHIS_VERSION` in `gradle.properties`.
- [x] Gradle publishing plugins are configured for local dry-run validation.
- [x] Local dry-run packaging is verified:
      `./gradlew publishToMavenLocal --dry-run --no-daemon` for root artifacts
      and `./gradlew -p fixthis-gradle-plugin publishToMavenLocal --dry-run
      --no-daemon` for the included Gradle plugin build.
- [x] Maven Central manual workflow exists for `fixthis-compose-core` and
      `fixthis-compose-sidekick`.
- [ ] Gradle Plugin Portal validation passes for `:fixthis-gradle-plugin`.
- [ ] `./gradlew :fixthis-gradle-plugin:validatePlugins` succeeds.
- [ ] Maven Central namespace ownership is verified by the maintainer.
- [ ] Gradle Plugin Portal account ownership is verified by the maintainer.
- [ ] Signing and publishing secrets are configured outside the repository.
- [ ] A clean external sample project resolves the published artifacts from
      public registries.
- [ ] README install instructions are updated only after registry verification.

## Future Coordinates

| Surface | Future coordinate / id | Registry |
| --- | --- | --- |
| Gradle plugin | `io.github.beyondwin.fixthis.compose` | Gradle Plugin Portal |
| Compose sidekick | `io.github.beyondwin:fixthis-compose-sidekick` | Maven Central |
| Compose core | `io.github.beyondwin:fixthis-compose-core` | Maven Central, only if needed by consumers |
| MCP server | GitHub Release asset `fixthis-cli-mcp-vX.Y.Z.tar.gz` | Future MCP Registry metadata after the package is verified |

## Required Maintainer Secrets

These names document the default expected CI inputs. They are not committed and
can be renamed before real publication is enabled.

| Secret | Purpose |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Maven Central publishing identity or token user. |
| `MAVEN_CENTRAL_PASSWORD` | Maven Central publishing password or token secret. |
| `SIGNING_KEY` | ASCII-armored signing key for Maven artifacts. |
| `SIGNING_PASSWORD` | Password for the signing key. |
| `GRADLE_PUBLISH_KEY` | Gradle Plugin Portal publish key. |
| `GRADLE_PUBLISH_SECRET` | Gradle Plugin Portal publish secret. |

## Evidence Links

- [Release process](release-process.md)
- [Changelog](../../CHANGELOG.md)
- [Unreleased release notes](../releases/unreleased.md)
- [Security model](../../SECURITY.md)
- [Privacy](../reference/privacy.md)
- [Threat model](../reference/threat-model.md)
- [Compatibility](../reference/compatibility.md)
- [CLI reference](../reference/cli.md)
- [MCP tools](../reference/mcp-tools.md)

## Release Claim Rules

- Use **source release** only for GitHub tags and GitHub Releases.
- Use **publish-ready** only when build, docs, and checks are prepared but no
  public artifact exists.
- Use **artifact release** only after Maven Central or Gradle Plugin Portal
  artifacts are public and verified.
- Never use "published", "install from Maven", or "available on Gradle" for
  FixThis artifacts until the relevant registry listing is live.
