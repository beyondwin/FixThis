# Feedback Console Contract

**Status:** Current V1 Studio contract
**Owner surface:** `fixthis-mcp`

## Canonical Labels

| Surface | DOM id | Label |
| --- | --- | --- |
| Prompt copy | `copyPromptButton` | `Copy Prompt` |
| Agent handoff | `sendAgentButton` | `Save to MCP` |
| Canvas select tool | `selectToolButton` | `Select` |
| Canvas annotate tool | `annotateToolButton` | `Annotate` |
| Add pending annotation | `addItemButton` | `Add annotation` |
| Exit annotate mode | `cancelAddFlowButton` | `Exit Annotate` |
| Clear current selection | `clearSelectionButton` | `Clear Selection` |
| Clear draft feedback | `clearDraftButton` | `Clear Draft` |
| Refresh devices | `refreshDevicesButton` | `Refresh devices` |
| Clear FixThis device selection | `disconnectDeviceButton` | `x` icon |
| Workflow progress | `workflowProgress` | `FixThis feedback workflow` |
| Prompt readiness | `promptReadiness` | `Prompt readiness` |
| Compact history drawer | `historyToggleButton` | `History` |
| Preview frame state | `previewFrameStatus` | `Live preview` / `Frozen for annotation` / `Saved screen` / `Stale frame` / `No screenshot` / `Interaction blocked` |

## Mode Semantics

- Select mode is the normal preview mode. Preview clicks navigate the debug app when the bridge is ready.
- Annotate mode freezes the latest preview so the user can select Compose nodes or draw visual areas.
- Stale preview state keeps the last preview visible while live bridge actions are disabled.
- The workflow progress surface uses `data-workflow-step` values `connect`,
  `preview`, `annotate`, and `handoff`, with `data-state` values `complete`,
  `active`, and `upcoming`. It must remain visible whenever the console shell
  is visible.
- The prompt readiness surface stays visible near the handoff controls even
  when `Copy Prompt` and `Save to MCP` are disabled. It must explain the empty,
  draft-only, ready-to-copy, and ready-to-save states without requiring a toast
  or failed click.
- The preview frame state badge stays attached to the preview stage and must
  use one of the canonical labels above. `Interaction blocked` takes precedence
  over stale or frozen labels; `Stale frame` takes precedence over a live frame
  when bridge actions are disabled but the last image remains visible.
- Draft/history view shows persisted local feedback groups and sent handoff history.
- On compact layouts below the desktop history breakpoint, history remains
  reachable from the top-bar `History` control. Opening the drawer must expose
  the same saved evidence groups as the desktop history pane and support
  dismissal without changing the active session.
- The global status surface (`#error.global-status`) displays console-level
  error, warning, success, and info messages. Long bridge errors, partial
  handoff failures, and recovery diagnostics must wrap inside the viewport.
- Saved annotation rows render `draft`, `sent`, `sent_modified`,
  `in_progress`, `needs_clarification`, `wont_fix`, and `resolved` as distinct
  lifecycle phases. `needs_clarification` remains editable; `wont_fix` and
  `resolved` are terminal agent outcomes. Agent notes and summaries appear in
  the saved annotation detail.

## Persistence Semantics

- `Annotate` starts targeting and freezes the latest available preview. It does not write a session item by itself.
- `Add annotation` creates a browser-side pending annotation.
- `Copy Prompt` persists written pending annotations when needed, then copies compact agent-facing prompt text.
- `Save to MCP` persists written pending annotations when needed, then creates a local handoff batch for MCP tools.
- `Clear Draft` deletes unsent draft feedback after confirmation.
- Live preview frames are transient. Persisted `screens` are evidence snapshots, not every preview frame.
- Browser-only pending work is stored as a schema-v2 DraftWorkspace envelope
  under `localStorage["fixthis.workspace.<sessionId>.<workspaceId>"]`, with a
  per-session index at `localStorage["fixthis.workspace.index.<sessionId>"]`.
  The envelope carries `workspaceId`, `revision`, `lifecycle`, immutable
  `context`, frozen `screen`, `screenshotUrl`, `items`, and `history`.
