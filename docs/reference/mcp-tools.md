# FixThis MCP

Primary agent workflow. The Android app shows MCP connection status. Selection,
comments, Copy Prompt, Save to MCP, and persistence happen in the desktop
console.

Codex plugin skills wrap the same CLI and MCP workflows. They do not change
the tool schemas below.

## Repository Sample

Gradle project `:app`, sources under `sample/`, package
`io.github.beyondwin.fixthis.sample`.

```bash
./gradlew :app:installDebug
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist
fixthis-cli/build/install/fixthis/bin/fixthis run --package io.github.beyondwin.fixthis.sample
```

`fixthis run` defaults to `:app:installDebug`.

## Architecture

```text
MCP client
  -> fixthis mcp over stdio
  -> ADB forward
  -> localabstract:fixthis_<packageName>
  -> debug app sidekick
```

The Android app does not host MCP. The in-app pill shows `MCP connected` only
while an authorized browser heartbeat is recent.

## Feedback Console

MCP-owned localhost UI. Sessions live under `.fixthis/feedback-sessions/`.
Mutating `/api/*` needs `X-FixThis-Console-Token` and a localhost Origin.

Typical flow:

1. `fixthis_open_feedback_console`
2. Connection card → `Ready`
3. Navigate the live preview
4. **Annotate** to freeze
5. Select targets, write comments
6. **Copy Prompt** or **Save to MCP** (Save follows runtime-evidence policy;
   Copy Prompt never starts collection)
7. `fixthis_list_feedback` → `fixthis_read_feedback` → `fixthis_claim_feedback`
8. Edit, then optional `fixthis_verify_feedback`
9. `fixthis_resolve_feedback`

`Annotate` does not save. Written comments on one freeze share one `screenId`.
Retries reuse `workspaceId` + `draftItemId`. Fingerprint mismatch returns
HTTP 409 `screen_fingerprint_mismatch`. Save to MCP is local persistence, not
an external AI call.

Draft recovery: `localStorage["fixthis.workspace.<sessionId>.<workspaceId>"]`.
v0.4 does not read pre-v0.4 `fixthis.pending.*` mirrors.

### Connection Recovery API

- `GET /api/connection` — current status
- `POST /api/app/launch` — launch when state is `WELCOME` or `OPEN_APP`

`state`: `WELCOME`, `READY`, `OPEN_APP`, `STARTING`, `RECONNECT`,
`CHOOSE_DEVICE`, `CHECK_PHONE`, `UNSUPPORTED_BUILD`.
`primaryAction`: `START`, `OPEN_APP`, `RECONNECT`, `TRY_AGAIN`,
`CHOOSE_DEVICE`, `CAPTURE`.

## Setup Output

```bash
fixthis setup --package <applicationId>
```

Prints `{ command, args, packageName, projectRoot }`. Use `command` and `args`
as separate values. `--write --target all` merges into Claude Code / Codex /
Cursor config. `--dry-run` previews. Restart the agent after `--write`.

## Stdio Rules

JSON-RPC on stdin. Responses on stdout only. Diagnostics on stderr.
`fixthis-mcp --console` is non-stdio: one startup JSON, then the HTTP server.

Methods: `initialize`, `notifications/initialized`, `notifications/cancelled`,
`tools/list`, `tools/call`, `resources/list`, `resources/read`, `ping`.

## Tools

`fixthis_list_feedback` and `fixthis_read_feedback` default to `delivery: sent`
items that are not yet resolved. Pass `includeAll: true` for everything.

`fixthis_status` — bridge reachability, package, activity, root count,
source-index, protocol `capabilities` (`targetEvidence`, `detailModes`).
`installStale` / `installStaleReason` mean host sources are newer than the
installed APK. Reinstall before trusting file:line.

`fixthis_get_current_screen` — current Compose screen, optional screenshot URI.

`fixthis_verify_ui_change` — current-screen text check. `expectedText` required.
Does not persist a receipt.

`fixthis_verify_feedback` — item-scoped verification against the persisted
baseline. Does not change item status. Session must be active; item must be
`delivery: sent` and `in_progress`.

Arguments: `sessionId?`, `itemId`, `assertions?` (max 8; `kind` is
`text_present`, `text_absent`, or `target_present`).

Request/state failures create no receipt. Prefixes:
`VERIFICATION_ITEM_NOT_SENT:`, `VERIFICATION_ITEM_NOT_IN_PROGRESS:`,
`VERIFICATION_BASELINE_NOT_FOUND:`, `VERIFICATION_ASSERTIONS_INVALID:`,
`VERIFICATION_CONTEXT_CHANGED:`, `VERIFICATION_ARTIFACT_FAILED:`.

Response: text plus `structuredContent.receipt`. No raw semantics or image
bytes. `pass` needs a reachable package, non-stale install, compatible
activity, high/medium target correspondence, at least one assertion, and no
failed/warning checks. Incomplete proof is `warn`. `SOURCE_INSTALL_STALE`,
`SCREEN_CONTEXT_MISMATCH`, `TARGET_NOT_FOUND`, `ASSERTION_FAILED` are `fail`.

