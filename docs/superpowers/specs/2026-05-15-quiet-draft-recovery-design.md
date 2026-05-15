# Quiet Draft Recovery Design

## Goal

FixThis should not treat commentless annotation pins as an error recovery case.
The console should preserve them locally, but only written draft feedback should
interrupt or guide the user.

The current recovery banner appears whenever a recovered draft has any item. That
mixes two different states:

- a pin-only draft, where the user selected a target but did not write feedback;
- a commented draft, where the user left actionable feedback that should not be
  lost.

This makes normal session navigation and Annotate entry feel blocked by an
error, especially after exploratory clicks.

## Product Behavior

Draft recovery is renamed conceptually from "recover" to "resume draft". The
implementation can keep existing storage names, but UI copy should describe
unfinished local draft work rather than failure recovery.

Items are classified by comment state:

- **Commented draft item:** `comment.trim()` is non-empty. This is actionable
  user feedback.
- **Pin-only draft item:** `comment.trim()` is empty. This is local target
  metadata only.

Pin-only drafts remain stored in the browser-local draft workspace so the user
can return to them. They do not trigger the recovery banner, do not block session
changes, and do not block entering Annotate.

Commented drafts remain visible and resumable. When a recovered workspace has at
least one commented item, the console shows a low-friction "Resume draft"
notice for that session. The notice should include split counts when mixed
drafts exist, for example:

`1 draft comment · 2 pins without comments`

The UI should avoid warning language for pin-only state. "Unsaved annotations
found", "Recover", and "Discard before continuing" are reserved for stronger
states, not a pin-only draft.

## Architecture

Add small draft classification helpers near the existing pending recovery and
annotation helpers:

- `hasWrittenAnnotationComment(item)` remains the source of truth for written
  feedback.
- `commentedDraftItems(items)` returns only items with written comments.
- `pinOnlyDraftItems(items)` returns items without written comments.
- `draftRecoverySummary(recovery)` returns `{ total, commented, pinOnly }`.

Existing storage remains unchanged. The browser-local draft workspace can still
contain both item types. This keeps compatibility with schema-v2 workspaces and
legacy schema-v1 pending envelopes.

The behavioral change is in consumers:

- pending recovery banner checks `summary.commented > 0`, not `items.length`;
- passive recovered drafts do not block session changes or Annotate entry;
- active draft editing keeps the existing save/discard protection for written
  feedback, but pin-only items do not escalate that protection;
- history display separates commented draft count from pin-only count;
- prompt and handoff code persists/sends written draft items only.

## Data Flow

On reload or session reattach:

1. The console loads the newest local draft recovery for the selected session.
2. The recovery is summarized into commented and pin-only counts.
3. If there are no commented items, the console does not show the recovery
   banner and does not block navigation.
4. If there are commented items, the console shows a resume-oriented notice with
   separated counts. The notice does not block session navigation by itself.
5. If the user resumes the draft, the whole workspace is restored so pin-only
   targets remain available next to commented items.

On Copy Prompt or Save to MCP:

1. The console flushes the focused comment input.
2. Only commented items are included in the persist/send path.
3. Copy Prompt keeps pin-only residual items browser-local unless the user later
   adds comments or explicitly clears the draft.
4. Save to MCP discards pin-only residual items after the handoff batch is
   created, so history does not count saved items plus a stale local recovery
   draft.

## Error Handling

If a recovered workspace has corrupted or missing preview context, the console
keeps the current recapture path for commented items. Pin-only-only recovery
still stays quiet because there is no actionable feedback to protect.

If a mixed draft is resumed and some pin-only items cannot be mapped to the
current frozen preview, the console should preserve the commented items first and
surface the existing recapture/mismatch messaging only for the resumed editing
flow.

## Testing

Focused test coverage should live in `scripts/pendingItemRecovery-test.mjs` and
nearby console contract tests:

- pin-only recovery does not render the recovery banner;
- pin-only recovery does not block session switching;
- pin-only recovery does not block entering Annotate;
- mixed recovery renders split counts for commented and pin-only items;
- Save to MCP / Copy Prompt persist and hand off commented items without failing
  only because pin-only items exist.

Run the focused console tests and generated asset check after implementation:

```bash
npm run console:pending:test
node scripts/build-console-assets.mjs --check
```