- Legacy schema-v1 `localStorage["fixthis.pending.<sessionId>"]` envelopes are
  migrated into schema-v2 recovery workspaces after explicit user choice.
- On browser reload or session reattach, recovered pending work is not exposed
  automatically. The console shows an explicit Recover / Recapture / Discard
  banner; Recover is available only when the frozen preview context is present.
- Mutating saved/draft item APIs and preview/screen artifact URLs must carry
  the session that created the item, preview, or screen. They must not fall
  back to "current active session" when an explicit session id is available.
- Async save, update, delete, undo/redo, and session refresh responses are
  fenced by the annotation context that issued them. If the user switches
  sessions while a mutation is in flight, stale responses are discarded or
  followed by a fresh session refresh before rendering.
- Persisted feedback item `sequenceNumber` values are stable and monotonic
  within a session. Deleting or resolving a saved item does not renumber
  existing saved overlays or compact handoff item numbers.
- Before persisting a pending batch, the console sends the frozen screen
  fingerprint to `/api/items/batch`. The server compares it with a lightweight
  current capture when both fingerprints exist. HTTP 409 with
  `error: "screen_fingerprint_mismatch"` is recoverable UI state: prompt for
  re-capture, force-save, or cancel.

## Device Semantics

- The device-chip `x` clears only FixThis's active device selection and owned bridge resources.
- The device-chip `x` must not run `adb disconnect`, detach USB, or affect Wi-Fi ADB outside FixThis-owned resources.

## Privacy Semantics

- `Save to MCP` stores a local handoff batch.
- Pending recovery envelopes remain browser-local until `Copy Prompt` or
  `Save to MCP` persists the items into `.fixthis/feedback-sessions/`.
- FixThis does not upload screenshots, comments, prompt text, source hints, or target evidence by default.

## Compact handoff schema

Rule: source hints are candidates; verify screenshot, target, and code before editing.

### v2 prompt grammar (BNF-ish)

```
prompt        = header rule package_line source_root_line? "" screen_block+
header        = "# FixThis Feedback Handoff" ""
rule          = "Rule: source hints are candidates; verify screenshot, target, and code before editing." ""
package_line   = "- Package: `" pkg "`"
source_root_line = "- Source root: `" prefix "`"           ; emitted iff ≥2 distinct candidate paths share a directory-boundary prefix ≥10 chars
screen_block  = screen_header screenshot_line? viewport_line? activity_line? "" (overlap_block | item_block)+
screen_header = "Screen " short_id ": " display_name
short_id      = first 8 chars of UUID
screenshot_line = "screenshot: " path
viewport_line   = "viewport: " width "×" height          ; emitted iff screenshot has dims
activity_line   = "activity: " activity_name             ; emitted iff != display_name
overlap_block = "Overlap group " N " (resolve one marker at a time):" item_block+
item_block    = item_header id_line target_line crop_line? source_block reliability_block? ""
item_header   = "[" N "] " title                         ; title may include severity prefix
id_line       = "  id: " item_id
target_line   = "  " [ "role=" role "  " ] [ "tag=" tag "  " ] "box=(" x1 "," y1 ")-(" x2 "," y2 ")"
                 [ "  instance " i "/" total ]
                 [ "; targetRisk=overlap" ]
                 [ "; targetRisk=duplicate-of-marker-" M ]
crop_line     = "crop: " path
source_block  = candidate_line{1,3} caution_line?
candidate_line= "  " file ":" line "  conf=" lvl "  margin=" margin "  matched=[" terms "]"
                                                                          ↑ first line only; runner-ups omit margin/matched
                                                                          ; file is stripped of source_root prefix when present
caution_line  = "  note: " text                          ; emitted iff caution OR collision
reliability_block = target_confidence_line warning_line*
target_confidence_line = "  targetConfidence=" ("high" | "medium" | "low" | "unknown")
warning_line   = "  warning: " text
```

The `screenshot:` line is optional and omitted when no screenshot artifact is available for the screen.

The `crop:` line is optional and emitted only when a per-item screenshot crop path is available.

