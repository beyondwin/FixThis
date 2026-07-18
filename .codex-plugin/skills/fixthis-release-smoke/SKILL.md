---
name: fixthis-release-smoke
description: Run FixThis release and trust-loop smoke checks.
---

# FixThis Release Smoke

Use only from a FixThis source checkout when a maintainer asks to validate release readiness or public release reality.

Rules:
- FixThis remains debug-only and Jetpack Compose-only.
- Do not commit `.fixthis/`.
- Separate repository checks, connected Android proof, and live registry reality.
- DEFERRED and SKIPPED are not PASS.
- Do not tag, publish, push, or edit a downstream repository unless explicitly requested.
- Use docs/contributing/release-readiness.md as the maintained release contract.

Workflow:
1. Run npm run release:reality and record live/public evidence separately.
2. Run npm run evidence:release for the repository evidence profile.
3. Run npm run android:proof -- --strict --continue when connected proof is required.
4. Run npm run release:check before a release PR or tag.
5. Report PASS, FAIL, DEFERRED, and SKIPPED separately with reasons and residual risk.
