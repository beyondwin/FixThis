# FixThis UI/UX Polish - Detailed Spec

**Date:** 2026-05-14
**Status:** Ready for implementation planning
**Owners:** console UX / sample app / sidekick overlay
**Related:**
- `docs/superpowers/plans/2026-05-14-ui-ux-polish-implementation.md` - task-by-task implementation plan
- `docs/guides/feedback-console-tour.md` - user-facing console walkthrough
- `docs/reference/feedback-console-contract.md` - browser console behavior contract
- `docs/reference/output-schema.md` - persisted MCP compatibility fields

---

## Purpose

FixThis is intentionally split across two surfaces:

1. The Android target app shows only a debug-only sidekick status pill.
2. The desktop browser console owns preview, annotation, selection, history, and
   AI handoff.

That architecture keeps the Android runtime lightweight, but it also means the
browser console must explain the entire feedback workflow by itself. The current
console already exposes the main controls, but the path from "connect the app"
to "handoff saved feedback to an agent" is too implicit for first-time users.
This spec defines a focused UI/UX polish pass that improves orientation,
handoff confidence, inspector clarity, compact-screen reachability, preview
state awareness, sample app polish, and sidekick status wording.

The work is not a visual redesign. It is a product-readiness pass that makes the
existing Studio shell more explicit and more resilient without changing MCP
contracts or introducing a frontend framework.

## Background

The FixThis feedback workflow currently depends on users discovering the order
of operations from scattered UI controls:

- Start connection from the connection card.
- Navigate the app from the preview.
- Enter Annotate mode to freeze a screen.
- Click or drag to select a target.
- Type a comment and add an annotation.
- Use Copy Prompt or Save to MCP.
- Ask an agent to read and resolve the saved queue.

That workflow is documented in `AGENTS.md` and guide docs, but the console does
not present it as a visible sequence. Users can also see Copy Prompt and Save to
MCP before they know what makes those actions valid. Saved annotation details
contain useful source candidates and target evidence, but they are not organized
around the user's core question: "what did I ask, what UI element did I mean,
and what evidence will the agent receive?"

On narrow screens the history column is hidden, so a user who is working in a
compact browser width can lose access to saved sessions. The preview frame has
some stale and blocked indicators, but it does not consistently name whether
the user is looking at live device state, a frozen annotation frame, a stale
frame, or a saved historical frame.

The sample Compose app is useful for validation, but a few controls still feel
like implementation scaffolding rather than a polished app surface. The Android
sidekick pill also exposes "MCP browser" terminology even though the user-facing
product concept is the FixThis console.

## Problem Statement

The product failure mode is not that FixThis lacks capabilities. The failure
mode is that the capabilities are available but not narrated at the moment a
user needs them. The console asks users to infer workflow state, action
readiness, and preview trust from disabled buttons, implicit layout, and sparse
labels.

Specific issues:

1. There is no persistent workflow progress model for Connect, Preview,
   Annotate, and Handoff.
2. Copy Prompt and Save to MCP do not explain why they are disabled, what they
   will include, or whether the current selection is ready for agent work.
3. Inspector detail mixes user request, target identity, source candidates, and
   agent lifecycle status into one scan path.
4. History is hidden at compact widths instead of becoming reachable through a
   drawer or equivalent compact control.
5. Preview state is not consistently named as live, frozen, stale, saved, or
   unavailable.
6. The sample app still uses letter-only navigation and action glyphs in places
   where recognizable icons would reduce friction.
7. The debug overlay pill uses implementation wording that is less helpful to a
   product tester than console-oriented wording.

## Goals

- Make the primary workflow visible without forcing users to read docs.
- Make handoff readiness explicit before a user clicks Copy Prompt or Save to
  MCP.
- Make saved annotation detail scannable by separating Request, Target, and
  Evidence.
- Keep session history reachable at narrow browser widths.
- Name preview trust state in a way that prevents users from annotating the
  wrong screen.
- Polish the sample app enough that it is credible as a validation target.
- Change the Android sidekick copy to product language while keeping the
  overlay debug-only and lightweight.
- Preserve every persisted MCP compatibility field and every public tool
  contract.

## Non-Goals

