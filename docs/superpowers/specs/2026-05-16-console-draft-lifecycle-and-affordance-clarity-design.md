# Console Draft Lifecycle and Affordance Clarity — Design

Status: Design for review
Date: 2026-05-16
Scope: `:fixthis-mcp` browser feedback console (HTML, CSS, JS, draft persistence,
session boundary protocol, status surface coordination). Out: Android sidekick,
MCP tool signatures, server schema field renames, bridge protocol.

Related prior specs (already merged in part):
- [`2026-05-15-console-draft-session-lifecycle-hardening-detailed-spec.md`](2026-05-15-console-draft-session-lifecycle-hardening-detailed-spec.md)
  — established the single-reducer boundary protocol foundation.
- [`2026-05-15-duplicate-draft-feedback-hardening-implementation-details.md`](2026-05-15-duplicate-draft-feedback-hardening-implementation-details.md)
  — closed five save-path dedup gaps.
- [`2026-05-15-quiet-draft-recovery-design.md`](2026-05-15-quiet-draft-recovery-design.md)
  — defined quiet recovery semantics.
- [`2026-05-14-draft-workspace-state-machine-design.md`](2026-05-14-draft-workspace-state-machine-design.md)
  — workspace/revision model.

## Summary

The console's structural draft and session machinery has been hardened, but the
user-facing surface still produces three recurring complaints:

1. Switching sessions, creating new sessions, or refreshing the browser while a
   target is selected but no comment has been typed yields confusing behaviour
   and, sometimes, duplicate entries on recovery.
2. The action buttons in the annotation editor (`Delete`, `Done`, `Clear
   Selection`, `Exit Annotate`, `Clear Draft`) overlap semantically and do not
   reflect that local persistence is already automatic.
3. Destructive controls (session `×`, device picker `×`, comment `Clear`) are
   icon-only and do not preview consequences; navigation back to the
   annotation list has no destination label.

This spec closes the user-facing gap by:

- Adopting a strict **empty-annotation policy** (no draft exists until the
  first comment character is typed).
- Defining a complete **trigger × state matrix** for every context transition
  that can affect an in-flight annotation.
- Redesigning action affordances so each button reveals destination, state,
  and consequence — and so the bottom action bar varies by editor state.
- Coordinating the eight independent status surfaces under a single
  `StatusSurfaceRegistry` so that disconnect, recovery, and boundary events do
  not stack into a noisy or unresponsive UI.

The goal is not new features. The goal is to make the existing system
predictable, recoverable, and visually honest about what it is doing.

## Problem Statement

User-reported and code-evidenced symptoms still present after prior hardening:

- **S1 (semantics).** Clicking an element selects a target. With no comment
  typed, switching sessions, pressing `+` to create a session, or refreshing
  the browser can leave behind an "empty" workspace artifact, or simply lose
  the target with no signal.
- **S2 (recovery dedup gap).** Empty-target artifacts that do reach
  localStorage can resurface on reload. Subsequent real input collides with
  the recovered empty entry and the save path persists both, producing a
  duplicate.
- **S3 (transition opacity).** Discards, switches, saves, and recoveries
  happen with little or inconsistent visual feedback, so the user cannot tell
  what changed when they return to the console.
- **S4 (action-bar overlap).** `Delete`, `Done`, `Clear Selection`, `Exit
  Annotate`, `Clear Draft`, and `Add annotation` co-exist in the inspector
  footer with overlapping semantics. The user cannot predict the difference
  between `Clear Selection` and `Clear Draft` before clicking.
- **S5 (destructive icon ambiguity).** Session `×`, device picker `×`,
  comment `Clear`, and the back affordance from the editor expose
  potentially destructive results behind icons without text or consequence
  preview.
- **S6 (status surface stacking).** The console has eight independently
  toggled status surfaces with no central coordinator. On device disconnect,
  banner + lock bar + canvas overlay + stale notice + badge can all show at
  once, producing a visually broken state.

