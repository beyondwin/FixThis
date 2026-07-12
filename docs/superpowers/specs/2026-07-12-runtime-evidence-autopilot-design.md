# Runtime Evidence Autopilot Design

Date: 2026-07-12

## Summary

FixThis should complete its existing runtime-evidence contract by collecting a
small, time-correlated Android diagnostic bundle when a user hands annotated
feedback to an MCP agent. The first implementation adds host-side ADB
collectors for bounded logcat, memory, frame, and app/bridge context; connects
their summaries and local artifact paths to the affected feedback items; and
keeps normal handoff successful when evidence is unavailable.

The feature is intentionally narrower than a general mobile debugger. FixThis
keeps its Compose semantics, frozen-screen annotation, source matching, and
agent handoff as the product center. It adopts Orca's useful engineering
patterns—connection diagnostics, bounded recovery, stream lifecycle honesty,
protocol-skew awareness, and no-phone repro harnesses—without adopting Orca's
mobile companion product or remote agent-control surface.

## Research Basis

This design reviewed `stablyai/orca` at commit
`26934b11bfb5bb02cd9f504a33498a67aa14fdeb` on 2026-07-12.

Orca's mobile app is a companion for monitoring and steering desktop coding
agents, not a target-app debugger. Its public mobile documentation describes a
read-mostly phone surface for worktree state, terminal scrollback, replies,
source control, account switching, and notifications, with the desktop as the
source of truth:

- <https://www.onorca.dev/docs/mobile>
- <https://github.com/stablyai/orca/tree/main/mobile>

The transferable parts are implementation patterns rather than companion UI:

- foreground and network-change revival for parked or half-open connections;
- explicit connection-state and diagnostic logs;
- replay of active subscriptions after reconnect;
- per-device authentication, E2EE, and an RPC allowlist;
- versioned mobile/desktop compatibility checks;
- transport-versus-UI repro harnesses that run without a physical phone;
- bounded streaming and backpressure behavior.

The most relevant upstream investigation is Orca's Android session recovery
write-up, which separates parked reconnect loops, half-open sockets, and stale
client references instead of treating every unresponsive screen as one bug:

- <https://github.com/stablyai/orca/blob/main/mobile/issue-5049-unresponsive-session-findings.md>
- <https://github.com/stablyai/orca/blob/main/mobile/terminal-output-streaming-findings.md>

FixThis should borrow that diagnostic discipline. It should not copy Orca's
mobile terminal, worktree, Git, notification, or LAN pairing surfaces.

## Current State And Gap

FixThis already has additive persisted models for:

- `RuntimeEvidenceAttachment`;
- `RuntimeEvidenceType`: `logcat_window`, `frame_summary`,
  `memory_summary`, and `trace_artifact`;
- item-level `runtimeEvidenceIds` references;
- bounded compact-handoff rendering;
- the `fixthis_capture_runtime_evidence` MCP tool;
- local artifact paths under `.fixthis/`;
- `runtime-evidence:smoke` and strict connected evidence reporting.

The product path is not yet an evidence collector.
`RuntimeEvidenceService.attachManualSummary()` accepts a caller-provided summary
and optional artifact path, then links them to one item. It does not run ADB,
capture a time window, redact collector output, validate that the device stayed
stable, or coordinate multiple evidence types.

The current strict smoke has a related proof gap. When Android is ready,
`scripts/runtime-evidence-smoke.mjs` runs `adb logcat -d -t 80` directly and
writes a generic local row. That proves that ADB can produce a bounded artifact,
but it bypasses the CLI bridge, feedback session, MCP operation, event journal,
redaction pipeline, multi-item linking, compact handoff, and boot replay. A
strict pass therefore does not prove that a real feedback item received safe,
recoverable runtime evidence.

The missing product capability is:

> Capture bounded evidence from the selected Android debug app, correlate it
> with the frozen feedback screen, persist only safe metadata and local paths,
> and tell the agent exactly how trustworthy that evidence is.

## Goals

- Automatically collect a low-cost baseline evidence bundle once per saved MCP
  handoff batch.
