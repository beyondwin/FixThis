# Project Cleanup Pass — Design

**Date:** 2026-05-10
**Status:** Approved for execution
**Owners:** repo housekeeping
**Related:**
- `docs/superpowers/plans/2026-05-10-project-cleanup-pass-implementation.md` — step-by-step execution plan

---

## Purpose

A project audit on 2026-05-10 found ~1.1 GB of reclaimable disk plus several stale or zombie tracked artifacts that confuse first-time readers. This pass cleans them in one coordinated change so the working tree, the `.fixthis/` workspace, and the docs tree all reflect current reality.

The pass is intentionally **defensive**: only files with zero live references or with explicit "regenerable" semantics are deleted. ADRs and historical design reviews are kept (with status edits) so the decision trail isn't erased.

## Audit Findings

### Tracked artifacts that should be deleted

| Item | Size | Why |
| --- | --- | --- |
| `fixthis-compose-overlay/` | 57 MB | Module already retired. `settings.gradle.kts` no longer includes it; only an orphan `build/` directory remains. Module-rename history: see `docs/superpowers/plans/2026-05-07-fixthis-full-rename-implementation.md`. The dir is gitignored, so the size doesn't bloat git history, but the path on disk is misleading. |
| `./.DS_Store` (root) | 6 KB | macOS Finder metadata; gitignored, but lingering on disk. |

### Tracked artifacts that should change status, not be deleted

| Item | Action | Why |
| --- | --- | --- |
| `docs/adr/0005-overlay-mode-state-machine.md` | Edit status from `Accepted` → `Superseded — overlay module retired` | The overlay module is gone, so the state machine described in this ADR no longer governs running code. Keep the file: ADRs document the decision trail and removing them rewrites history. |
| `docs/design-target-evidence-handoff-review.md` (44 KB) | Move to `docs/superpowers/specs/2026-05-07-target-evidence-handoff-review.md` (preserve git history via `git mv`) | Historical sibling-review doc tied to `docs/superpowers/plans/2026-05-07-stable-target-evidence-v1-implementation.md`. Header explicitly says "PointPatch 시절 작성된" plan + "main 병합 후 구현 보정". Already implemented. Belongs in superpowers/specs/ alongside its plan; doesn't belong at top-level `docs/` where it can be mistaken for living guidance. |

### Regenerable disk reclaim (no source change)

| Item | Reclaim | How |
| --- | --- | --- |
| All `*/build/` (gradle output) | ~168 MB | `./gradlew clean` |
| Old `.fixthis/feedback-sessions/` (85 sessions, mostly closed) | up to ~900 MB | `fixthis clean --older-than-days 7` (preserves `project.json`; symlink-safe; supports `--dry-run`) |

### Items intentionally **not** touched

- `node_modules/` — required by `scripts/console-browser-smoke.mjs` (Playwright). Gitignored.
- `.orchestrator/`, `.playwright-mcp/`, `.remember/`, `.superpowers/` — Claude plugin runtime state. Gitignored.
- `local.properties`, `.idea/` — user-environment files. Gitignored.
- `docs/superpowers/` archival reorg — discussed; rejected for this pass. Moving 40 plan/spec files would generate large git churn for low real-world value, and the existing date-stamped filenames already convey "this is implementation history, not policy." Revisit if `docs/` clarity becomes a recurring complaint.

## Decisions and Rationale

1. **Delete `fixthis-compose-overlay/` outright.** The directory contains zero source files; only stale `build/` artifacts remain. Verifying it's not referenced by any active Gradle module took ~10s (`settings.gradle.kts` shows the include list explicitly). All historical references are inside `docs/superpowers/plans|specs/` from prior work — those keep their cross-references intact because the deletion is on disk only; the historical text is unchanged.

2. **`./gradlew clean` is in scope.** It's regenerable, but doing it in this pass means the next CI run starts from a known clean state and the `.fixthis/` cleanup numbers below aren't conflated with `build/` numbers.

3. **`fixthis clean --older-than-days 7` (not 30, not 0).** Seven days lets us keep recent debugging sessions intact (the 2026-05-09 / 2026-05-10 sessions used while shipping the prompt v2 and console-state-sync work). Anything older is from completed milestones and easily recreated by re-running `fixthis run`.

4. **ADR 0005 stays in place; only the status changes.** ADRs serve as decision history. Removing one rewrites that history and breaks any external link. The cheapest faithful update is to mark the status superseded and add one sentence explaining what replaced it (in this case, "module retired; no current state-machine equivalent exists in `fixthis-compose-sidekick`").

5. **`design-target-evidence-handoff-review.md` moves into `docs/superpowers/specs/`.** This file is a sibling of plans authored on 2026-05-07. Top-level `docs/` is reserved for living guidance (privacy, mcp, troubleshooting, project-overview, …). The file is a one-time review, not living guidance. Move via `git mv` so the file's history follows it; rename to add the `2026-05-07-` prefix so it sorts naturally with peers.

## Non-Goals

- Do not change `.gitignore` rules. Current rules already cover `.fixthis/`, `*/build/`, `.DS_Store`, etc.
- Do not touch any source file outside the dead-module deletion.
- Do not relocate active-living docs (`README.md`, `AGENTS.md`, `CONTRIBUTING.md`, `CLAUDE.md`, `docs/privacy.md`, `docs/troubleshooting.md`, `docs/mcp.md`, `docs/project-overview*.md`, `docs/release-readiness.md`, `docs/feedback-console-contract.md`, `docs/console-state-sync-design.md`, `docs/output-schema.md`, `docs/fixthis_*.md`).
- Do not delete any ADR file.
- Do not rewrite git history; everything is forward-only commits.

## Risks and Mitigations

| Risk | Mitigation |
| --- | --- |
| `fixthis clean` deletes a session the user is actively using. | Use `--dry-run` first; the command preserves `.fixthis/project.json` and honors `--older-than-days`, so anything <7d stays. |
| `git mv` rename loses blame chain. | Use `git mv` (not delete + write); git log `--follow` continues to track. |
| Future references to the moved doc break. | Single grep for `design-target-evidence-handoff-review` shows one external pointer (in another superpowers plan). Update or accept the broken link as historical (the prior plan won't be re-edited regardless). |
| ADR status change is mistaken for "decision reversed". | The status line will explicitly say "Superseded — overlay module retired" and a one-sentence "what replaced it" follow-up; reverses are usually labeled differently (`Replaced by ADR-NNNN`). |
| Build-clean leaves a flaky next build (e.g. annotation processor cache miss). | `./gradlew clean` is the standard Gradle target and is run by CI all the time. If the next local build is slow, it's just incremental cache warmup, not breakage. |

## Verification

After execution:

1. `git status` clean except for the 4 expected modified/added paths (1 ADR edit, 1 mv, 1 spec, 1 plan) plus the deletion of `fixthis-compose-overlay/`.
2. `./gradlew :fixthis-mcp:test` passes (sanity that nothing structural was disturbed).
3. `du -sh .` shows ~1 GB less than before.
4. `find . -name fixthis-compose-overlay` returns no on-disk hit.
5. README link audit (re-run from earlier work) reports zero broken markdown links.

## Decision log

| Date | Decision | Rationale |
| --- | --- | --- |
| 2026-05-10 | Approve cleanup pass | One-shot housekeeping that pays for itself in disk + first-time reader clarity. |
| 2026-05-10 | Defer `docs/superpowers/` archival reorg | Net cost > value at this size; revisit if doc tree pain recurs. |