These symptoms share one root cause: lifecycle invariants and affordance
invariants are implicit. Each call-site decides on its own when to mutate
state, when to show a notice, and what label to put on a button. The fix is
to make the implicit explicit and to centralize the few coordination points
that are currently spread across many call-sites.

## Goals

- Empty (comment-less) annotations cannot exist anywhere — memory,
  localStorage, server, or recovery envelope.
- Every context transition that can affect an in-flight annotation goes
  through one boundary protocol whose behaviour is determined by the current
  draft state (None / Pending / Draft / Saved).
- Every button in the annotation editor reflects an operation that is
  actually available in the current state, with a label that names the
  destination or consequence.
- Destructive actions show consequence before commit (item count, scope,
  reversibility).
- Navigation from editor to list names its destination and triggers the same
  boundary protocol other context transitions use.
- Status surfaces are routed through one registry that enforces priority,
  mutual exclusion, and auto-dismiss rules. No two modal-class surfaces ever
  cover the canvas simultaneously.
- New invariants are encoded as console regression tests so future changes
  cannot reintroduce the same class of bug.

## Non-Goals

- Changing MCP tool signatures or persisted JSON field names.
- Rewriting the Android sidekick or bridge protocol.
- Adding cloud sync, multi-user collaboration, or notifications.
- Replacing the browser bundle system (no TypeScript / ESM migration in
  this pass).
- Auto-committing first-time annotation submissions to the session (the
  explicit `Add annotation` moment is preserved).
- Search, filter, archive, or rename of sessions (separate spec).
- Onboarding, tutorials, or empty-state coaching (separate spec).
- New keyboard shortcuts beyond what current code already binds.

---

## Section 1 — Draft Semantics (Policy A)

**Definitions:**

- **None** — no element target is selected. No editor panel is open.
- **Pending Target** — an element or area is selected and the editor panel is
  visible. The comment is empty (`comment.length === 0`). No workspace,
  revision, or persisted artifact exists.
- **Draft** — the comment has length ≥ 1. A workspace is allocated with a
  monotonically increasing revision. The draft is persisted to localStorage
  and surfaces in recovery on reload.
- **Saved** — the annotation has been committed to the session by an
  explicit `Add annotation` action. Server holds it; subsequent edits
  auto-save against the workspace revision fence.

**Transitions:**

```
None ──(click element)──▶ Pending Target
Pending Target ──(first comment char)──▶ Draft   [workspace created, rev=1]
Pending Target ──(target change)──▶ Pending Target  [target swap, no draft]
Pending Target ──(discard trigger)──▶ None       [target dropped]
Draft ──(comment cleared to 0 chars)──▶ Pending Target  [workspace freed]
Draft ──(edit)──▶ Draft  [rev++, persisted]
Draft ──(Add annotation)──▶ Saved
Draft ──(discard trigger)──▶ None  [boundary protocol gates this]
Saved ──(edit)──▶ Saved  [auto-save, rev++]
Saved ──(Delete annotation)──▶ None  [server delete, workspace freed]
```

**Invariants:**

1. A workspace exists ⇔ a Draft or Saved annotation exists in that workspace.
2. A persisted (localStorage or server) annotation has a comment of length
   ≥ 1.
3. Recovery envelopes never contain entries with empty comments.
4. Discarding a Pending Target is silent at the data layer but visible at
   the UX layer (Section 4).

**Files affected:** `draftWorkspace.js`, `draftUseCases.js`,
`draftCommandQueue.js`, `draftStorageAdapter.js`, `pendingPersistence.js`,
`historyPendingDedupe.js`, server-side `FeedbackSessionStoreDelegate` (input
validation).

## Section 2 — Context Transition Matrix

All context transitions go through a single boundary protocol whose behaviour
is determined by the current state. The matrix below is the authoritative
specification.