- Keep collection local, debug-only, ADB-scoped, bounded, and redacted.
- Correlate evidence with session, screen, item batch, package, selected device,
  process, build/install epoch, and capture time.
- Share one capture bundle across all items from the same saved frozen screen.
- Make collection failure visible without blocking annotation persistence or
  normal agent handoff.
- Persist attachment metadata and item links atomically through the existing
  session event/replay model.
- Provide a manual browser action and MCP tool for targeted recapture.
- Turn strict connected evidence into an end-to-end product-path proof.
- Preserve current persisted JSON field names and keep older session JSON
  readable.

## Non-Goals

- No mobile companion, remote agent control, cloud relay, or remote console.
- No production-build capture.
- No iOS, Flutter, React Native, XML/View exact targeting, or WebView DOM
  inspection.
- No automatic Perfetto or simpleperf trace.
- No Copy Prompt automatic capture.
- No recorded/replayable navigation scenario in this work.
- No source-confidence increase merely because runtime evidence exists.
- No external AI service or LLM summarization.
- No dependency from `:fixthis-compose-core` on CLI, MCP, Android UI, or
  `.fixthis/` paths.
- No bridge protocol change in the first implementation.

## Product Policy

Runtime evidence has three session-scoped modes:

| Mode | Behavior |
| --- | --- |
| `auto_on_handoff` | Default. `Save to MCP` collects one baseline bundle for the written annotation batch. |
| `manual` | Collection runs only from `Capture diagnostics` or the collection MCP tool. |
| `off` | Automatic and browser-triggered collection are disabled. Existing manual summary attachment remains available. |

Persist the selection as an additive `runtimeEvidencePolicy` session field.
New sessions explicitly start as `auto_on_handoff`. A session JSON written
before this field existed resolves a missing value as `manual`, so upgrading
FixThis does not silently enable log collection inside an already-open or
resumed feedback workspace.

`Copy Prompt` does not collect automatically. A user can capture diagnostics
first and then copy the prompt, but the fast chat-style path does not wait on
ADB by default.

The automatic baseline contains only low-cost collectors:

- app, bridge, device, screen, and build/install context;
- a bounded app-oriented logcat window;
- a bounded `dumpsys meminfo` summary;
- bounded `dumpsys gfxinfo` output labeled as cumulative when it is not a
  windowed measurement.

Heavy trace capture is explicit-only because it is slower, larger, more
platform-dependent, and more likely to affect the observed performance.

## Architecture

```text
Console / MCP trigger
  -> RuntimeEvidenceCaptureCoordinator        (:fixthis-mcp)
  -> RuntimeEvidenceBridge                    (MCP port)
  -> CLI ADB collectors                       (:fixthis-cli)
  -> redaction + deterministic summarization  (:fixthis-mcp)
  -> local artifact bundle                    (.fixthis/, ignored)
  -> RuntimeEvidenceCaptured event            (session journal)
  -> bounded compact handoff                   (:fixthis-mcp)
```

### MCP Ownership

Add `RuntimeEvidenceCaptureCoordinator` under the existing MCP session/runtime
package. It owns product policy and session correlation:

- resolve session and target items;
- validate the frozen screen and package;
- snapshot the selected device identity;
- choose collectors from the requested preset and capabilities;
- apply per-collector and overall deadlines;
- deduplicate a batch capture;
- redact and summarize results;
- commit a completed artifact bundle;
- append one domain event linking attachments to all target items.

Keep `RuntimeEvidenceService` focused on persistence-facing model operations and
the existing backward-compatible manual attachment path. Do not grow
`FeedbackSessionService` into the collector implementation; it remains the
facade and delegates to the coordinator.

### CLI Ownership

ADB executable discovery, device selection, serial scoping, and command
execution already belong to `:fixthis-cli`. Real collection stays there.

Add a narrow `RuntimeEvidenceBridge` port in MCP and implement it through
`CliFixThisBridge` plus neutral CLI result DTOs. CLI result DTOs must not depend
on MCP session models. MCP maps them to persisted attachment metadata.

