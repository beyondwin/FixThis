---
name: fixthis-android-evidence
description: Attach scoped Android runtime evidence to a FixThis feedback item.
---

# FixThis Android Evidence

Use when a user asks to attach logs, frame evidence, memory evidence, or runtime proof to FixThis feedback.

Rules:
- Runtime evidence is local-first, debug-only, and Jetpack Compose-only.
- Do not commit `.fixthis/`.
- Prefer summaries and local artifact paths over raw logs.
- Non-strict missing Android prerequisites are deferred with exact reasons; strict runs fail.
- Use `docs/reference/mcp-tools.md` for `fixthis_capture_runtime_evidence` arguments and return shapes.

Workflow:
1. Identify the feedback `sessionId` and `itemId`.
2. Capture the smallest useful evidence type: `logcat_window`, `frame_summary`, `memory_summary`, or `trace_artifact`.
3. Call `fixthis_capture_runtime_evidence` with a bounded summary.
4. Re-read the handoff and confirm compact Markdown shows `runtimeEvidence`.
