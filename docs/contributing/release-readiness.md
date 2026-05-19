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
| Homebrew tap | Available | macOS users can run `brew install beyondwin/tools/fixthis`. |
| External Gradle artifacts | Available | External apps apply `io.github.beyondwin.fixthis.compose` from the Gradle Plugin Portal; the plugin resolves sidekick/core from Maven Central. |
| npm wrapper | Available | Users can run `npm install -g @beyondwin/fixthis`. |
| MCP Registry entry | Available | `io.github.beyondwin/fixthis` points at the public npm wrapper. |

## Supported Install Paths Today

- Clone the repository and run the sample app.
- Use `./scripts/bootstrap-mcp.sh --sample` to register the local MCP server
  with Claude Code or Codex.
- Install the CLI/MCP package with Homebrew on macOS:
  `brew install beyondwin/tools/fixthis`.
- Install the CLI/MCP package with npm:
  `npm install -g @beyondwin/fixthis`.
- Install the CLI/MCP package from GitHub Releases:
  `scripts/install-fixthis.sh --version v0.7.0`.
- Use `fixthis install-agent --project-dir . --target all` in an external
  debug Compose app.
- Use **Copy Prompt** for chat-style agents without MCP.

## Not Published Yet

The following install paths are intentionally not advertised as ready:

- PyPI/Docker package for the CLI/MCP server.

Do not advertise a package-manager path until it has its own clean install
test and rollback plan.

## v0.5 Trustworthy Onboarding Claim

v0.5 may claim README-first Claude Code / Codex bootstrap only when the release
issue includes evidence for this path:

```text
README agent block
-> fixthis install-agent --project-dir . --target all
-> fixthis doctor --project-dir . --json
-> restart Claude Code or Codex when MCP config changed
-> fixthis_open_feedback_console
-> Save to MCP
-> agent-readable sent feedback
```

Required evidence before tagging:

- `npm run docs:agent-bootstrap:test`
- `bash scripts/check-docs-cli-surface.sh`
- `npm run first-run:smoke`
- `npm run first-run:smoke:test`
- `./gradlew :fixthis-cli:test --tests "*AgentSetupFilesTest" --tests "*InstallAgentJsonReportTest" --tests "*DoctorCommandTest" --no-daemon`
- `./gradlew :fixthis-mcp:test --tests "*ConsoleConnectionServiceTest" --tests "*ConsoleConnectionRoutesTest" --no-daemon`

## v0.6 Release Claim Manifest

v0.6 may claim the umbrella roadmap only when the release issue includes
evidence for each claim below.
If evidence is missing, narrow or remove the corresponding v0.6 release note claim.

| Claim | Required evidence |
| --- | --- |
| Handoff Intelligence improves measured edit-surface usefulness. | `npm run handoff:eval:test` and the Track A corpus summary. |
| Studio Reliability preserves sessions, drafts, saves, recovery, stale-preview, blocked-device state, SSE sync, and closed-session mutation fences under repeated use. | `npm run console:reliability:test`, `npm run console:session:test`, `npm run console:draft:test`, and `npm run console:browser:reliability`. |
| Release Grade keeps docs, CLI, MCP, output schema, artifacts, and release notes aligned. | `npm run release:v06:evidence:test`, `node scripts/check-release-readiness.mjs`, `bash scripts/check-docs-cli-surface.sh`, and `npm run checks:observation -- --json`. |

Required v0.6 evidence before tagging:

- `npm run handoff:eval:test`
- `npm run console:reliability:test`
- `npm run console:browser:reliability`
- `npm run release:v06:evidence:test`
- `node scripts/check-release-readiness.mjs`
- `bash scripts/check-docs-cli-surface.sh`
- `npm run checks:observation -- --json`

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
- [ ] `npm run checks:observation -- --json` output captured for the release
      issue, and any non-ready scheduled gate is explicitly accepted.
- [ ] Release/minify consumer fixture passed, or the release issue records why
      Android SDK fixture execution was deferred:

  ```bash
  ./gradlew :fixthis-gradle-plugin:functionalTest --tests "*ReleaseConsumerFixtureTest" --no-daemon
  ```
- [ ] Security, privacy, compatibility, and troubleshooting docs still match
      the release claims.
- [ ] v0.5 Trustworthy Onboarding evidence is captured if the release notes
      claim README-first Claude Code / Codex bootstrap.
- [ ] v0.6 evidence is captured if release notes claim Handoff Intelligence,
      Studio Reliability, or Release Grade:
      `npm run handoff:eval:test`,
      `npm run console:reliability:test`,
      `npm run console:browser:reliability`, and
      `npm run release:v06:evidence:test`.

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
| Homebrew formula | `beyondwin/tools/fixthis` | Homebrew tap |
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