MCP must not invoke `ProcessBuilder` for ADB commands. The CLI collector must
use structured argument lists, never `sh -c` or caller-supplied command text.

### Sidekick And Core Boundaries

The first implementation reuses existing bridge operations such as `status`
and `captureScreenSnapshot`; logcat, memory, and frame information come from
host-side ADB. Therefore:

- `BridgeProtocol.VERSION` stays `1.3`;
- no new sidekick method is required;
- older installed debug apps can use baseline collection when their existing
  status/snapshot surface is compatible;
- `:fixthis-compose-core` does not change.

In-process-only evidence such as recomposition counters requires a later,
capability-gated bridge design and is outside this spec.

## Components

### `RuntimeEvidenceCaptureCoordinator`

Inputs:

- `sessionId`;
- one or more `itemIds`;
- `screenId`;
- preset: `baseline`, `logs`, `memory`, or `performance`;
- trigger: `handoff_auto`, `console_manual`, or `mcp_manual`.

Outputs:

- capture id and aggregate status;
- attachment summaries;
- warnings and failure reasons;
- linked item ids;
- artifact bundle relative path when committed.

The coordinator limits collector concurrency to two and the automatic baseline
wall-clock budget to 2.5 seconds. Completed results survive a timeout; unfinished
collectors become `capture_timeout` and the aggregate becomes `partial`.

### CLI Collectors

Collectors have one purpose each and return a typed result rather than throwing
raw process failures across the module boundary:

- `LogcatWindowCollector`;
- `MemorySummaryCollector`;
- `FrameSummaryCollector`;
- `RuntimeContextCollector`.

Each reports `complete`, `partial`, `failed`, or `unsupported`, plus bounded
stdout/stderr, timing, command identity without secrets, and a stable reason.

### Artifact Writer And Manifest

Artifacts live under:

```text
.fixthis/runtime-evidence/
└── <sessionId>/
    └── <captureId>/
        ├── manifest.json
        ├── logcat.txt
        ├── frame-summary.txt
        └── memory-summary.txt
```

Write to a temporary sibling directory, fsync or close all outputs, write the
manifest last, then rename to the final capture id. Only a committed bundle can
be linked from session state.

The session stores summaries, warnings, timestamps, and relative artifact
paths, never raw log, memory, frame, or trace payloads.

### Session Event

Add a `RuntimeEvidenceCaptured` event containing:

- capture metadata and attachments;
- the set of target item ids;
- the expected session/screen context;
- aggregate capture status.

Applying the event appends attachments and updates all referenced items in one
state transition. If the process stops after the event is written but before a
session snapshot is saved, boot replay restores both the attachments and item
links. Temporary or unreferenced artifact directories are cleaned separately;
replay never invents a successful artifact.

Before appending the event, revalidate that the session is open and each target
item still belongs to the expected screen. A late collector result for a closed
session, deleted item, or replaced screen is discarded from session state; its
unreferenced bundle is cleaned as an orphan.

### Browser And MCP Surfaces

The annotation detail shows:

- `Diagnostics: collecting`, `complete`, `partial`, or `failed`;
- capture time and proximity to the frozen screen;
- collected evidence types and warnings;
- `Capture again`.

The session UI exposes `Auto`, `Manual`, and `Off`. The preference is
session-scoped, persisted only in the local session JSON, and never added to
the Android app or project build configuration.

Keep `fixthis_capture_runtime_evidence` for the existing manual-summary
contract. Add `fixthis_collect_runtime_evidence` for actual device collection
so one tool does not ambiguously mean both “attach caller data” and “run ADB”.

## Data Flow

### Automatic Save To MCP

1. The browser sends written annotations and the frozen-preview timestamp.
2. The idempotent draft persistence path creates or reuses the screen snapshot
   and items while keeping their delivery state out of the agent-visible sent
   queue.
3. When the session policy is `auto_on_handoff`, the coordinator deduplicates on
   `(sessionId, screenId, policy, installEpoch)`.
4. It captures start context: device serial, package, PID, install epoch,
   activity, bridge protocol, and current screen fingerprint.