| # | Trigger | None | Pending Target | Draft (comment ≥ 1) |
|---|---|---|---|---|
| 1 | Session switch (select existing) | pass | discard + toast "🔄 Switched · 1 empty discarded" | boundary dialog (save / discard / cancel) |
| 2 | Session create (`+` button) | pass | discard + toast | boundary dialog → on save, create new session |
| 3 | Current session delete | pass | discard + toast | boundary dialog (must save to retain) |
| 4 | Screen switch within session | pass | discard + toast "📷 Different screen" | Draft preserved (anchored to its own screen) |
| 5 | New capture / Android nav | pass | discard + toast "📷 New screen · 1 empty discarded" | Draft preserved (anchored to its captured screen) |
| 6 | Click different element on same screen | pass | swap target silently (clear intent) | Draft preserved; target-change confirm if Draft target differs |
| 7 | `Esc` key | pass | discard + toast "🎯 Cancelled" | discard confirm if comment ≥ 1, else close |
| 8 | Browser refresh (F5 / Cmd+R) | pass | silent loss, **no recovery** | `beforeunload` guard + recovery on reload |
| 9 | Tab / window close | pass | silent loss, **no recovery** | `beforeunload` + recovery on reopen |
| 10 | Browser back / forward | pass | discard + toast | boundary dialog (treat as route change) |
| 11 | Server disconnect | pass | retain target locally + "🔌 Reconnecting…" badge | retain + badge + persist as usual |
| 12 | Reconnect / preview resume | pass | revalidate target; if stale, discard + toast | revalidate; if target stale, mark stale and keep |
| 13 | Bridge protocol mismatch | pass | discard + toast "⚠ Bridge upgraded" | retain locally, surface single migration notice |
| 14 | Activity drift (Android foreground change) | pass | retain pending target | retain Draft |
| 15 | Inactivity timeout | pass | retain (revalidate on next input) | retain |

**Implementation notes:**

- The boundary protocol is the one already introduced in the 2026-05-15
  lifecycle hardening spec. This section extends it by enumerating every
  trigger and codifying the Pending-Target row, which previously had no
  consistent treatment.
- The "discard + toast" rows are silent at the storage layer (nothing is
  removed because nothing was ever persisted) and visible at the UX layer
  via Section 4.

## Section 3 — Recovery and Dedup Gap Closure

**Root cause of S2:** prior dedup hardening targeted the save path. Empty-
comment artifacts predating Policy A can still be in user localStorage. On
reload they surface as recovery candidates, and the new input collides with
them during save.

**Mitigations:**

1. **Load-time filter.** When recovery is loaded, drop entries with
   `comment.length === 0` before they enter the recovery envelope or
   dedupe path. Log dropped count to console for diagnostic visibility.
2. **Persistence guard.** Drafts whose comment becomes empty mid-edit free
   their workspace and remove their localStorage entry. The next non-empty
   keystroke allocates a fresh workspace.
3. **Schema validation.** The server save endpoint rejects items with
   `comment.length === 0` (validation error, typed). Belt and braces for
   any client that bypasses the policy.
4. **Migration.** No active migration code is required — entries are simply
   ignored on load. After one session, they decay out of localStorage
   naturally via the existing eviction logic. If the existing eviction does
   not remove ignored entries, add explicit removal at load time.

**Files affected:** `draftStorageAdapter.js`,
`historyPendingDedupe.js`, `pendingPersistence.js`, `draftWorkspace.js`,
server `FeedbackItemRoutes` / `FeedbackSessionService` for the validation
guard.

## Section 4 — Transition Transparency Toasts

State transitions surface short, non-modal feedback. Toasts are routed
through the registry of Section 10 and are subject to its priority rules.

| Event | Signal | Duration |
|---|---|---|
| Pending Target → discarded (auto: session switch, screen change, route, refresh) | "🔄 Switched · 1 empty annotation discarded" (or context-appropriate variant) | 2s |
| Pending Target → discarded (user: `Esc`, `Cancel`) | "🎯 Cancelled" | 1.5s |
| Draft → saved | "✓ #N saved" | 2s |
| Draft → discarded (user) | "🗑 1 draft discarded · Undo" | 5s, Undo restores from in-memory snapshot |
| Reload → draft recovered | "🔄 N draft(s) recovered" | 3s |
| Server save in progress (Saved auto-save) | "●" indicator in inline status (Section 6) | continuous |
| Empty-comment legacy entry skipped on load | (silent, `console.log` only) | — |

