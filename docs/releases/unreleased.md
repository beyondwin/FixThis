# Unreleased changes

This page summarizes current `main`. It is not a tagged release; use the
GitHub Release page and registry listings as release evidence.

`CHANGELOG.md` remains the chronological source of truth.

## Highlights

- **v0.4 maintainability.** Pre-v0.4 local compatibility paths were removed:
  schema-v1 browser pending mirrors, `.fixthis/artifacts` screenshot fallback,
  keyless semantic draft dedupe, and the deprecated no-item handoff service
  overload. Current draft recovery is schema-v2 `fixthis.workspace.*` only.
- **v0.5 trustworthy onboarding.** The README-first agent path is now
  `fixthis install-agent --project-dir . --target all` followed by
  `fixthis doctor --project-dir . --json`. `install-agent` writes
  `.fixthis/project.json`, `.fixthis/agent-setup.*`, and
  `.fixthis/mcp.json.template`; doctor JSON readiness is the source of truth.
- **v0.6 Handoff Intelligence.** Feedback items can carry optional edit-surface
  role hints, and compact handoffs render those roles so agents can distinguish
  likely call sites, component definitions, copy/data origins, layout/style
  surfaces, visual-area work, and interop risk.
- **v0.6 Studio Reliability.** Console reliability coverage now exercises draft
  recovery, stale-session fences, duplicate save idempotency, session polling,
  and blocked-device state contracts through `npm run console:reliability:test`
  plus the existing draft/session harnesses.
- **v0.6 Release Grade.** Release claims are now evidence-gated. Do not claim
  Handoff Intelligence, Studio Reliability, or Release Grade in a tagged
  release unless the v0.6 evidence commands in
  `docs/contributing/release-readiness.md` have passed and the release issue
  records the results.

## Compatibility Notes

- External Android apps should use Gradle plugin
  `io.github.beyondwin.fixthis.compose` version `0.3.0`.
- The changes above are on current `main`; they are not published as a tagged
  external artifact until the next release is cut.
- The plugin resolves the debug-only sidekick from Maven Central.
- Homebrew installs the matching CLI/MCP GitHub Release package on macOS.
- npm installs the matching CLI/MCP GitHub Release package through
  `@beyondwin/fixthis`.
- Bridge protocol version is `1.3`.
- Persisted JSON changes are additive. `editSurfaceCandidates[].role` is
  optional and older sessions omit it.
- v0.4 does not migrate pre-v0.4 `localStorage["fixthis.pending.<sessionId>"]`
  mirrors or `.fixthis/artifacts` screenshot roots. Clear browser storage or
  run `fixthis clean` if old local artifacts confuse the console.

## Validation Surface

Before tagging the next release, run the current contributor checklist in
[`CONTRIBUTING.md`](../../CONTRIBUTING.md). The top-level local mirror is:

```bash
npm run ci:local
```

That command covers the required Gradle matrix, release-readiness checks,
console bundle freshness, console JS tests, package installer tests, and
whitespace checks. If a release changes CLI commands or agent-facing setup
docs, also run:

```bash
npm run docs:agent-bootstrap:test
bash scripts/check-docs-cli-surface.sh
```

For console layout or agent-state UI changes, also run:

```bash
npm run console:smoke
npm run console:responsive:stress
npm run console:reliability:test
```

For v0.6 release claims, also run and capture:

```bash
npm run handoff:eval:test
npm run release:v06:evidence:test
node scripts/check-release-readiness.mjs
npm run checks:observation -- --json
```