5. It runs baseline collectors with concurrency and deadline limits.
6. It captures end context and classifies context drift.
7. It streams collector output through redaction and deterministic
   summarization into a temporary bundle.
8. It commits the bundle and appends `RuntimeEvidenceCaptured` when usable
   results exist.
9. The normal handoff transition marks the target items sent whether evidence
   completed, partially completed, failed, or was disabled.
10. The Save response and compact handoff include the aggregate evidence state.

Annotation persistence is authoritative. Evidence failure does not roll it
back. The UI and MCP response say that evidence is partial or failed. Because
items do not enter the sent queue until the bounded collection attempt ends, an
agent cannot race Save to MCP and read an item that is still waiting for its
automatic evidence decision. If the process stops during collection, the
idempotent browser workspace ids preserve the draft items for a safe Save retry.

### Manual Collection

`Capture diagnostics` and `fixthis_collect_runtime_evidence` call the same
coordinator with an explicit preset. A repeat capture gets a new capture id and
does not overwrite prior evidence. The handoff renderer caps output and shows
the newest relevant attachments first.

### Compact Handoff

The handoff remains summary-only:

```text
runtimeEvidence:
  - logcat_window status=complete proximity=near
    summary: IllegalStateException observed after retry; causal link unproven
    artifact: .fixthis/runtime-evidence/.../logcat.txt
  - memory_summary status=partial
    warning: process_restarted
```

The renderer never embeds raw lines, stack traces, memory dumps, or trace
payloads.

## Time Correlation And Context Integrity

Record three distinct timestamps:

- `screenCapturedAt`: frozen preview capture;
- `captureStartedAt`: diagnostic collection start;
- `captureCompletedAt`: collection end.

Classify `captureStartedAt - screenCapturedAt` as:

- `near`: 0–3 seconds;
- `delayed`: over 3 seconds through 15 seconds;
- `stale`: over 15 seconds, with `stale_window` warning.

At capture start and end, verify:

- selected device serial;
- package availability;
- package install epoch;
- PID;
- active session and screen context.

A device-serial or install-epoch change invalidates the entire capture with
`context_changed`. A PID change is retained as `partial` with
`process_restarted`, because crash-buffer and ActivityManager evidence may
still be useful. A changed screen fingerprint lowers trust and is reported; it
does not silently attach as near-screen evidence.

The logcat window begins shortly before the frozen preview when the platform
supports timestamp selection. It combines bounded current-process output with
bounded crash-buffer and package-related system events so a process restart
does not erase every clue. Unsupported timestamp or PID filtering is explicit
in warnings.

## Error Model

Stable attachment states:

- `complete`;
- `partial`;
- `failed`;
- `unsupported`.

Stable reasons and warnings:

- `device_unavailable`;
- `device_changed`;
- `package_unavailable`;
- `process_not_running`;
- `collector_unsupported`;
- `permission_denied`;
- `capture_timeout`;
- `output_truncated`;
- `redaction_applied`;
- `process_restarted`;
- `context_changed`;
- `artifact_write_failed`;
- `quota_exceeded`;
- `artifact_missing`;
- `stale_window`;
- `cumulative_not_windowed`.

One bounded retry is allowed for transient transport/process failures. Do not
retry permission denial, unsupported commands, invalid context, or quota
failure.

Save to MCP succeeds when annotation persistence succeeds. The evidence state
is an independent result and must never be translated into a false handoff
failure.

## Privacy And Command Safety

### Command Safety

- Validate package names as Android application ids.
- Build ADB commands as argument lists.
- Do not accept arbitrary shell fragments or collector commands from MCP.
- Apply process timeout, child cleanup, and stdout/stderr byte limits.
- Redact command metadata; do not store tokens or unnecessary absolute user
  paths.
- Expose only allowlisted presets and collectors.

### Redaction

Collector output passes through streaming redaction before it is written to an
artifact. The default rules cover:

- authorization and bearer headers;
- cookies and session tokens;
- JWT-shaped strings;
- common API key, secret, and password key/value patterns;
- sensitive URL query keys;
- FixThis bridge and console tokens;
- project-configured additional patterns.

