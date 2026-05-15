# Spec - Console Harness Blocked Scenarios

Status: Draft
Date: 2026-05-15
Scope: feedback console browser harness, fake bridge fixture, DOM test contracts, reconnect and slow handoff UX verification
Related implementation plan: [`../plans/2026-05-15-console-harness-blocked-scenarios-implementation.md`](../plans/2026-05-15-console-harness-blocked-scenarios-implementation.md)

## Summary

`scripts/console-harness.mjs` has a scenario matrix for happy path,
multi-tab, network outage, and slow handoff. `happy-path` and `multi-tab` are
active. `network-outage` and `slow-handoff` are marked
`@blocked-pending-impl`, so the nightly harness reports them as skipped.

The skip is not a product feature gap by itself. It means the harness cannot
reliably observe the reconnect, pending recovery, and in-flight handoff states
through stable DOM contracts. This spec turns those blocked scenarios into
first-class tests by adding stable `data-testid` attributes, strengthening the
fake bridge fixture, and asserting the user-visible recovery states end to end.

## Goals

- Remove `@blocked-pending-impl` from `network-outage`.
- Remove `@blocked-pending-impl` from `slow-handoff`.
- Add stable DOM test IDs for:
  - reconnect banner or connection reconnect state
  - pending recovery banner
  - Save to MCP button
  - Copy Prompt button
  - handoff in-flight readiness/status
- Make fake bridge scenario endpoints match the console's real endpoint names.
- Verify network outage preserves unsaved draft work and surfaces reconnect UI.
- Verify slow handoff disables duplicate actions and returns to a consistent
  state after the delayed request finishes.
- Keep skip accounting for future intentionally blocked scenarios, but this
  pair must run under `--fail-on-skips`.

## Non-Goals

- No visual redesign of the console.
- No change to persisted session JSON.
- No change to MCP tool contracts.
- No new Android instrumentation requirement.
- No conversion from the existing harness to Playwright Test runner.

## Current State

`scripts/console-harness.mjs` defines:

```js
const SCENARIOS = {
  'happy-path': { apply: null, requiredViewports: Object.keys(VIEWPORTS) },
  'network-outage': { apply: applyNetworkOutage, requiredViewports: Object.keys(VIEWPORTS), status: '@blocked-pending-impl' },
  'slow-handoff': { apply: applySlowHandoff, requiredViewports: Object.keys(VIEWPORTS), status: '@blocked-pending-impl' },
  'multi-tab': { apply: applyMultiTab, requiredViewports: ['breakpoint-900', 'compact-1024', 'desktop-1280'] },
};
```

The blocked scenario branches currently wait for selectors that do not exist
as stable contracts in the bundle:

- `[data-testid="reconnect-banner"], .pending-recovery`
- `[data-testid="send-button"], button.send`

The real console already has stable element IDs for many controls, including:

- `connectionCard`
- `sendAgentButton`
- `copyPromptButton`
- `pendingRecoveryBanner` created dynamically in `main.js`
- `promptReadiness`
- `error`

The harness should use explicit `data-testid` attributes rather than depending
on CSS class names or button text.

## DOM Contract

Add these attributes:

| Element | Attribute | Owner |
| --- | --- | --- |
| `#connectionCard` | `data-testid="connection-card"` | `index.html` |
| `#connectionCard` | `data-reconnect-visible="true|false"` | `connection.js` |
| `#sendAgentButton` | `data-testid="save-to-mcp-button"` | `index.html` |
| `#copyPromptButton` | `data-testid="copy-prompt-button"` | `index.html` |
| `#promptReadiness` | `data-testid="prompt-readiness"` | `index.html` |
| `#pendingRecoveryBanner` | `data-testid="pending-recovery-banner"` | `main.js` |
| `#error` | `data-testid="global-status"` | `index.html` |

`data-reconnect-visible` is true when the connection view state is
`reconnect`, or when session polling has paused due to repeated failures.

## Fake Bridge Contract

The fake bridge must respond to the actual browser requests that the bundled
console makes:

- `GET /api/session`
- `GET /api/sessions`
- `GET /api/connection`
- `GET /api/devices`
- `GET /api/preview`
- `POST /api/items/batch`
- `POST /api/agent-handoffs`
- `POST /api/sessions/:sessionId/handoff-preview`
- `POST /api/mark-handed-off` if the current bundle calls it through an
  adapter

The fixture must maintain a minimal mutable session with one selectable preview
and one draft-capable target. It does not need to implement the full Kotlin
server, but the JSON shape must include fields read by the console selectors
and renderers.

## Network Outage Scenario

Setup:

1. Load the console.
2. Fixture starts in `happy-path`.
3. Harness creates one draft annotation through the UI.
4. Fixture switches to `network-outage` for session polling and handoff
   endpoints.

Expected behavior:

- The console surfaces reconnect or paused update UI through
  `[data-testid="connection-card"][data-reconnect-visible="true"]`.
- The draft annotation remains visible.
- `Save to MCP` is disabled or produces a recoverable error; it must not clear
  the draft.
- When fixture restores `happy-path`, the connection state clears and the draft
  can still be saved.

## Slow Handoff Scenario

Setup:

1. Load the console.
2. Fixture starts in `happy-path`.
3. Harness creates one draft annotation with a comment.
4. Fixture switches to `slow-handoff` with a 1500 ms delay for handoff endpoint.
5. Harness clicks `Save to MCP`.

Expected behavior:

- `Save to MCP` and `Copy Prompt` become disabled while request is in flight.
- Prompt readiness or button title exposes a "Preparing handoff" or in-flight
  state.
- A second click does not create a second handoff request.
- After the delayed response resolves, buttons settle into a consistent state
  and no console errors are logged.

## Acceptance Criteria

- This command exits 0 and reports no skips:

```bash
node scripts/console-harness.mjs --matrix network-outage,slow-handoff --viewport all --fail-on-skips
```

- `npm run console:harness:test` covers scenario selection, skip policy, and
  fixture routes.
- `npm run console:test:all` still passes.
- `npm run console:responsive:stress` still passes when DOM contract changes
  alter any visible connection, prompt, or inspector element.
- `node scripts/build-console-assets.mjs --check` passes after bundle
  regeneration.
- `CHANGELOG.md` no longer says these two scenarios are deferred once the
  implementation lands.

## Risks

| Risk | Mitigation |
| --- | --- |
| Harness creates brittle UI flows | Use `data-testid` and state attributes only for stable contracts. |
| Fake bridge drifts from Kotlin route shapes | Keep fixture JSON minimal but named after real endpoints and fields consumed by selectors. |
| Slow handoff test is flaky | Use 1500 ms delay and explicit request-count assertions instead of arbitrary long sleeps. |
| Network outage hides real console errors | Capture browser console errors and fail the cell unless the error text is an expected fetch failure displayed through UI. |
