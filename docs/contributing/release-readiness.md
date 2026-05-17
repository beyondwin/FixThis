# Release Readiness

FixThis is usable today through GitHub Releases, Homebrew, npm, MCP Registry,
Maven Central, the Gradle Plugin Portal, the bundled sample app, and local MCP
bootstrap.

This page is the release dashboard. It separates what users can do today from
what must be verified before adding broader package-manager or registry
discovery.

## Current Status

| Surface | Status | User impact |
| --- | --- | --- |
| GitHub Release | Available | Users can install the CLI/MCP package or inspect tagged source. |
| Current `main` | Development | Users can try latest docs/code from source. |
| Sample app | Available | Users can verify the workflow before touching their app. |
| Claude Code / Codex MCP bootstrap | Available from source | `./scripts/bootstrap-mcp.sh --sample` configures local MCP for the sample. |
| CLI/MCP GitHub Release package | Available | `scripts/install-fixthis.sh` installs it. |
| Homebrew tap | Available | macOS users can run `brew install beyondwin/fixthis/fixthis`. |
| External Gradle artifacts | Available | External apps apply `io.github.beyondwin.fixthis.compose` from the Gradle Plugin Portal; the plugin resolves sidekick/core from Maven Central. |
| npm wrapper | Available | Users can run `npm install -g @beyondwin/fixthis`. |
| MCP Registry entry | Available | `io.github.beyondwin/fixthis` points at the public npm wrapper. |

## Supported Install Paths Today

- Clone the repository and run the sample app.
- Use `./scripts/bootstrap-mcp.sh --sample` to register the local MCP server
  with Claude Code or Codex.
- Install the CLI/MCP package with Homebrew on macOS:
  `brew install beyondwin/fixthis/fixthis`.
- Install the CLI/MCP package with npm:
  `npm install -g @beyondwin/fixthis`.
- Install the CLI/MCP package from GitHub Releases:
  `scripts/install-fixthis.sh --version v0.2.3`.
- Use `fixthis install-agent --project-dir . --target all` in an external
  debug Compose app.
- Use **Copy Prompt** for chat-style agents without MCP.

## Not Published Yet

The following install paths are intentionally not advertised as ready:

- PyPI/Docker package for the CLI/MCP server.

Do not advertise a package-manager path until it has its own clean install
test and rollback plan.

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
- [ ] Release tarball checksum sidecar exists and both shell/npm installers
      verify SHA-256 before extraction.
- [ ] Security, privacy, compatibility, and troubleshooting docs still match
      the release claims.

## Required Before External Artifact Release

- [x] Public group and artifact coordinates are final:
      `io.github.beyondwin:fixthis-compose-sidekick` and
      `io.github.beyondwin:fixthis-compose-core` if core is published.
- [x] Gradle plugin id remains `io.github.beyondwin.fixthis.compose`.
- [x] One local version source of truth is established for root published
      modules: `FIXTHIS_GROUP` and `FIXTHIS_VERSION` in `gradle.properties`.
- [x] Gradle publishing plugins are configured for local dry-run validation.
- [x] Local dry-run packaging is verified:
      `./gradlew publishToMavenLocal --dry-run --no-daemon` for root artifacts
      and `./gradlew -p fixthis-gradle-plugin publishToMavenLocal --dry-run
      --no-daemon` for the included Gradle plugin build.
- [x] Maven Central manual workflow exists for `fixthis-compose-core` and
      `fixthis-compose-sidekick`.
- [x] Gradle Plugin Portal publish workflow exists and runs `publishPlugins`.
- [x] A clean external sample project resolves the published artifacts from
      public registries.
- [x] README install instructions use the published artifact path.

## Public Coordinates

| Surface | Coordinate / id | Registry |
| --- | --- | --- |
| Gradle plugin | `io.github.beyondwin.fixthis.compose` | Gradle Plugin Portal |
| Compose sidekick | `io.github.beyondwin:fixthis-compose-sidekick` | Maven Central |
| Compose core | `io.github.beyondwin:fixthis-compose-core` | Maven Central, only if needed by consumers |
| CLI/MCP package | `fixthis-cli-mcp-vX.Y.Z.tar.gz` | GitHub Releases |
| Homebrew formula | `beyondwin/fixthis/fixthis` | Homebrew tap |
| npm wrapper | `@beyondwin/fixthis` | npm |
| MCP server | `io.github.beyondwin/fixthis` | MCP Registry metadata |

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
| `NPM_TOKEN` | npm automation token for publishing `@beyondwin/fixthis` before MCP Registry publication. |

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

- Use **GitHub release** for tags, release notes, and CLI/MCP tarballs.
- Use **artifact release** for Maven Central and Gradle Plugin Portal
  publications.
- Use **registry release** only for MCP Registry, Homebrew, npm, PyPI, Docker,
  or similar discovery channels after their install tests exist.
- Do not advertise a newly added registry until a clean consumer install has
  verified it.
