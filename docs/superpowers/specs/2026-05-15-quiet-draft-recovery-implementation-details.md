# Quiet Draft Recovery — Implementation Details

**Date:** 2026-05-15
**Status:** Implementation reference
**Companion docs:** `2026-05-15-quiet-draft-recovery-design.md`, `../plans/2026-05-15-quiet-draft-recovery.md`
**Primary modules:** `fixthis-mcp/src/main/console/annotations.js`, `fixthis-mcp/src/main/console/main.js`, `fixthis-mcp/src/main/console/history.js`, `fixthis-mcp/src/main/console/prompt.js`

This document explains the implementation details behind quiet draft recovery.
It is a technical companion to the task plan, not a replacement for it.

## 1. Current Problem Shape

The current console treats every recovered draft item as user feedback, even
when the item is only a pin with an empty comment. That happens because the
recovery path uses raw item counts:

- `pendingRecoveryItems(recovery).length`
- `draftItemList().length`
- `historyRecoveryEnvelopeItems(recovery).length`

Those counts collapse two different states:

- **pin-only draft:** a selected UI target with no written feedback;
- **commented draft:** a selected target plus actionable feedback text.

The current behavior then escalates pin-only state into visible recovery UI and
session-boundary blocking. This is the source of the awkward UX: clicking a
target without writing a comment can later produce a warning-style
Recover / Recapture / Discard flow.

## 2. Desired State Model

The storage model does not change. Draft workspaces and legacy pending envelopes
can continue to contain mixed items. Only the interpretation changes.

Use one predicate as the source of truth:

```js
function hasWrittenAnnotationComment(item) {
  return Boolean(String(item?.comment || '').trim());
}
```

Build every new distinction from that predicate:

```js
function draftItemsFromValue(value) {
  if (Array.isArray(value)) return value;
  return Array.isArray(value?.items) ? value.items : [];
}

function commentedDraftItems(value) {
  return draftItemsFromValue(value).filter(hasWrittenAnnotationComment);
}

function pinOnlyDraftItems(value) {
  return draftItemsFromValue(value).filter(item => !hasWrittenAnnotationComment(item));
}

function draftRecoverySummary(value) {
  const items = draftItemsFromValue(value);
  const commented = commentedDraftItems(items);
  const pinOnly = pinOnlyDraftItems(items);
  return {
    total: items.length,
    commented: commented.length,
    pinOnly: pinOnly.length,
  };
}
```

The helpers intentionally accept either an array or an envelope. Call sites
currently pass both shapes: active draft paths often have arrays, while recovery
paths often have schema-v1 or schema-v2 envelopes.

## 3. Module Dependency Notes

The console bundle is built from `fixthis-mcp/src/main/console/*.js` by
`scripts/build-console-assets.mjs`. It topologically sorts modules by their
`// @requires` headers. If a module starts using helpers from `annotations.js`,
its header must say so.

Update these headers when implementing:

```js
// history.js
// @requires state.js, draftWorkspace.js, annotations.js

// prompt.js
// @requires state.js, draftWorkspace.js, draftUseCases.js, annotations.js
```

`main.js` already depends on `annotations.js`, so it can use the new helpers
without a header change.

After source edits, regenerate the served bundle:

```bash
FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
```

## 4. Recovery Banner Semantics

`renderPendingRecoveryBanner()` should stop using item count as its primary
visibility condition. Its visibility condition is:

```js
const summary = draftRecoverySummary(pendingRecovery);
if (!pendingRecovery || !summary.total || summary.commented === 0 || draftFlow() || draftItemList().length) {
  banner.hidden = true;
  return;
}
```

That means:

- no recovered draft: hidden;
- recovered pin-only draft: hidden;
- active draft already visible: hidden;
- recovered commented draft: show a low-friction resume notice.

The copy should avoid failure language. Use "Resume draft" for the primary
action and "Clear local draft" for destructive clearing. Keep "Recapture" only
for the existing case where preview context is missing or unusable.

Recommended visible text:

```text
1 draft comment · 2 pins without comments
Resume the local draft for this session.
```

The resume action still restores the full workspace. If a draft has one
commented item and two pin-only items, resuming should bring back all three
items so the user can continue editing the unfinished pins.

## 5. Boundary Semantics

There are two boundary situations, and they should not behave the same.

### Passive recovered draft

This is a draft found in local storage while no draft is currently active in the
composer. Passive recovery must not block:

- opening another session;
- creating a new session;
- entering Annotate;
- closing a session.

If the passive draft has commented items, the console may render the resume
notice, but it still returns `continue` from the boundary preflight. The user can
come back to the session and resume intentionally.

### Active draft in composer

This is a draft currently being edited. Existing protection still matters when
there is written feedback. However, pin-only-only active drafts should not force
a save/discard prompt. Store them as a local recovery workspace and continue.

The important branch in `resolvePendingBeforeBoundary()` is:

```js
const activeItems = draftWorkspaceItems(draftWorkspace);
const activeCommentedItems = commentedDraftItems(activeItems);
if (hasActivePending && activeCommentedItems.length === 0) {
  createBrowserDraftPorts().storage.saveWorkspace(draftWorkspaceRecoveryEnvelope(draftWorkspace));
  if (pendingSessionId) activePendingMirrorSessions.add(pendingSessionId);
  replaceDraftWorkspace(createEmptyDraftWorkspace());
  return 'continue';
}
```