- Do not redesign the console visual system from scratch.
- Do not replace vanilla JS/CSS with React, Vue, Svelte, or another framework.
- Do not add cloud sync, authentication, or external API calls.
- Do not change the MCP persisted JSON field names `items`, `screens`,
  `itemId`, `screenId`, `targetEvidence`, or `sourceCandidates`.
- Do not move annotation, selection, or handoff UI into the Android app.
- Do not support release builds, View-based Android apps, or Flutter targets.
- Do not change the bridge protocol unless a separate protocol spec covers it.

## Users And Jobs

### Product Tester

The product tester wants to leave specific UI feedback on a running debug app
without learning MCP terminology. They need the console to show what step comes
next, whether their feedback is saved, and whether the agent will receive enough
context.

### Coding Agent

The coding agent consumes compact Markdown or the local MCP queue. It needs
saved feedback to contain a clear user request, a stable target description,
source candidates, screen context, and lifecycle state.

### Project Maintainer

The maintainer validates FixThis in the sample app and checks regressions. They
need the sample surface and sidekick overlay to look deliberate enough that UI
captures represent real product usage, not rough internal tooling.

## UX Principles

- Show workflow state near the controls that advance the workflow.
- Explain disabled actions with state, not with documentation links.
- Keep the console dense and operational. This is a workbench, not a landing
  page.
- Prefer explicit state labels over color-only status.
- Treat saved history as core navigation, including on compact screens.
- Use local-first language. Save to MCP is local persistence, not cloud sync.
- Keep Android in-app UI minimal: connection status only.

## Priority Map

| ID | Priority | Area | Requirement |
|----|----------|------|-------------|
| UXP-1 | P0 | Console workflow | Show Connect, Preview, Annotate, Handoff progress |
| UXP-2 | P0 | Handoff readiness | Explain Copy Prompt and Save to MCP readiness |
| UXP-3 | P0 | Inspector detail | Separate Request, Target, and Evidence |
| UXP-4 | P1 | Compact history | Keep history reachable below desktop widths |
| UXP-5 | P1 | Preview state | Label live, frozen, stale, saved, and unavailable frames |
| UXP-6 | P2 | Sample app | Replace letter-only navigation/action glyphs with icons |
| UXP-7 | P2 | Sidekick overlay | Use console-oriented product wording |
| UXP-8 | P2 | Docs | Update guides and contracts to match UI behavior |

---

## UXP-1 - Console Workflow Progress

### Current Behavior

The console has a connection card, toolbar buttons, inspector actions, and
handoff buttons. Those controls imply a sequence, but there is no visible
workflow model. A first-time user can see Annotate, Copy Prompt, and Save to MCP
before they understand what has to happen first.

### Desired Behavior

The console shows a compact workflow progress component with four steps:

1. Connect
2. Preview
3. Annotate
4. Handoff

The component should stay visible near the top of the console, below the
connection status context and before the main work area. It should not compete
with the canvas. It should orient the user and reflect the current state.

### State Model

| Step | Active When | Complete When |
|------|-------------|---------------|
| Connect | No active session or device is not connected | A session is active and the app can be previewed |
| Preview | Connected and not currently annotating | A preview screen is available |
| Annotate | Annotate mode or frozen add-items flow is active | At least one draft or saved annotation exists |
| Handoff | There are handoff-ready annotations or a handoff action is in flight | Items are copied or saved to MCP |

When multiple conditions are true, the most advanced active step wins unless an
in-flight operation requires a temporary state. For example, saving to MCP makes
Handoff active while it is in flight.

### Requirements

- The component must be semantic navigation with `aria-label="FixThis feedback workflow"`.
- Each step must expose a stable `data-workflow-step` value:
  - `connect`
  - `preview`
  - `annotate`
  - `handoff`
- Each step must expose a stable `data-state` value:
  - `complete`
  - `active`
  - `upcoming`
- The component must fit at 390px viewport width without horizontal scrolling.
- Labels must use short product copy:
  - Connect: "Start the debug app"
  - Preview: "Navigate the screen"
  - Annotate: "Pin UI feedback"
  - Handoff: "Copy or save"
- The progress UI must update when connection state, preview state, tool mode,
  pending items, saved items, or handoff in-flight state changes.
- It must not depend on new persisted state.

### Acceptance Criteria

