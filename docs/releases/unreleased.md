# Unreleased changes

This page summarizes current `main`. It is not a tagged release; use the
GitHub Release page and registry listings as release evidence.

`CHANGELOG.md` remains the chronological source of truth.

## Highlights

- Compose `Layout(...)` / `SubcomposeLayout(...)` call sites are now indexed
  as a typed `LAYOUT_RENDERER` signal and surfaced as medium-confidence
  edit-surface hints for strict `comp:` test-tag selections, with conservative
  scanning rules that avoid false signals from comments, strings, declarations,
  and same-name non-Compose locals.
- Compact handoff preserves the source-matching confidence token on the
  rank-1 candidate and renders trust caveats as a `note:` line, making
  source-matching trust legible from the compact prompt.
- The local source-matching fixture lab classifies trust regressions, fails
  on unmatched confidence expectations, and supports a runtime trust
  evaluation mode that exercises `RuntimeTargetResolver` against fixture
  indexes (plus a strict local gate invocation for CI-like enforcement).
  Runtime cases now require a `trustPurpose` description (rendered as a
  `Purpose` column), and the manifest accepts two fixture sources:
  `external-github` (clone+patch) and `local-project` (in-tree modules
  addressed by an optional `moduleDir`). Focused runtime set: Reply, the
  in-repo `fixthis-sample` local-project case, and a Jetsnack filters
  case. See [`docs/guides/source-matching-fixture-lab.md`](../guides/source-matching-fixture-lab.md).
- The fixture-lab source-index evaluator now matches manifest paths
  against both `entry.file` and `entry.repoFile`, so monorepo-prefixed
  fixtures (Reply, Jetsnack, NIA) match the way operators write them.
  The manifest is re-pinned to current upstream (`ReplyApp`,
  `JetsnackBottomBar`, NIA `for-you` API title resource, NIA
  `Navigation.kt`), and runtime `capture_failed` downgrade rows now
  render their purpose instead of `-`.
- Console preview is now SSE-first: a live SSE connection alone refreshes
  the screen, and visibility-resume / boot polling only fires as a
  fallback when SSE is disconnected and an interval is configured. A new
  `console:browser:reliability` proof asserts the no-fallback path. See
  the push-first preview contract in
  [`docs/reference/feedback-console-contract.md`](../reference/feedback-console-contract.md).
- Console maintainer inner loop: `node scripts/build-console-assets.mjs --watch`
  auto-rebundles on edit and the `--console-assets-dir` server pushes a
  `console-assets-changed` SSE event so the browser reloads. The top bar
  shows a server build chip (`connected · build sha=<short>` /
  `reconnecting…`) so `bash scripts/restart-console.sh` is verifiable from
  the UI.
- Bug fixes preserve ambiguous-owner candidate confidence, original
  fixture candidate order, and the `UNTYPED_FALLBACK` risk on weak
  owner-function matches; owner-function source evidence is now explicitly
  capped at medium confidence. Detekt findings cleared across
  `SourceScoringPolicy`, `SourceMatcher`, `CompactHandoffRenderer`,
  `ConsoleAssetsWatcher`, `FeedbackConsoleAssets`, and
  `RuntimeTrustObservationMapper` without behavior changes.
- Trust program Phase 2 — PRECISE handoff now pairs each source candidate
  with its corresponding `edit:` sub-line and surfaces an `edit-note:`
  bullet for warnings, renders a rank-1 source caution as a `- note:`
  line, exposes an `edit-surface hints` block when source candidates are
  absent, and emits explicit `- Action:` lines for `VISUAL_AREA_ONLY`,
  `POSSIBLE_VIEW_INTEROP`, `NO_MEANINGFUL_COMPOSE_TARGET`, and
  `SENSITIVE_TEXT_REDACTED` reliability warnings. Compact and PRECISE
  renderers stay token-equivalent for trust-essential fields, the existing
  source-only contract remains byte-stable for older sessions, and the
  empty-source preview retains its "do not invent source" invariant while
  permitting Compose-derived edit-surface hints.
- Trust-sync hardening now makes interop and visual-area boundaries explicit in
  agent handoffs, reduces item/handoff mutation pull refreshes while SSE is
  healthy, and exposes local event-stream diagnostics for release evidence.
- Planned V1 hardening now ties source-trust calibration, agent install
  recovery, and local evidence profiles to explicit release evidence.
- Agent setup and diagnostics now share a top-level readiness contract:
  `fixthis doctor --json` and `install-agent --json` both expose
  `readiness` plus `nextAction`, so agents can continue from one machine-readable
  next step instead of inferring from individual checks.
- Local evidence profiles (`npm run evidence:fast`, `evidence:trust`,
  `evidence:console`, and `evidence:release`) write JSON and Markdown reports
  under `build/reports/fixthis-evidence/`. The runner auto-detects common
  Android SDK locations and defers runtime trust checks when no ready device is
  available unless strict runtime mode is requested.
- Clean architecture hardening moves target-evidence assembly into the pure
  core module and splits MCP session target validation, preview fingerprint
  policy, and save-reservation tracking into focused collaborators. Architecture
  tests now enforce the core dependency boundary and ratcheted hotspot budgets.
- Agent setup can now write a Cursor MCP config: `--target cursor` writes a
  project-local `.cursor/mcp.json` and `--target all` includes Cursor alongside
  Codex and Claude. The writer follows the same idempotent JSON-merge semantics
  as the Claude writer — it preserves unrelated MCP servers and replaces only
  the fixthis entry. See [`docs/reference/cli.md`](../reference/cli.md).

## Compatibility Notes

- External Android apps should use Gradle plugin
  `io.github.beyondwin.fixthis.compose` version `0.7.0`.
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
- Current source-index assets use schema version `1.2` and preserve the legacy
  source-index field lists while adding typed `signals`.

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
```

`npm run ci:local` covers the required Gradle matrix, release-readiness checks,
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
