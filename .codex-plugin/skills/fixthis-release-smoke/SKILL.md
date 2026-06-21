---
name: fixthis-release-smoke
description: Run FixThis release and trust-loop smoke checks.
---

# FixThis Release Smoke

Use when a maintainer asks to validate FixThis release readiness or trust-loop evidence.

Rules:
- FixThis is debug-only and Jetpack Compose-only.
- Do not commit `.fixthis/`.
- Separate strict connected Android proof from non-strict deferred evidence.
- Do not claim connected runtime proof passed unless the command actually passed.
- Use `docs/contributing/release-readiness.md` for the canonical release checklist.

Workflow:
1. Run `npm run evidence:trust`.
2. Run `npm run runtime-evidence:smoke`.
3. For strict local release proof, run `npm run runtime-evidence:smoke -- --strict` and the existing strict trust commands required by `docs/contributing/release-readiness.md`.
4. Summarize pass, deferred, and fail results separately.