- A new session with no preview marks Connect active.
- A connected session with live preview marks Preview active.
- Entering Annotate mode marks Annotate active.
- Adding one annotation marks Annotate complete and Handoff active.
- Starting Copy Prompt or Save to MCP keeps Handoff active until the operation
  finishes.
- Playwright smoke can assert the active step after preview load and after
  clicking Annotate.

---

## UXP-2 - Prompt Readiness Summary

### Current Behavior

Copy Prompt and Save to MCP are disabled until `currentPromptAnnotations()` has
items. The UI does not explain that condition, does not show how many
annotations will be included, and does not distinguish draft-only, saved,
modified-after-sent, or in-flight states in the top-level action area.

### Desired Behavior

The console displays a prompt readiness summary near Copy Prompt and Save to
MCP. The summary tells the user whether handoff is blocked, ready, already sent,
or needs re-save after edits.

### Required States

| State | Trigger | User Copy |
|-------|---------|-----------|
| Empty | No current prompt annotations | "Add an annotation to prepare an agent handoff." |
| Draft Ready | Pending annotations exist before Save to MCP | "Ready to hand off N draft annotation(s)." |
| Saved Ready | Saved annotations exist and none are in progress | "N saved annotation(s) ready for Copy Prompt or MCP." |
| Sent | Items have been handed off and not modified | "Saved to MCP. Agent can read this queue." |
| Modified | A sent item has local edits not re-saved | "Edits changed after handoff. Re-save before agent work." |
| In Flight | Copy Prompt or Save to MCP is running | "Preparing handoff..." |
| Partial Failure | Clipboard copy succeeds but MCP mark fails, or save partially fails | "Handoff needs attention. Review status before continuing." |

### Requirements

- The readiness summary must be visible even when both handoff buttons are
  disabled.
- The summary must include the number of annotations that would be included.
- It must distinguish Copy Prompt and Save to MCP only where behavior differs:
  Copy Prompt puts Markdown on the clipboard, Save to MCP writes a local handoff.
- It must not claim cloud upload or remote sync.
- It must update after add, edit, delete, re-save, copy, save, claim, resolve,
  and session switch.
- It must handle singular and plural annotation counts correctly.
- It must be announced politely through screen reader accessible text when the
  state changes.

### Acceptance Criteria

- With zero annotations, both buttons are disabled and the summary explains the
  blocker.
- With two pending annotations, both buttons are enabled and the summary says
  two draft annotations are ready.
- After Save to MCP succeeds, the summary says the local queue is available to
  the agent.
- After editing a sent item, the summary warns that re-save is needed.
- During Save to MCP, the summary changes to in-flight copy and buttons cannot
  be clicked twice.

---

## UXP-3 - Inspector Detail Hierarchy

### Current Behavior

Saved annotation detail exposes the important information, but the scan order is
not optimized for a user or agent reviewer. Request text, target label, bounds,
source candidates, evidence, and lifecycle messages appear as one long detail
area.

### Desired Behavior

Saved annotation detail is organized around three sections:

1. Request
2. Target
3. Evidence

Agent lifecycle status remains visible above these sections because it answers
whether the item is draft, sent, in progress, needs clarification, will not be
fixed, or resolved.

### Section Requirements

#### Request

- Show the user comment exactly as stored, with wrapping.
- Show item number, status badge, and edited-after-sent state.
- Show the screen/session timestamp where available.
- Keep Re-save available when local edits exist.

#### Target

- Show human-readable target label.
- Show role, text, content description, test tag, and bounds when available.
- Show whether the target came from a semantics node or a drawn visual region.
- Use labels that help an agent choose source files.

#### Evidence

- Show target evidence and source candidates.
- Group candidate details by confidence and source path where available.
- Keep persisted field names stable in JSON but use product labels in UI.
- Wrap long package names, paths, and node descriptions without horizontal
  overflow.

### Requirements

- The inspector section headings must be visible text, not only CSS styling.
- Keyboard focus must remain inside the inspector detail when opening and
  closing a saved item detail.
- The "All annotations" back action must remain available.
- Long comments, file paths, package names, and bounds must wrap inside the
  inspector at 390px.
- The empty inspector state must still invite the user to use Annotate.
- The pending annotation composer must remain simple and must not show all saved
  evidence fields before the user saves.