**Principles:**

- Toasts never block input.
- No modal nag for routine transitions.
- Undo is offered only where it has real meaning (Draft discard). Pending
  Target undo is omitted — re-selection is one click away.

## Section 5 — Destructive Action Affordances

Applies to all destructive controls in the console.

**Principle:** *destructive actions use words, not icons; consequence is
shown before commit.*

### 5.1 Session `×` button

**Remove** the bare `×` from each session row.

**Replace with:**

- A trash icon (`🗑`) revealed on row hover, with `aria-label="Delete
  session"` and a visible tooltip on hover.
- Click triggers a confirm dialog:

  ```
  Delete "<session name>"?
  Removes N annotations across M screens.
  + (if drafts present) Includes K unsaved drafts.
  Cannot be undone.
  [Cancel]  [Delete]
  ```

Panel collapse, if separate, moves to an explicit `▾` chevron in the panel
header. Collapse never affects data.

### 5.2 Comment `Clear` button

**Remove** the existing `Clear` (or `×`) button inside the comment area. Its
two implicit meanings are split:

- *Clear text only*: rely on keyboard standard (`Cmd/Ctrl+A` then
  `Backspace`). No dedicated button.
- *Cancel annotation*: covered by the inspector action bar (Section 7) or
  the back affordance (Section 8). Both use the word "Cancel" rather than
  `×`. In Draft state, Cancel confirms with character count.

### 5.3 Device picker `×` button (`#disconnectDeviceButton`)

**Replace** the bare `×` with a trash-style icon and an explicit label,
e.g. `Forget device`, revealed on hover or surfaced inline. Click triggers
a confirm:

```
Forget "<device name>"?
The next device click in the picker will pair fresh. Active session
data is unaffected.
[Cancel]  [Forget]
```

The control is moved to the device picker overflow if it crowds the topbar.

## Section 6 — Annotation Editor Action Bar (Replaces Delete / Done)

The inspector footer currently shows `Clear Selection`, `Exit Annotate`,
`Add annotation`, and `Clear Draft` in all states. This is replaced by a
state-dependent action bar.

### State 1 — Pending Target (comment === "")

```
[ ← All annotations ]                                  (Section 7, top-left)
[ Comment textarea ]    💡 "Type to save"   (inline hint inside textarea row)
                                                      [ Cancel ]
```

- No `Delete`, no `Done`, no `Add annotation`.
- `Cancel` is `Esc` equivalent; discard is silent at the data layer.

### State 2 — Draft (comment.length ≥ 1, not yet on server)

```
[ ← All annotations ]
[ Comment textarea ]    ● "Saved locally"
                                  [ Cancel ]  [ Add annotation ]
```

- `Add annotation` is the **only** explicit commit moment.
- `Cancel` opens a confirm dialog naming the character count.
- No `Delete` — nothing has been committed.

### State 3 — Saved (committed annotation under edit)

```
[ ← All annotations ]
[ Comment textarea ]    ✓ "Saved Ns ago"
                                  [ ⋯ ]
```

- The "Done" button is removed entirely. Closing is via Section 7's back
  affordance.
- The `⋯` overflow menu contains:
  - `Revert changes` (enabled only if unsaved local edits exist)
  - `Delete annotation` (confirms with consequence preview)
- Live status `✓ Saved Ns ago` honestly reflects auto-save.

### Principle

> *Buttons in the action bar reflect operations that are actually possible
> in the current state. Automatic behaviour is shown as status, not as a
> button.*

### Intentional trade-off

The first commit (`Add annotation`) remains explicit. Auto-committing every
in-progress comment would send half-formed annotations. Subsequent edits to
a Saved annotation auto-save (the user has already chosen to commit).

## Section 7 — Editor ↔ List Navigation

The back affordance from the editor to the annotation list is a single
top-left button with a destination label.

### Visual

```
┌────────────────────────────────────────┐
│ ← All annotations                      │   top-left, always present in editor
│                                        │
│   [ comment textarea ]                 │
│   [ ... state-specific action bar ... ]│
└────────────────────────────────────────┘
```