When no source candidates are available for the item, the source block consists of a single `  unknown` line.

- `N` — 1-based annotation number matching the numbered overlay marker.
- `short_id` — first 8 characters of the screen UUID.
- `viewport:` — screen dimensions in pixels; emitted only when the screenshot artifact has width and height metadata.
- `activity:` — Android activity name; emitted only when it differs from `display_name`.
- `Source root:` — directory-boundary common prefix of all candidate file paths in the session, with trailing `/`. Emitted only when at least two distinct candidate paths share a prefix of ≥10 chars; otherwise omitted and candidate paths are written verbatim. When emitted, the prefix is removed from every candidate line so each candidate appears as a relative path.
- target line — emits only the tokens that have content. `role=` and `tag=` are dropped when the corresponding `selectedNode` field is blank; the line collapses to bare `box=` for area selections.
- `[!]` severity prefix — prepended to the title when item severity is `HIGH`; absent for `MED` and `LOW`.
- `id:` — feedback item id. Agents should use this with
  `fixthis_claim_feedback` before editing and `fixthis_resolve_feedback` after
  finishing.
- `instance i/N` — emitted on the target line when multiple items on the same screen share the same `(top_candidate file:line, testTag)`; index assigned by `path`-leaf string sort order.
- `targetRisk=overlap` — present when the target participates in an overlap group (see below).
- `targetRisk=duplicate-of-marker-M` — present when this item is an exact duplicate of an earlier item (same source, testTag, path leaves, and bounds).
- candidate lines — up to 3 in score order. Rank 1 includes `margin=` (score gap to rank 2, formatted to 2 decimal places) and `matched=[...]` (up to 4 reason tokens). Runner-up lines include only `conf=`.
- `note:` — emitted when the rank-1 candidate has a `caution` field, or when multiple items in an instance group share the same call site (collision note on the first item only).
- Confidence is lowercase: `high`, `medium`, `low`, or `none`.
- `targetConfidence=` — optional target-level reliability. It describes how
  much the selected UI target can be trusted before editing; it is not task
  priority and is distinct from source-candidate `conf=`.
- `warning:` — optional target-level caveats, such as visual-area-only,
  possible AndroidView/WebView interop, stale source index, forced screen
  mismatch, missing fingerprint, or sensitive text redaction.

### Reason-token mapping

| Reason string | Token |
| --- | --- |
| `selected text` | `text` |
| `selected contentDescription` | `contentDescription` |
| `selected testTag` | `tag` |
| `selected testTag convention composable` | `compTag` |
| `selected role` | `role` |
| `nearby text` | `nearbyText` |
| `nearby contentDescription` | `nearbyContentDescription` |
| `nearby testTag` | `nearbyTag` |
| `nearby role` | `nearbyRole` |
| `activity` | `activity` |
| `selected stringResource` | `stringRes` |
| `arbitrary literal` | `literal` |
| `legacy fallback` | `legacy` |

These tokens appear in `matched=[...]` on rank-1 candidate lines.

### Overlap groups

When two or more annotations on the same screen have targets that overlap (visual-area intersection IoSA >= 0.25, or weak-label center distance <= 24dp at default density 1.0), they are collected into an explicit overlap group:

```
Overlap group N (resolve one marker at a time):
```

Each item in the group carries `targetRisk=overlap` on its `ui:` line. Resolve the group one marker at a time to avoid editing the wrong composable. Coordinate space is window pixels at default density 1.0; the 24dp center-distance fallback is conservative on high-density screens.

### v1 → v2 token migration

Tool-using agents that parsed the v1 compact prompt format must update the following token patterns. The v2 format is strictly more verbose and uses different but equally parseable tokens; no information is lost.

