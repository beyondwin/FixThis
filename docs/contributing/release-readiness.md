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
  `scripts/install-fixthis.sh --version v1.3.0`.
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

## v0.8 Release Claim Manifest

v0.8 may claim the items below only when the release issue includes evidence
for each.

| Claim | Required evidence |
| --- | --- |
| Layout/SubcomposeLayout call sites surface as medium-confidence edit-surface hints for strict `comp:` tags. | `./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" --no-daemon` and `node scripts/source-matching-fixtures-test.mjs`. |
| Reused component definitions are flagged `SHARED_COMPONENT`, capped at medium, and ranked by call-site evidence. | `./gradlew :fixthis-compose-core:test --tests "*SharedComponentCallSiteRankingTest" --no-daemon` and `node scripts/source-matching-fixtures-test.mjs`. |
| Healthy SSE sessions perform no session/preview polling; polling is fallback-only. | `node scripts/console-browser-reliability.mjs`. |
| `fixthis doctor --json` emits top-level `readiness` and `nextAction`. | `./gradlew :fixthis-cli:test --tests "*DoctorCommandTest" --tests "*InstallAgentJsonReportTest" --no-daemon`. |

## v1.0 Release Claim Manifest

v1.0 may claim the items below only when the release issue includes evidence
for each.

| Claim | Required evidence |
| --- | --- |
| Source matching recommends a single shared-component call-site edit surface while the definition stays capped at medium confidence. | `./gradlew :fixthis-compose-core:test --tests "*SharedComponentCallSiteRankingTest" --tests "*SourceMatcherTest" --no-daemon` and `node scripts/source-matching-fixtures-test.mjs`. |
| Custom composables wrapping `Layout`/`SubcomposeLayout` carry layout-renderer (`LAYOUT_RENDERER`) context. | `./gradlew :fixthis-gradle-plugin:test --tests "*KotlinSourceScannerTest" --no-daemon`. |
| Interop-risk selections surface multiple ranked boundary-context nodes (top-3). | `./gradlew :fixthis-mcp:test --tests "*TargetBoundaryContextFormatterTest" --no-daemon`. |
| Healthy SSE sessions perform no session and no preview polling. | `node scripts/console-browser-reliability.mjs`. |
| The ChatGPT agent path is the documented Copy-Prompt/connector flow rather than a first-class file-based writer. | `bash scripts/check-docs-cli-surface.sh`. |
| Lazy-list item lambdas and navigation destinations map a selection to the item/destination composable. | `./gradlew :fixthis-gradle-plugin:test --tests "*KotlinSourceScannerTest" :fixthis-compose-core:test --tests "*SourceMatcherTest" --no-daemon` and `npm run source-matching:fixtures:test`. |
| Each edit-surface role reports a role-specific confidence and an explainable basis. | `./gradlew :fixthis-mcp:test --tests "*EditSurfaceConfidencePolicyTest" --tests "*EditSurfaceCandidateServiceTest" --tests "*CompactHandoffRendererTest" --no-daemon`. |

## Trust Loop Completion Evidence

The Trust Loop Completion umbrella may be claimed only when the release commit
has evidence for runtime source-trust calibration, external agent lifecycle
completion, and release-gate aggregation. This evidence pack does not tag or
publish by itself.

| Claim | Required evidence |
| --- | --- |
| Runtime source-trust fixtures prevent shared-component, interop-risk, visual-area, and weak-candidate evidence from overclaiming exact edit ownership. | `npm run source-matching:fixtures:test`, `npm run source-matching:fixtures:runtime -- --strict`, and `npm run handoff:eval:test`. |
| External Android agent lifecycle completes from handoff through queue read, claim, resolution, persistence, and console reflection. | `npm run agent-loop:smoke:test` and `npm run agent-loop:smoke -- --strict`. |
| Maintainers can classify release evidence and public claims as pass, deferred, or fail in one report. | `npm run release:gate`, `npm run release:gate:test`, and `node scripts/check-release-readiness.mjs`. |

When Android SDK or an unlocked emulator is unavailable, non-strict reports must
record the exact deferred reason and strict connected evidence must fail.

## v1.1 Trust Loop Evidence

The v1.1 trust-loop line may be claimed only when each area below has matching
local evidence from the release commit. This evidence pack does not tag or
publish by itself.