### Behaviour matrix

| Editor state | "← All annotations" click |
|---|---|
| Pending Target | discard + toast "🎯 Cancelled" + back to list |
| Draft | boundary dialog (save → list / discard → list / cancel → stay) |
| Saved, clean | back to list immediately |
| Saved, autosave in flight | wait ≤ 200ms for in-flight save, then back to list (no UI lag visible) |

### Principles

- Navigation buttons name their destination.
- If the navigation has data consequences, it triggers the same boundary
  protocol the rest of the system uses (Section 2). No bespoke per-control
  logic.

### Section 6 reconciliation

The Saved-state `Close` previously drafted in Section 6 is removed; the
back affordance covers it. The Saved-state action bar collapses to the
`⋯` overflow only.

## Section 8 — Inspector Footer Redesign

Current footer at `fixthis-mcp/src/main/resources/console/index.html:180-183`:

```html
<button id="clearSelectionButton">Clear Selection</button>
<button id="cancelAddFlowButton">Exit Annotate</button>
<button id="addItemButton" class="primary" disabled hidden>Add annotation</button>
<button id="clearDraftButton">Clear Draft</button>
```

Four buttons of overlapping semantics are visible regardless of state.

**Replacement:** the footer becomes a *single state-dependent slot* whose
contents are exactly the Section 6 action bar. The old four-button block is
removed.

| Editor state | Footer contents |
|---|---|
| None (no editor open) | (footer hidden) |
| Pending Target | `Cancel` (right-aligned) |
| Draft | `Cancel`  ·  `Add annotation` (primary, right-aligned) |
| Saved | `⋯` overflow menu (right-aligned) |

`Clear Selection`, `Exit Annotate`, and `Clear Draft` IDs are deprecated in
this pass:

- `Clear Selection` → covered by `Cancel` in Pending Target.
- `Exit Annotate` → covered by Section 7 back navigation.
- `Clear Draft` → covered by the `⋯` overflow's `Revert changes` (in Saved
  state) or `Cancel` confirm (in Draft state).

Element IDs and any associated tests are updated accordingly. The HTML in
`index.html` is restructured to host the dynamic footer; CSS for the four
buttons can be removed.

## Section 9 — Boundary Dialog Label Specification

`#sessionBoundarySheet` at `fixthis-mcp/src/main/resources/console/index.html:154-165`
holds four `data-boundary-action` buttons whose labels are set dynamically.
The labels must be consequence-explicit. The dialog text and the four button
slots are specified below; the existing JS that fills `data-boundary-action`
must conform.

### Common dialog structure

```
[ <Title> ]
[ <Summary describing what's at stake — annotation count, target name>
  <If destination is named: ", switching to '<dest name>'"> ]

[ Cancel ] [ Discard ] [ Save ] [ <action-specific primary> ]
```

### Variants

| Trigger | Title | Summary template | Primary action label |
|---|---|---|---|
| Session switch (Draft present) | "Save draft before switching?" | "1 unsaved draft in '<current>'." | `Save and switch` |
| Session create (`+` with Draft) | "Save draft before creating new session?" | "1 unsaved draft." | `Save and create` |
| Session delete (current, Draft present) | "Save draft before deleting?" | "Deleting '<current>' removes N saved annotations across M screens." | `Save and delete` |
| Editor back (Draft) | "Save draft before going back?" | "1 unsaved draft." | `Save and back` |
| Route change (back/forward) | "Save draft before leaving?" | "1 unsaved draft." | `Save and leave` |

### Rules

- Primary button label always includes the destination verb (`switch`,
  `create`, `delete`, `back`, `leave`).
- Destructive secondary label is the bare verb (`Discard`) — never `Yes` /
  `No` / `OK`.
- `Cancel` is always present and is always the leftmost button (closest to
  "do nothing").
- Summary includes the exact item counts that will be affected.
- Buttons that do not apply in a given variant are hidden (not disabled),
  so the dialog has a consistent visual rhythm.