| v1 token | v2 token | Rationale |
| --- | --- | --- |
| `src? <file>:<line> <conf>` (single line) | indented `<file>:<line>  conf=<lvl>` lines (up to 3, no header, no `~` prefix) | Two-space indent + `conf=` token is enough to distinguish candidate lines for an agent. The header and `~` prefix were boilerplate. Runner-up candidates are now visible. |
| `bounds=L,T - R,B` | `box=(L,T)-(R,B)` | Matches typical `(x,y)` notation. The earlier `[W×H]` size suffix was dropped — derivable from the box, and agents can compute it on demand. |
| `target: Node "tag"` | `[role=<role>  ][tag=<tag>  ]box=...` (with `role=`/`tag=` dropped when blank) | Drops unvarying `Node`/`Area` fallback role and the `tag=(none)` placeholder. Each token is keyed and only emitted when it carries information. |
| `why=<token>+<token>` | `matched=[<token>, <token>]` | Renamed for clarity: `matched` describes what evidence was found; `why` was ambiguous metadata. The same reason-token vocabulary is reused. |
| `risk=<token>` (on source line) | `targetRisk=<token>` (on `ui:` line) and `note: <text>` (after candidates block) | Splits target-level risk (`overlap`, `duplicate-of-marker-N`) from source-level caution; v1 conflated them on one line. |
| Screen UUID `<full-uuid>` | `<first-8-chars>` (short-id) | Reduces visual noise; 8 hex chars are unique enough for disambiguation within a session. |
| (absent) | `viewport: W×H` | New: lets agents interpret pixel bounds without opening the screenshot. |
| (absent) | `activity: <name>` | New: emitted when Android activity name differs from `displayName`. |
| (absent) | `instance i/N` on `ui:` line | New: disambiguates list-rendered widgets that share a call site. |
| (absent) | `note: N markers map to same call site — likely list-rendered; disambiguate by instance index` | New: collision signal on the first item of each instance group. |
| (absent) | `targetRisk=duplicate-of-marker-M` | New: surfaces true marker duplication so agents do not double-resolve. |
| (absent) | `- Source root: \`<prefix>\`` header + relative candidate paths | New (2026-05-10 trim): hoist the directory-boundary common prefix of all candidate paths once, strip from each candidate line. Net token saving on long monorepo paths; absent for sessions whose candidates do not share a prefix. |

## Console state model

### Canonical Runtime State

The browser console uses one canonical `ConsoleAppState`. DOM events and
network responses dispatch commands/events into a reducer. The reducer returns
the next state plus effect descriptions; browser adapters execute those effects
and dispatch fenced results back into the store.

Renderers consume selector view models only. They do not mutate session,
preview, draft, tool, polling, or prompt state. Draft work is represented by
`workspace.kind = "draft"` and is locked to an immutable session/preview/screen
context until saved, moved to recovery, or discarded.

Browser-internal legacy state holders such as `activeDraftFlow`,
`draftFeedbackItems`, `focusedPendingItemIndex`, and `currentSelection` are not
supported. This does not change MCP tool contracts, HTTP route payloads,
persisted feedback-session JSON, or local draft storage migration
compatibility.

Cross-session navigation with unsaved draft work creates `pendingBoundary`; the
boundary sheet is the only UI path that can save, recover, discard, or cancel
that transition. The same boundary state renders as a centered modal on desktop
and a bottom sheet/full-width modal on mobile.

## Bundle pipeline

The console bundle is generated by `scripts/build-console-assets.mjs` from
the source files in `fixthis-mcp/src/main/console/`. Source order is a
topological sort over `// @requires` directives at the top of each file.
The build emits a minified `app.js` (≤ 190 KiB raw / ≤ 45 KiB gzip), an
external source map linked via `//# sourceMappingURL=app.js.map`, and a
`console-build-meta.json` sidecar that `FeedbackConsoleAssets.kt` inlines
into `window.FixThisConsoleConfig.buildMeta` at serve time.

Identifier minification is disabled (`minifyIdentifiers: false`) so
asset-contract tests grep for stable function names. A `CONTRACT_SYMBOLS`
list in the build script guards each test-asserted symbol against
accidental minifier elimination. Tests now read unbundled JS from
`fixthis-mcp/src/main/console/*.js` via `ConsoleSourceFixtures` (see
`fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/fixtures/ConsoleSourceFixtures.kt`)
rather than the minified `app.js`.