Persist only redacted artifacts by default. Apply a second redaction pass to
summaries before session persistence and handoff rendering. Record
`redaction_applied` without recording the secret class or value.

Do not blindly redact every email address or human-readable string. Arbitrary
application PII requires project-specific rules; the UI and docs must keep the
same warning already applied to sensitive screenshots and local artifacts.

## Size, Quota, And Retention

Default limits:

| Artifact | Limit |
| --- | ---: |
| Logcat | 2,000 lines or 512 KiB |
| Memory summary | 128 KiB |
| Frame summary | 128 KiB |
| Baseline bundle | 2 MiB |
| Explicit trace | 25 MiB |
| Project runtime evidence | 250 MiB |

Truncation records `output_truncated`; it is never silent.

- Deleting a session deletes its evidence directory.
- Startup removes incomplete temporary bundles.
- Automatic cleanup does not delete bundles referenced by open sessions.
- When the project quota is full, a new capture fails with `quota_exceeded`
  rather than deleting referenced evidence.
- Old closed-session cleanup is explicit and does not run as a hidden side
  effect of Save to MCP.

## Capability And Compatibility

Runtime evidence capability is host-side and separate from the sidekick bridge
version:

```json
{
  "runtimeEvidence": {
    "baselineAvailable": true,
    "supportedCollectors": [
      "logcat_window",
      "memory_summary",
      "frame_summary"
    ],
    "traceAvailable": false,
    "limits": {
      "baselineBytes": 2097152
    }
  }
}
```

Recompute this capability when device selection or ADB availability changes.
A missing collector becomes `unsupported`; it is not a bridge protocol
mismatch. A new MCP with an older incompatible CLI returns an explicit local
tooling update action. An older MCP simply does not expose the new collection
tool.

Persisted schema changes are additive. Existing `items`, `screens`, `itemId`,
`screenId`, `targetEvidence`, `targetReliability`, `sourceCandidates`,
`runtimeEvidence`, and `runtimeEvidenceIds` names do not change. New fields use
defaults so older session JSON remains readable. Specifically, a missing
`runtimeEvidencePolicy` means `manual`; only newly created sessions explicitly
receive `auto_on_handoff`.

## Evidence Honesty

Summaries are deterministic and never claim causality from temporal proximity
alone.

- Report exception type, tag, count, and time distance.
- Say “no matching error pattern in the selected window,” not “no error.”
- Mark cumulative frame output as `cumulative_not_windowed`.
- Mark screen or process drift.
- Keep source confidence independent from runtime evidence presence.
- Do not elevate an edit surface to exact ownership because a related log line
  exists.

## Testing Strategy

### CLI Unit Tests

- structured command construction without a shell;
- application-id validation;
- device serial scoping;
- timeout and process cleanup;
- stdout/stderr limits;
- logcat, meminfo, and gfxinfo parsing;
- deterministic unsupported classification;
- streaming redaction.

### MCP Unit And Integration Tests

- aggregate `complete`, `partial`, `failed`, and `unsupported`;
- enforce the 2.5-second baseline deadline;
- deduplicate one batch capture;
- detect serial, install epoch, PID, and screen changes;
- compute proximity;
- link one attachment set to multiple items;
- commit artifact before event linkage;
- replay the event after an interrupted snapshot save;
- remove orphan temporary bundles;
- degrade missing artifacts;
- preserve referenced evidence on quota failure;
- keep raw payloads out of session JSON, MCP response, and compact handoff.

### Console Harness

- switch Auto, Manual, and Off;
- render collecting and terminal evidence states;
- keep saved annotations when collection is partial or failed;
- recapture from annotation detail;
- fence late results after session or screen changes;
- avoid attaching to a closed session;
- preserve annotation usability at narrow viewport widths.

### No-Device Repro Harness

Use a fake ADB runner and temporary project root to reproduce success, timeout,
permission denial, unsupported commands, output truncation, process restart,
device drift, and artifact commit failure without Android. This is the main CI
surface for failure branches and follows the Orca lesson of separating
transport/collector failures from UI rendering failures.