## Section 10 — Status Surface Coordination

The console has eight independently-toggled status surfaces (Section
problem statement, S6). They are routed through one coordinator.

### `StatusSurfaceRegistry`

A small JS singleton with no UI of its own. Every existing surface module
calls into it instead of toggling its own `hidden` attribute. The registry
enforces:

1. **Priority**: critical > error > warning > info.
2. **Mutual exclusion**: only one *modal-class* surface is visible at a time
   (`sessionBoundarySheet`, `canvasBlockedOverlay`). When a modal is active,
   inline canvas-scoped surfaces are hidden.
3. **Stacking**: banner-class surfaces stack vertically up to 1 visible at a
   time (others queue and show on dismiss). Toast-class surfaces stack up to
   3 (FIFO eviction).
4. **Auto-dismiss**: info ≤ 3s, warning ≤ 10s, error/critical persistent
   until acknowledged.

### Surface classification

| Surface | Class | Behaviour under registry |
|---|---|---|
| `sessionBoundarySheet` | Modal | Single modal at a time |
| `canvasBlockedOverlay` | Modal (canvas-scoped) | Hidden while `sessionBoundarySheet` active |
| `stalenessBanner` | Banner | Max 1 visible; queued if another shown |
| `canvasStaleNotice` | Inline | Hidden when any modal-class active |
| `draftLockBar` | Inline | Hidden when any modal-class active |
| `previewStaleBadge` | Badge | Hidden when `canvasBlockedOverlay` active |
| `error` (`#error`) | Toast | Max 3 stacked, FIFO |
| `deviceStatus` pill | Pill (topbar) | Always visible position, content updates only |

### Z-index tokenization

Replace scattered raw z-index values for status surfaces with CSS custom
properties:

```css
:root {
  --z-pill:    5;
  --z-badge:  10;
  --z-banner: 30;
  --z-overlay: 50;
  --z-modal:  100;
  --z-toast:  200;
}
```

Layout-level z-index (cards, panels — current values 1–4) remain raw;
surface-level values must use these tokens. A lint rule (regex on CSS) flags
raw numeric z-index in surface-class selectors.

### Disconnect UX specification

Today's symptom: banner + lock bar + canvas overlay + stale notice + badge
may all appear together when the device disconnects.

After this spec:

1. `canvasBlockedOverlay` shows alone: `🔒 Device disconnected ·
   Reconnecting…` plus a `Retry now` button.
2. While the overlay is active, all inline canvas surfaces
   (`canvasStaleNotice`, `draftLockBar`, `previewStaleBadge`) are hidden
   via the registry.
3. The topbar `deviceStatus` pill always-on slot updates to `Disconnected`.
4. The `stalenessBanner` shows **only** when a dirty Draft would be lost on
   prolonged disconnect — message: `⚠ 1 unsaved draft preserved locally`.
   Never duplicates the disconnect message.

### Reconnect UX specification

1. Overlay fades out (300ms).
2. `previewStaleBadge` briefly shows `Connection restored · refreshing
   preview` (2s) and then resumes its normal logic.
3. If reconnect leaves the previous target stale, a single notice surfaces
   — not three.

### Invariants enforced by tests

- At most one modal-class surface visible at a time.
- Overlay active ⇒ inline canvas surfaces hidden.
- Error toast stack ≤ 3.
- All surface-class z-index values resolve to one of the tokens.

## Section 11 — Out of Scope

To keep this spec single-plan-executable, the following are explicitly
deferred:

- Session search, filter, archive, rename. Captured as a future spec.
- First-user onboarding, tutorials, empty-state coaching.
- Auto-commit of first-time `Add annotation` (preserved as explicit moment).
- Agent progress visualization in the console (`claim` / `resolve` status
  surfaced post-handoff). Explicitly rejected during brainstorming on the
  principle that the agent's own surface is the status surface.
- New keyboard shortcuts beyond what current code binds.
- MCP tool signature or schema changes.
- Android sidekick or bridge protocol changes.
- Device picker decomposition (the device-control cluster of
  `index.html:24-36` has its own affordance issues — covered separately).