### Acceptance Criteria

- Selecting a saved item renders Request, Target, and Evidence headings in that
  order.
- A source candidate path longer than 120 characters wraps without widening the
  document.
- A visual-region annotation without a semantics node still shows a useful
  Target section with bounds and "Visual region" wording.
- An item with no source candidates shows an empty Evidence state that explains
  what is missing without implying data loss.

---

## UXP-4 - Compact History Reachability

### Current Behavior

At compact widths the `.studio-history` column is hidden. This protects the
canvas from overflow, but it also makes saved sessions unreachable from the
console UI.

### Desired Behavior

History remains available through a compact control. The preferred interaction
is a History button in the canvas toolbar that opens a drawer or popover
containing the same session rows.

### Requirements

- History must remain visible as a left column on desktop layouts.
- Below the compact breakpoint, the history column can be off-canvas but must be
  reachable through a button labelled "History".
- The compact history surface must reuse existing session row rendering and
  delete behavior.
- Selecting a session from compact history must close the compact surface.
- Escape must close the compact surface.
- Clicking outside the drawer or popover must close it.
- Focus must move to the compact history surface when opened and return to the
  History button when closed.
- The compact surface must not cover the Save to MCP and Copy Prompt buttons
  permanently; users must be able to dismiss it easily.

### Acceptance Criteria

- At 390px viewport width, the user can open History, select a saved session,
  and close History without horizontal scrolling.
- At desktop width, the existing history column remains present and the compact
  History button is hidden or visually secondary.
- Deleting a history item behaves the same in desktop and compact layouts.
- Responsive stress tests assert that history is reachable at compact width.

---

## UXP-5 - Preview Frame State Badge

### Current Behavior

The canvas can show a live preview, a frozen frame during annotation, saved
historical screens, stale notices, blocked overlays, and no-screenshot empty
states. These states are spread across several visual treatments and copy
strings.

### Desired Behavior

The preview frame exposes a small state badge that names the current trust state
of the frame.

### Required States

| State | Trigger | Badge Copy |
|-------|---------|------------|
| Live | `state.preview` is present and no add-items flow is active | "Live preview" |
| Frozen | Annotate add-items flow is active | "Frozen for annotation" |
| Stale | Current preview or frozen frame is marked stale | "Stale frame" |
| Saved | Viewing a persisted screen from history | "Saved screen" |
| Unavailable | No screenshot artifact exists | "No screenshot" |
| Blocked | Interaction is blocked by connection or activity state | "Interaction blocked" |

### Requirements

- The badge must sit inside or adjacent to the preview frame without covering
  annotation pins.
- Stale and blocked states take precedence over live/frozen/saved labels.
- The existing stale frame notice remains available when the user can refresh or
  use the latest frame.
- The badge must be text visible to assistive technology.
- The badge must not change screenshot coordinates or overlay math.
- The badge must fit at 390px and desktop widths.

### Acceptance Criteria

- Live preview shows "Live preview".
- Entering Annotate mode freezes the frame and shows "Frozen for annotation".
- A stale frozen frame shows "Stale frame" until refreshed or replaced.
- Selecting an older saved annotation shows "Saved screen".
- No screenshot state shows "No screenshot" without creating an empty badge that
  floats over the empty stage.

---

## UXP-6 - Sample App Navigation And Action Polish

### Current Behavior

The sample app uses Material3 navigation, but tab icons are currently represented
by letter-like text glyphs. Some card actions also use text-only symbols. This
works for semantics inspection but does not feel like a realistic validation
target.

### Desired Behavior

The sample app should use recognizable Material icons for bottom navigation and
card actions while keeping existing labels and testable semantics stable.

### Requirements

- Use Material icons from the Compose ecosystem already compatible with the
  project.
- Keep bottom navigation labels unchanged:
  - Home
  - Queue
  - Project
  - Review
  - Diagnostics
- Keep each tab selectable through Compose semantics.
- Replace the card save action symbol with a recognizable save icon.
- Preserve or improve content descriptions, such as `Save FX-101`.
- Do not redesign sample screens or change demo data.
- Do not add network-loaded image assets.

### Acceptance Criteria

