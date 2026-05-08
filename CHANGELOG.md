# Changelog

## Unreleased

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