- Copy-Prompt-vs-Save-to-MCP guidance (separate UX spec).

## Section 12 — Testing and Verification

### New / extended console regression tests

1. **Policy A invariants**
   - Selecting an element with no comment → switching session → no recovery
     entry exists on next load.
   - Typing one character → switching session → boundary dialog fires.
   - Editing a Draft to empty string → workspace is freed → next character
     allocates a fresh workspace.

2. **Trigger matrix coverage**
   - For each of the 15 triggers in Section 2, assert the documented
     behaviour for each of None / Pending / Draft states.
   - Asserted via existing console JS harness; new cases co-located with
     `console-routes-test-*` style.

3. **Action bar correctness**
   - Each editor state renders exactly the buttons specified in Section 6.
   - `Add annotation` only appears in Draft state.
   - `⋯` overflow appears only in Saved state.

4. **Back navigation**
   - `← All annotations` is present in all editor states.
   - Click in Draft state fires boundary dialog; click in Pending fires
     discard toast; click in Saved (clean) immediately returns.

5. **Boundary dialog labels**
   - Each variant in Section 9 renders the specified title, summary, and
     button labels.
   - Cancel is always leftmost; primary action verb matches destination.

6. **Status surface coordination**
   - Modal-class count ≤ 1 at all times.
   - Inline surfaces hidden when overlay active.
   - Error toast queue ≤ 3 (4th eviction is FIFO).
   - CSS lint: no raw z-index on surface-class selectors.

7. **Disconnect / reconnect flow**
   - Simulated disconnect shows only the overlay + topbar pill update.
   - Reconnect dismisses overlay, surfaces a single stale notice if
     applicable.

### Manual verification

- Keyboard-only flow: select element, type comment, `Cmd+Enter`, switch
  session, return — confirm transparency toasts fire and no duplicates
  appear after reload.
- Force disconnect (kill server) and observe single-surface presentation.

### Out-of-band telemetry

- Console logs the count of empty-entry recovery skips on load.
- Status registry logs surface state changes for debugging (no UI impact).

## Section 13 — Implementation Sketch (for the plan)

The implementation plan derived from this design is expected to split into
phases. This sketch is illustrative only; the plan itself is authoritative.

### Phase 1 — Foundation

- Policy A enforcement in `draftWorkspace.js` / `draftUseCases.js`.
- Load-time empty-entry filter in storage adapter and dedup paths.
- Server save validation for empty comment.
- Console regression tests for Section 1 invariants.

### Phase 2 — Trigger matrix completion

- Audit each trigger from Section 2 in current code; route Pending and Draft
  rows through the unified boundary protocol.
- Pending-Target row implementations + toasts.
- Regression tests for the 15 × 3 cells.

### Phase 3 — Action bar and navigation

- Replace inspector footer with state-dependent slot (Section 8).
- Implement Section 6 action bars per state.
- Implement Section 7 back affordance.
- Remove deprecated button IDs and update related tests.

### Phase 4 — Destructive affordances and dialog labels

- Section 5 changes for session, device picker, and comment Clear.
- Section 9 boundary dialog wiring.

### Phase 5 — Status surface coordination

- Introduce `StatusSurfaceRegistry`.
- Route the eight existing surfaces through the registry.
- Tokenize surface z-index; add lint rule.
- Disconnect / reconnect flow per Section 10.
- Tests for the four coordination invariants.

Each phase ends with green console regression tests and a passing local PR
checklist (per `CONTRIBUTING.md`).

## Open Questions

None blocking design approval; the following are to be resolved in the
implementation plan, not here:

- Exact placement of the `⋯` overflow menu (right of comment row vs. footer
  right-aligned). To be decided based on inspector layout fit.
- Whether the Pending Target inline hint (`💡 Type to save`) lives inside
  the textarea border or as a separate line directly above the action bar.
- Animation timing for overlay fade and toast dismiss (use existing
  `transition-fast` / `transition-slow` tokens if present in CSS).

These are visual polish items and do not affect the semantic invariants of
this spec.