- Compose smoke tests can find each bottom navigation item by label.
- The save action exposes a stable content description.
- The sample app still builds and runs as a debug validation target.
- The visual polish does not change FixThis capture or source-index behavior.

---

## UXP-7 - Android Sidekick Overlay Copy

### Current Behavior

The sidekick status pill uses strings such as "FixThis MCP browser connected"
and "FixThis MCP browser waiting". This is accurate internally but not the most
useful language for a tester who is using the FixThis console.

### Desired Behavior

The sidekick pill should use product-facing console language:

- Connected: "FixThis console connected"
- Waiting: "FixThis console waiting"

The overlay remains debug-only and continues to expose only connection status.

### Requirements

- Change visible text and content description together.
- Keep the host title and internal tags stable unless tests require a text-only
  assertion update.
- Keep connected and waiting visual states unchanged.
- Do not add annotation controls to the Android overlay.
- Do not change release behavior.

### Acceptance Criteria

- Existing overlay host tests pass with updated expected strings.
- A connected bridge state displays "FixThis console connected".
- A waiting bridge state displays "FixThis console waiting".
- The overlay remains excluded from Compose root inspection through existing
  overlay-host filtering.

---

## UXP-8 - Documentation Updates

### Current Behavior

Docs describe the feedback workflow, but they will not mention the new workflow
progress, readiness summary, compact history control, or preview frame state
badge until updated.

### Desired Behavior

Docs match the UI so agents and users do not have to translate old terminology.

### Requirements

- Update the feedback console tour with the visible workflow steps.
- Explain that Save to MCP is local persistence.
- Explain Copy Prompt as the compact Markdown path for chat-style agents.
- Document that compact layouts expose History through a compact control.
- Document preview state labels and what action a user should take for stale or
  blocked frames.
- Update the console contract only for behavioral guarantees, not for private
  CSS implementation details.

### Acceptance Criteria

- Guide docs mention Connect, Preview, Annotate, and Handoff in the same order
  as the UI.
- Reference docs mention local MCP persistence and compact history reachability.
- No docs claim that data is uploaded to a cloud service.

---

## Cross-Cutting Accessibility Requirements

- Every new interactive control must have an accessible name.
- Status changes must use polite announcements unless the state blocks user
  action and needs immediate attention.
- Badge color must not be the only source of meaning.
- Drawer or popover interactions must support Escape.
- Keyboard users must be able to open compact history, choose a session, close
  the surface, and return to the canvas toolbar.
- Long user-generated text must wrap with no horizontal document overflow at
  390px.

## Responsive Requirements

- The console must avoid horizontal document overflow at 390px, 768px, 900px,
  1024px, and 1280px.
- Workflow progress may wrap to two rows at compact widths.
- Prompt readiness summary may stack below handoff buttons at compact widths.
- History may move from a column to a drawer or popover at compact widths.
- Inspector detail sections must stack vertically and wrap long evidence.
- Preview badge must stay within the frame or canvas bounds.

## Data And Compatibility Requirements

- No persisted MCP field names may be renamed.
- No new required field may be added to existing queue JSON.
- New UI state should be derived from existing session, preview, pending item,
  saved item, and lifecycle state wherever possible.
- If a new in-memory helper is needed, it must live in console JS state only and
  must not become a compatibility contract.
- Generated `fixthis-mcp/src/main/resources/console/app.js` must be produced by
  `node scripts/build-console-assets.mjs`.

## Verification Requirements

Implementation must verify the spec with:

```bash
node scripts/build-console-assets.mjs --check
npm run console:smoke
npm run console:responsive:stress
./gradlew :fixthis-mcp:test
./gradlew :fixthis-compose-sidekick:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

If no Android device or emulator is available, the connected test result must be
reported as not run with the device reason. The implementation plan may also
include narrower task-level tests before the full verification pass.

## Release Readiness Checklist

- Console workflow progress is visible and stateful.
- Handoff readiness summary explains disabled and ready states.
- Inspector saved detail has Request, Target, and Evidence sections.
- History is reachable at compact widths.
- Preview frame state badge is present and does not cover pins.
- Sample app uses recognizable icons while preserving labels.
- Sidekick overlay copy says FixThis console, not MCP browser.
- Docs match the new UI language.
- Generated console bundle is current.
- Compatibility fields remain unchanged.
