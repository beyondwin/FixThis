# Changelog

## Unreleased

### Fixed

- Removed a disruptive browser alert dialog that interrupted the workflow when copying the prompt; copying now works silently without a popup.
- "Copy Prompt" and "Send Agent" buttons are now correctly disabled while a request is in progress, preventing duplicate submissions.
- Switching focus between annotation entries no longer discards in-progress comment text; any pending comment is saved before the focus change takes effect.
- Annotation comment text entered in an input field is now persisted when the field loses focus, so drafts are no longer silently dropped.
- Status bar correctly clears a previous success indicator when a new error message is shown.
- Repeated heartbeat/bridge connectivity errors are now suppressed in the status bar; only the first occurrence of an identical error is shown until the next successful heartbeat resets the dedup state, eliminating noisy repeated alerts when the bridge is offline.
- Removed a duplicate static `#selectionOverlay` element from the preview region; the overlay is now created and managed exclusively by the JS preview frame, preventing rare double-overlay rendering and stale DOM state.
- Empty preview stage now shows context-appropriate guidance instead of a single generic message: "Connect a device to get started." when no session is active, "Waiting for first capture from device…" when connected without a screenshot yet, and "No screenshot artifact for this preview." when a screen exists but its image is missing.

- Fixed annotation screen mismatch in the feedback console: after saving annotations on one screen and navigating the device to another, the preview now shows the live device screen instead of staying stuck on the previous screenshot. Clicking a saved annotation swaps the preview to that annotation's saved screenshot and shows only that screen's annotation pins; deselecting returns to the live preview. Cross-screen pin overlap (screen 1 pins appearing on a screen 2 preview) is also eliminated.

### Added

- "Copy Prompt" button now briefly displays a "Copied ✓" confirmation after copying to the clipboard, giving clear visual feedback.
- "Send Agent" action now shows a success status message in the console status bar after the request completes successfully.
- Severity and status segmented buttons in both pending and saved annotation panels now expose `aria-pressed` state, so screen readers correctly announce which option is currently selected.
- Keyboard-only users now see a clear focus ring (2px accent-color outline) on tool, zoom, annotation back, and segmented buttons; the ring uses `:focus-visible` so it does not appear on mouse clicks.

- SourceMatcher confidence is now margin- and evidence-aware. HIGH is reserved for strong evidence with a clear top-vs-next margin. Visual-area, text-only, nearby-only, activity-only, arbitrary-literal, and legacy-fallback matches carry explicit risk caps and caution text.
- SourceCandidate gains optional ranking, scoreMargin, evidenceStrength, riskFlags, and caution metadata. Older persisted sessions remain compatible.
- The console "Copy Prompt" / "Send Agent" output and DetailMode.COMPACT Markdown switch to a compact `src? file:line confidence; why=tokens; risk=token` shape with a single top-level verification rule and screen-level screenshot context. PRECISE/FULL output is unchanged.
- Same-screen annotation targets that overlap (visual-area intersection, IoSA >= 0.25, or weak-label center distance <= 24dp at default density) are split into explicit overlap groups in compact handoff.
- Added project stabilization planning and documentation for the console contract, CI, onboarding, MCP maintainability, evidence quality, local security, and release readiness.
- Added CI-oriented verification scripts, contributor guidance, zero-setup MCP configuration helpers, and connected smoke diagnostics for local development.
- Improved MCP feedback console maintainability through route, session service, and JavaScript module splits while preserving existing MCP tool and console behavior.
- Added Stable Target Evidence/source-index signals and sample coverage fixtures for repeatable evidence quality checks.
- Hardened local console mutation endpoints with browser token and origin checks, and added cleanup support for known local `.fixthis` artifacts.
- Documented current release blockers, including the missing root `LICENSE` and unpublished external artifact coordinates.

### Changed

- Compact feedback handoff prompt v2 (clarity, multi-candidate, instance disambiguation) — see docs/superpowers/specs/2026-05-09-compact-handoff-prompt-v2-design.md