### Connected Product-Path Proof

Redefine `runtime-evidence:smoke -- --strict` to:

1. create a sample-app feedback session and item;
2. collect baseline evidence on the selected real device or emulator;
3. attach it through the same MCP/session coordinator used by the product;
4. read the session back and verify device, package, time, and status metadata;
5. verify the redacted artifact exists under `.fixthis/`;
6. verify the compact handoff contains summaries but no raw artifact body;
7. restart the MCP/session store and verify event replay preserves attachment
   and item links.

The strict smoke must not synthesize a placeholder evidence row or pass solely
because a generic `adb logcat -d -t 80` command succeeded.

## Verification Commands

Focused and full verification:

```bash
./gradlew :fixthis-cli:test :fixthis-mcp:test --no-daemon
npm run runtime-evidence:smoke:test
npm run console:test:fast
npm run handoff:eval:test
npm run runtime-evidence:smoke -- --strict
npm run android:proof -- --strict
npm run external-fixture:matrix -- --strict
npm run release:check
git diff --check
graphify update .
```

No-device unit and harness checks run in CI. Strict Android checks require a
ready selected device and fail honestly when connected prerequisites are
missing.

## Implementation Sequence

1. Add neutral CLI collector contracts, fake-runner tests, limits, and
   redaction primitives.
2. Add additive MCP models, coordinator, artifact writer, and persistence event
   with replay tests.
3. Add the explicit collection MCP tool and manual console action while keeping
   the session default manual during implementation.
4. Add Save to MCP automatic baseline collection and the session mode control.
5. Convert strict runtime evidence smoke to the product path and prove connected
   replay.
6. After strict proof is green, set new sessions to `auto_on_handoff` by default.
7. Update maintained reference, privacy, troubleshooting, contributor, and
   release-readiness docs, then run the full verification matrix.

The temporary manual default is an implementation rollout sequence, not the
final product contract. The shipped design default is `auto_on_handoff`.

## Documentation Updates

Update maintained documentation rather than relying on this historical design
after implementation:

- `docs/reference/mcp-tools.md`;
- `docs/reference/output-schema.md`;
- `docs/reference/feedback-console-contract.md`;
- `docs/reference/privacy.md`;
- `docs/reference/threat-model.md` when the asset/control inventory changes;
- `docs/guides/troubleshooting.md`;
- `docs/guides/project-map.md`;
- `CONTRIBUTING.md`;
- `docs/contributing/release-readiness.md`;
- `docs/product/roadmap.md`;
- release notes and changelog required by the release process.

## Acceptance Criteria

- New sessions use `auto_on_handoff`; Manual and Off remain available.
- Existing sessions without `runtimeEvidencePolicy` resume in Manual mode.
- One Save to MCP batch produces at most one shared automatic capture bundle.
- Automatic collection never delays Save beyond the 2.5-second evidence budget.
- ADB or collector failure does not break existing feedback persistence and
  handoff.
- Device or install-context drift cannot be attached as normal evidence.
- Raw evidence never appears in session JSON, MCP tool results, or compact
  prompt output.
- Persisted artifacts are redacted, bounded, local, and ignored by Git.
- Evidence attachment and item links survive boot replay.
- Older persisted JSON remains readable and compatibility field names do not
  change.
- `:fixthis-compose-core` and the sidekick bridge remain unchanged in the first
  implementation.
- Strict runtime evidence proves the real product path instead of direct
  placeholder ADB capture.
- Focused, connected Android, external fixture, release, and Graphify checks
  pass before completion is claimed.

## Follow-Up Opportunities

After this design ships and evidence quality is measured, separate designs may
consider:

- capability-gated in-process Compose recomposition or snapshot-state evidence;
- explicit Perfetto/simpleperf presets;
- replayable navigation and assertion flows;
- automated evidence collection on verification failure;
- richer AndroidView/WebView boundary diagnostics.

These follow-ups must not be folded into the first implementation plan.