| Claim | Required evidence |
| --- | --- |
| Release/install claims match observable package, tag, and registry state or are explicitly deferred with a reason. | `npm run release:reality`, `npm run evidence:release`, and `node scripts/check-release-readiness.mjs`. |
| External Android agent lifecycle completes from handoff through claim, resolution, persistence, and console reflection. | `npm run agent-loop:smoke:test` and `npm run agent-loop:smoke -- --strict`. |
| Runtime source trust keeps shared-component, interop-risk, and visual-area guidance caveated instead of overclaiming exact ownership. | `npm run source-matching:fixtures:test`, `npm run source-matching:fixtures:runtime -- --strict`, and `npm run handoff:eval:test`. |

The release reality check must use observable public surfaces. For MCP Registry,
it reads only the local `server.json` server name, then verifies the public
registry version response for `io.github.beyondwin/fixthis`.

When Android SDK or an unlocked emulator is unavailable, record the connected
commands as deferred rather than implying they passed.

## External App First Handoff Recovery

The first external-app handoff recovery line may be claimed only when the
release commit has evidence that an external debug Compose app can move from
setup through one agent-readable sent feedback item, or that the report records
the exact recovery action when runtime prerequisites are missing.

| Claim | Required evidence |
| --- | --- |
| External app first handoff reaches one MCP-readable sent item, and failure reports carry `failureCode`, `readiness`, `nextAction`, `verify`, and `fix`. | `npm run agent-loop:smoke:test` and `npm run agent-loop:smoke -- --strict`. |
| Install and doctor JSON do not contradict the first handoff recovery path. | `./gradlew :fixthis-cli:test --tests "*DoctorCommandTest" --tests "*InstallAgentJsonReportTest" --tests "*AgentSetupFilesTest" --no-daemon`. |

Non-strict missing-runtime runs must be recorded as deferred with a recovery-oriented readiness object.
Strict connected smoke must fail when Android SDK, ADB, a ready
emulator/device, or the launched debug app is unavailable.

## Release Gate, Interop Evidence, And SSE Closure

This umbrella may be claimed only when the release gate report includes each
area below. The report is a local release-decision artifact and does not tag or
publish by itself.

| Claim | Required evidence |
| --- | --- |
| AndroidView/WebView-risk handoffs expose host/context boundary evidence without exact source ownership. | `./gradlew :fixthis-mcp:test --tests "*TargetBoundaryContextFormatterTest" --tests "*TargetBoundaryGuidanceTest" --tests "*CompactHandoffRendererTest.compactHandoffRendersInteropBoundaryContextFromNearbyComposeHost" --no-daemon` and `npm run source-matching:fixtures:test`. |
| Healthy SSE sessions avoid redundant session/history/preview polling, with fallback paths explicitly reported. | `node --test scripts/console-reliability-report-test.mjs scripts/studioReliabilityContract-test.mjs` and `npm run console:browser:reliability`. |
| Maintainers can use one release-gate report to classify evidence as pass, deferred, or fail. | `npm run release:gate`, `npm run release:gate:test`, and `node scripts/check-release-readiness.mjs`. |

Connected Android evidence remains local-only. If Android SDK or an unlocked
emulator is unavailable, non-strict reports must record the exact deferred
reason and strict reports must fail.

## v1 Residual Risk Closure Gate

The residual-risk closure may be claimed only when each area below has matching
evidence from the release commit.

| Claim | Required evidence |
| --- | --- |
| CLI Android environment detection is consistent across env vars, project `local.properties`, and default SDK locations. | `./gradlew :fixthis-cli:test --tests "*AdbTest" --no-daemon`. |
| Source matching recommends specific shared-component call sites without raising shared definitions above medium confidence. | `./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" --no-daemon` and `npm run source-matching:fixtures:test`. |
| Interop handoffs render boundary context without exact XML/View or WebView ownership claims. | `./gradlew :fixthis-mcp:test --tests "*TargetBoundaryContextFormatterTest" --tests "*CompactHandoffRendererTest" --no-daemon`. |
| Healthy SSE sessions do not use fallback session or preview polling. | `node --test scripts/console-reliability-report-test.mjs` and `npm run console:browser:reliability`. |
| Release and required-check readiness evidence is refreshable and documented. | `node scripts/check-release-readiness.mjs` and `npm run checks:observation -- --json`. |

## Trust Sync Release Hardening Evidence

The trust-sync hardening line may be claimed only when each claim below has
matching local evidence from the release commit.

