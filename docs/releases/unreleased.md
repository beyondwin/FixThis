# Unreleased changes since v0.1.0

This page summarizes current `main`. It is not a tagged release and should not
be used as evidence that Maven Central or Gradle Plugin Portal artifacts are
published.

These notes summarize the user-visible and contributor-facing changes that
landed after the [v0.1.0](v0.1.0.md) GitHub source release. `CHANGELOG.md`
remains the chronological source of truth.

## Highlights

- Feedback sessions are more durable. FixThis now writes session mutations to
  an append-only event log before updating in-memory state, replays events on
  restart, compacts through checkpoints, and recovers browser-local pending
  annotations after reloads or ADB disconnects.
- Frozen previews now carry screen-integrity metadata. Bridge protocol `1.3`
  adds orientation, dimensions, density, window mode, system UI state, and a
  nullable fingerprint. If the app rotates, changes window mode, opens system
  UI, or navigates away before save, the console asks whether to re-capture,
  force-save, or cancel.
- The console now treats agent states as first-class UI. Claimed items show an
  in-progress state and agent note; `needs_clarification`, `wont_fix`, and
  `resolved` render as distinct terminal states with the agent summary.
- Saved annotations are now session-scoped end to end. Preview artifact URLs,
  saved overlay edits, pending recovery, and undo/redo history carry the
  session context that created them, and persisted item numbers remain stable
  across deletes and session reopens.
- Target reliability is now part of saved handoffs. FixThis derives
  high/medium/low target confidence from semantic coverage, source-candidate
  quality, stale-index state, fingerprint checks, and redaction, then renders
  the result in the console and in compact agent Markdown.
- Browser-only pending annotations now run through a DraftWorkspace state
  machine. Drafts carry an immutable freeze context, revision, lifecycle, and
  undo/redo history; stale async responses and session switches can no longer
  move draft work into the wrong session.
- The browser console has been hardened for narrow screens and long diagnostics.
  Global status messages, connection details, activity-drift warnings, stale
  binary banners, and agent summaries wrap without forcing horizontal scroll.
- The MCP and session internals were split around cleaner boundaries: pure
  domain ports in `:fixthis-compose-core`, MCP adapters at the edge, a session
  reducer/replay path, a smaller tool registry/dispatcher, route-specific
  console tests, and architecture hotspot guardrails.
- Contributor checks now cover console bundle freshness and pure JavaScript
  harnesses. The project license is MIT.
- The feedback console now receives session, device, connection, and preview
  updates over an SSE `/api/events` channel, with existing polling retained as
  the fallback when the stream drops.
- The console harness now executes the `network-outage` and `slow-handoff`
  scenarios instead of reporting them as skipped placeholders.
- Contributor loops are faster. The local Gradle build cache is enabled by
  default, source-index generation is cacheable, sidekick build metadata avoids
  unnecessary Kotlin recompilation, and CI separates console JavaScript checks
  from Gradle verification for faster failures.

## Compatibility Notes

- External Gradle artifacts are still not published. Consumers should continue
  to use a Gradle composite build until release readiness marks Maven Central
  and Gradle Plugin Portal publication complete.
- Persisted MCP JSON field names remain compatibility contracts:
  `items`, `screens`, `itemId`, `screenId`, `targetEvidence`, and
  `sourceCandidates`.
- Session JSON now also carries additive `nextItemSequenceNumber` state for
  stable saved annotation numbering. Older sessions are migrated from existing
  item `sequenceNumber` values when they are reopened.
- Bridge protocol `1.3` is additive at the persisted JSON layer. Older saved
  sessions remain readable; fingerprint comparison is skipped when either side
  lacks a fingerprint.
- FixThis is still debug-build-only and Jetpack Compose-only.

## Validation Surface

Before tagging the next release, run the current contributor checklist:

```bash
./gradlew \
  spotlessCheck \
  detekt \
  :fixthis-compose-core:test \
  :fixthis-cli:test \
  :fixthis-mcp:test \
  :fixthis-compose-sidekick:testDebugUnitTest \
  :fixthis-gradle-plugin:test \
  :app:assembleDebug \
  :fixthis-cli:installDist \
  :fixthis-mcp:installDist \
  --no-daemon
node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
node scripts/check-doc-consistency.mjs
node scripts/run-console-tests.mjs availability pending beforeunload undo activity preview draft session harness
git diff --check
```

For console layout or agent-state UI changes, also run:

```bash
npm run console:smoke
npm run console:responsive:stress
```