Keep this before the existing `resolveDraftBoundary(...)` call. The existing
prompt flow remains responsible for active drafts with written comments.

## 6. History Display

History currently folds pending recovery into open/done counts. That should stay
stable enough for the existing strip and pips, but the human-readable row needs
more precise language.

Add a helper:

```js
function pendingHistorySummaryForSession(session) {
  const items = pendingHistoryItemsForSession(session);
  const summary = draftRecoverySummary(items);
  if (!summary.total) return '';
  const parts = [];
  if (summary.commented) parts.push(countLabel(summary.commented, 'draft comment', 'draft comments'));
  if (summary.pinOnly) parts.push(countLabel(summary.pinOnly, 'pin without comment', 'pins without comments'));
  return parts.join(' · ');
}
```

Render this as a subtle row line, not as a new card or warning. The point is to
make the state discoverable, not urgent.

CSS should be compact:

```css
.session-pending-summary {
  margin-top: 3px;
  color: var(--txt-1);
  font-size: 11px;
  line-height: 1.25;
  overflow-wrap: anywhere;
}
```

## 7. Handoff Behavior

The handoff path is where mixed drafts need the most care. Today,
`persistAndCollectItemIds()` rejects the whole draft if any item lacks a comment.
That is correct for "persist the entire draft", but wrong for "send actionable
feedback to an agent".

For Copy Prompt and Save to MCP, the written subset is persisted. Residual
pin-only handling differs by action:

1. Flush the focused comment input.
2. Require at least one commented item.
3. Persist only commented items with `persistPendingFeedbackItems({
   onlyWrittenComments: true,
   keepResidualDraftActive: ...,
   })`.
4. Preserve pin-only residual items locally only when the initiating action
   keeps residual drafts active.

`copyPrompt()` should keep residual pin-only items active, because the user stays
on the page and may continue editing. `sendAgentPrompt()` completes the handoff,
so residual pin-only items should be discarded after written items are persisted.
Keeping them as browser-local recovery makes the history row count server-saved
items plus a stale local draft.

Use an option on `persistAndCollectItemIds`:

```js
async function persistAndCollectItemIds(options = {}) {
  // ...
  await persistPendingFeedbackItems({
    onlyWrittenComments: true,
    keepResidualDraftActive: options.keepResidualDraftActive !== false,
  });
}
```

Then call it this way:

```js
// Copy Prompt
const itemIds = await persistAndCollectItemIds();

// Save to MCP
const itemIds = await persistAndCollectItemIds({ keepResidualDraftActive: false });
```

## 8. Residual Pin-Only Drafts

Do not save residual pin-only drafts after Save to MCP. `sendAgentPrompt()`
calls `resetComposer()` after creating the local handoff batch, and that action
is the user's explicit completion point for the draft.

When residual pin-only items are kept for Copy Prompt, create a fresh workspace
ID for them instead of reusing the just-persisted workspace ID:

```js
function saveResidualPinOnlyDraft(items, options = {}) {
  if (!draftWorkspace?.workspaceId || !items.length) return;
  const ports = createBrowserDraftPorts();
  const residualWorkspace = {
    ...draftWorkspace,
    workspaceId: ports.ids.nextWorkspaceId(),
    revision: 1,
    items,
    history: { undoStack: [], redoStack: [] },
  };
  ports.storage.saveWorkspace(draftWorkspaceRecoveryEnvelope(residualWorkspace));
  activePendingMirrorSessions.add(residualWorkspace.context?.sessionId);
  if (options.keepActive) {
    replaceDraftWorkspace(residualWorkspace);
    persistCurrentPendingState();
  }
  return residualWorkspace;
}
```

This keeps both flows coherent:

- Copy Prompt: residual pins remain active in the inspector.
- Save to MCP: residual pins are discarded after written items are saved and
  marked for agent handoff.

## 9. Test Strategy

The focused test file is `scripts/pendingItemRecovery-test.mjs`. It mostly uses
source-level contract checks, which matches the existing style in this area.

Add tests for:

- helper presence and source shape;
- passive recovery no longer blocking session changes or Annotate;
- banner visibility based on `summary.commented`;
- banner copy using "Resume draft" and split counts;
- handoff using `onlyWrittenComments: true`;
- Save to MCP passing `keepResidualDraftActive: false`;
- residual pin-only draft creation with a fresh workspace ID only when
  `keepResidualDraftActive` is true;
- no residual pin-only workspace is created after Save to MCP.

Run these during implementation:

```bash
npm run console:pending:test
npm run console:session:test
npm run console:draft:test
node scripts/build-console-assets.mjs --check
```

After rebuilding assets, run:

```bash
npm run console:test:fast
```

## 10. Non-Goals

Do not change storage schemas. Do not rename persisted MCP JSON fields. Do not
alter `.fixthis/feedback-sessions/` output. Do not change server-side session
models. This work is browser-console interpretation and presentation around
local draft state.

Do not make pin-only items eligible for agent handoff. Source hints without
written feedback are not actionable feedback.