| Claim | Required evidence |
| --- | --- |
| Interop and visual-area handoffs avoid exact-source overclaiming. | `npm run handoff:eval:test` plus `./gradlew :fixthis-mcp:test --tests "*TargetBoundaryGuidanceTest" --tests "*FeedbackQueueFormatterPhase2Test" --tests "*CompactHandoffRendererTest" --no-daemon`. |
| SSE is the happy-path console sync channel for item and handoff mutations. | `node --test scripts/studioReliabilityContract-test.mjs` and `npm run console:browser:reliability`. |
| Event-stream diagnostics expose local event count, reconnect/subscriber, replay, and overflow state. | `./gradlew :fixthis-mcp:test --tests "*ConsoleEventBusTest" --tests "*ConsoleEventsRoutesTest" --no-daemon`. |
| Release and agent-install docs match the supported public surfaces. | `node scripts/check-release-readiness.mjs`, `bash scripts/check-docs-cli-surface.sh`, `npm run docs:agent-bootstrap:test`, and `npm run release:version:check`. |

Connected runtime trust evidence remains local-only. If Android SDK or an
unlocked emulator is unavailable, record the runtime command as deferred rather
than implying it passed:

```bash
npm run source-matching:fixtures:runtime -- --strict
```

## V1 Trust, Install, And Inner-Loop Evidence

The V1 umbrella hardening line may be claimed only when each area below has
matching local evidence from the release commit. This evidence pack does not add PyPI, Docker, or any new package channel.

| Claim | Required evidence |
| --- | --- |
| Source trust avoids overconfident layout, copy/data, visual-area, and interop guidance. | `./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" --no-daemon`, `npm run handoff:eval:test`, and `npm run source-matching:fixtures:test`. |
| Agent-first setup reports a recoverable next action from install through doctor. | `./gradlew :fixthis-cli:test --tests "*InstallAgentJsonReportTest" --tests "*DoctorCommandTest" --tests "*AgentSetupFilesTest" --no-daemon` and `npm run docs:agent-bootstrap:test`. |
| Local evidence profiles are available without hiding canonical commands. | `npm run evidence:fast -- --dry-run`, `npm run evidence:test`, and `node scripts/check-release-readiness.mjs`. |

Runtime trust remains local-only. If Android SDK or an unlocked emulator is
unavailable, record `npm run source-matching:fixtures:runtime -- --strict` as
deferred rather than implying it passed.

The evidence runner writes local reports under
`build/reports/fixthis-evidence/`; those reports are useful release-issue
attachments but are not committed.

## External Trust Matrix And Release Drift Evidence

This umbrella may be claimed only when each area below has matching local
evidence from the release commit. The evidence pack does not tag or publish by
itself.

| Claim | Required evidence |
| --- | --- |
| External Android project shapes complete setup validation and, when Android runtime prerequisites are available, strict lifecycle validation. | `npm run external-fixture:matrix:test` and `npm run external-fixture:matrix -- --strict`. |
| Handoff correctness evaluation covers owner, role, confidence, caution, ranking, and prompt usability without exact-ownership overclaiming. | `npm run handoff:eval:test`. |
| Release metadata does not drift from tag distance, changelog, unreleased notes, readiness claims, and package command evidence. | `npm run release:drift`, `npm run release:drift:test`, and `npm run release:gate`. |

The strict external fixture matrix builds the local `fixthis` CLI distribution
before fixture execution, runs agent config writes with a fixture-local JVM
`user.home`, and treats `doctor --json` reaching `NEEDS_APP_LAUNCH` as the
expected setup boundary for generated external projects that have not been
installed and launched.

When Android SDK or an unlocked emulator is unavailable, non-strict reports must
record the exact deferred reason and strict connected evidence must fail.

## External App Trust Matrix v2 Evidence

The External App Trust Matrix v2 line may be claimed only when the release
commit has evidence that external project setup, first-handoff runtime proof,
and handoff trust caveats are reported in one matrix.

| Claim | Required evidence |
| --- | --- |
| External project setup and runtime-capable handoff trust are classified as pass, deferred, fail, fixture drift, or caveated pass without overclaiming exact source ownership. | `npm run external-fixture:matrix:test` and `npm run external-fixture:matrix -- --strict`. |
| Release gate consumes the matrix v2 report as the `external-trust-matrix-v2` claim. | `npm run release:gate:test` and `npm run release:gate`. |

When Android SDK, ADB, an unlocked emulator/device, or the launched debug app is
unavailable, non-strict reports must record the exact deferred reason and
strict connected evidence must fail. A caveated pass is acceptable only when
the handoff preserves the required warning or risk signal, such as
`VISUAL_AREA_ONLY`, `POSSIBLE_VIEW_INTEROP`, or `SHARED_COMPONENT`.

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