`fixthis_open_feedback_console` — local console URL with capability in the
fragment. Treat the URL as a secret. Arguments: `packageName?`, `sessionId?`,
`newSession?`.

`fixthis_list_feedback_sessions` — resumable workspaces. `packageName?`,
`includeClosed?`.

`fixthis_capture_screen` — capture into the active session.

`fixthis_navigate_app` — one debug `back`, `tap`, or `swipe`. `tap` needs `x`,`y`.
`swipe` needs `direction`. `captureAfter` defaults true.

`fixthis_list_feedback` — queue summaries. `sessionId?`, `includeAll?` (default
false).

`fixthis_read_feedback` — JSON plus Markdown. `sessionId?`, `itemId?`,
`includeAll?`, `detailMode` (`compact` / `precise` / `full`, default `precise`).
`detailMode` changes Markdown only. JSON stays complete, including
`targetEvidence` and `targetReliability`. Compact Markdown includes `id:`,
`target:`, optional `editSurface:`, and an `agent_protocol:` footer. Same text
as **Copy Prompt**.

`fixthis_claim_feedback` — marks `in_progress`. Call before editing.
`sessionId?`, `itemId`, `agentNote?`. Rejects already-resolved items.

`fixthis_resolve_feedback` — `status` is `resolved`, `needs_clarification`, or
`wont_fix`. Optional `summary`. Optional `verificationReceiptId` for
`resolved` only; must be the latest `pass` or `warn` receipt. Omit it for
receipt-free resolution (`unverified`). Prefixes:
`VERIFICATION_RECEIPT_NOT_FOUND:`, `VERIFICATION_RECEIPT_MISMATCH:`,
`VERIFICATION_RECEIPT_NOT_LATEST:`, `VERIFICATION_RECEIPT_FAILED:`.

### Runtime evidence policy

Host-side, local-only. New sessions: `runtimeEvidencePolicy: "auto_on_handoff"`.
Legacy decode: `manual`.

- `auto_on_handoff` (**Auto**): Save to MCP runs `baseline` before the batch
  becomes sent. Typed evidence failure is recorded; valid feedback still sends.
- `manual` (**Manual**): no automatic collection. Use **Capture diagnostics**
  or `fixthis_collect_runtime_evidence`.
- `off` (**Off**): Studio skips collection. Explicit MCP calls still run.

Copy Prompt never starts collection.

`fixthis_capture_runtime_evidence` — legacy manual attachment. Does not run a
collector. `type`: `logcat_window`, `frame_summary`, `memory_summary`,
`trace_artifact`. `summary` max 240 chars. `artifactPath` must be under
`.fixthis/`.

`fixthis_collect_runtime_evidence` — host ADB collection. Presets: `baseline`,
`logs`, `memory`, `performance`. Returns metadata only (`attempted`,
`captureId`, `status`, `attachmentIds`, `linkedItemIds`, `artifactDirectory`,
`warnings`, `failureReason`, `skippedReason`). 2,500 ms deadline. Not a Bridge
`1.3` change.

### Optional SourceCandidate fields

In `fixthis_read_feedback` JSON, each item's `sourceCandidates` may include:

| Field | Meaning |
| --- | --- |
| `ranking` | 1-based rank |
| `scoreMargin` | `topScore - nextScore` |
| `evidenceStrength` | `STRONG` / `MEDIUM` / `WEAK` |
| `riskFlags` | e.g. `AREA_SELECTION`, `AMBIGUOUS` |
| `caution` | Present when `riskFlags` is non-empty |
| `callSites` | Only for `SHARED_COMPONENT`; capped at 10 |

`SHARED_COMPONENT` caps confidence at medium. `stale: true` means do not edit
by file:line.

`editSurfaceCandidates` are MCP/session hints (`role` = call site, component
definition, copy/data, layout/style, visual area, interop risk). Not bridge
fields.

### Agent claim/resolve protocol

1. `fixthis_list_feedback`
2. `fixthis_read_feedback({itemId})`
3. `fixthis_claim_feedback({itemId})` before any code change
4. Optional `fixthis_verify_feedback({itemId, assertions})`
5. `fixthis_resolve_feedback({itemId, status, summary, verificationReceiptId?})`

## Resources

- `fixthis://session/current`
- `fixthis://screen/current`
- `fixthis://screenshot/latest/full.png`
- `fixthis://screenshot/latest/crop.png`
- `fixthis://source-index`

Screenshot resources return a desktop path JSON, not binary image data.

## Cancellation And EOF

`notifications/cancelled` closes the active bridge request. stdin EOF cancels
in-flight work and exits.

## Local Bridge Limits

ADB + debuggable app only. Token via `adb shell run-as`. Length-prefixed JSON,
16 MiB cap. Explicit screenshot paths rejected. Missing/mismatched tokens
rejected. Failures: [Troubleshooting](../guides/troubleshooting.md).
