# Project Cleanup Pass — Implementation Plan

**Date:** 2026-05-10
**Status:** Completed 2026-05-10
**Design:** [`docs/superpowers/specs/2026-05-10-project-cleanup-pass-design.md`](../specs/2026-05-10-project-cleanup-pass-design.md)

This plan executes the cleanup pass approved in the design doc. Each phase is independently runnable; later phases do not depend on earlier ones except where called out.

---

## Phase 0 — Pre-flight

**Tasks:**

1. Confirm working tree is clean (`git status` shows no unrelated changes; we'll be the only writer).
2. Confirm CLI is built so `fixthis clean` is available later: `test -x fixthis-cli/build/install/fixthis/bin/fixthis`. If not, `./gradlew :fixthis-cli:installDist` first.
3. Record current disk usage of `.` for before/after comparison: `du -sh .` → write to scratch.

**Verification:** `git status` clean; CLI binary present.

**Rollback:** N/A (no writes yet).

---

## Phase 1 — Delete dead module + DS_Store

**Tasks:**

1. `rm -rf fixthis-compose-overlay` — entire orphan directory (only contains stale `build/`).
2. `rm -f .DS_Store` — root-level Finder metadata.
3. Sanity grep: `grep -rn "fixthis-compose-overlay\|compose-overlay" --include="*.kts" --include="*.kt" --include="*.toml"` to confirm zero live references in build files. Historical references inside `docs/superpowers/` are expected and OK; they're frozen implementation history.

**Verification:**
- `find . -name fixthis-compose-overlay -not -path "./.git/*"` returns empty.
- `git status` shows the dir as deleted (because it was tracked? actually only `build/` was, and `build/` is gitignored, so this delete is filesystem-only; nothing to stage).
- `find . -maxdepth 1 -name .DS_Store` returns empty.

**Rollback:** `git checkout` restores nothing because both items were untracked. Re-running `./gradlew` re-creates `build/` dirs in any module that needs one. The dead overlay module has no remaining build-graph reference, so `gradlew` won't recreate `fixthis-compose-overlay/`.

---

## Phase 2 — ADR 0005 status update

**Edit `docs/adr/0005-overlay-mode-state-machine.md`:**

- Header `- Status: Accepted` → `- Status: Superseded — overlay module retired`
- Append, immediately under the existing header lines, a one-paragraph "Update" note:

  > **2026-05-10 update.** The `fixthis-compose-overlay` module described below was retired before V1 ship. This ADR is preserved for decision-trail history; no current module exposes the state machine documented here. Selection / commenting flow now lives in the desktop console (`fixthis-mcp`) and the in-app sidekick is reduced to a connection pill.

**Verification:** `git diff docs/adr/0005-overlay-mode-state-machine.md` shows exactly one status edit and one inserted update note. `head -10 docs/adr/0005-overlay-mode-state-machine.md` reads cleanly.

**Rollback:** `git checkout -- docs/adr/0005-overlay-mode-state-machine.md`.

---

## Phase 3 — Move handoff-review doc into superpowers/specs

**Tasks:**

1. `git mv docs/design-target-evidence-handoff-review.md docs/superpowers/specs/2026-05-07-target-evidence-handoff-review.md`
   - Date prefix `2026-05-07` matches the related plan `docs/superpowers/plans/2026-05-07-stable-target-evidence-v1-implementation.md` (file was first added 2026-05-07 12:11 KST per `git log --diff-filter=A`).
2. Update the one outside cross-reference inside `docs/superpowers/plans/2026-05-07-stable-target-evidence-v1-implementation.md` if it points at the old path. (Run `grep -rn "design-target-evidence-handoff-review" docs/` to confirm; edit only if a live link points at `docs/`.)
3. README/AGENTS/CONTRIBUTING/CLAUDE.md don't reference this file (verified during 2026-05-10 README pass), so no top-level link breakage.

**Verification:** `git status` shows the rename as `R100` or `R<99` similarity. `find docs -name '*target-evidence-handoff-review*'` returns the new path only.

**Rollback:** `git mv` the file back; restore any updated cross-reference.

---

## Phase 4 — Gradle clean

**Tasks:**

1. `./gradlew clean` — wipes every module's `build/` directory.

**Verification:** `du -sh */build 2>/dev/null` returns nothing or zero-sized dirs. `./gradlew :fixthis-mcp:test` succeeds (proving the next-build incremental works).

**Rollback:** N/A — `./gradlew clean` is non-destructive to source.

---

## Phase 5 — Old feedback-session reclaim

**Tasks:**

1. Dry-run first: `fixthis-cli/build/install/fixthis/bin/fixthis clean --project-dir . --older-than-days 7 --dry-run`. Inspect the report:
   - Confirm 2026-05-09 and 2026-05-10 sessions are NOT in the would-delete list.
   - Confirm `.fixthis/project.json` is preserved.
2. Execute: `fixthis-cli/build/install/fixthis/bin/fixthis clean --project-dir . --older-than-days 7`.
3. Record the reclaimed bytes from the CLI summary.

**Verification:**
- Recent session IDs (per `ls .fixthis/feedback-sessions/ -t | head -5` before run) still present after.
- `du -sh .fixthis` shows the reclaimed difference.

**Rollback:** None — sessions deleted by `fixthis clean` are gone. Mitigated by the `--dry-run` step above.

---

## Phase 6 — Verification + commit

**Tasks:**

1. Re-run README link audit:
   ```bash
   grep -oE '\(\.?\.?/?[A-Za-z0-9_./-]+\.md\)' README.md | tr -d '()' | sort -u | while read p; do
     test -f "$p" || echo "MISS $p"
   done
   ```
   Expect empty output.
2. `git status` review — exactly 4 changes expected:
   - **modified:** `docs/adr/0005-overlay-mode-state-machine.md`
   - **renamed:** `docs/design-target-evidence-handoff-review.md` → `docs/superpowers/specs/2026-05-07-target-evidence-handoff-review.md`
   - **new:** `docs/superpowers/specs/2026-05-10-project-cleanup-pass-design.md`
   - **new:** `docs/superpowers/plans/2026-05-10-project-cleanup-pass-implementation.md`
   - (plus the optional cross-ref update from Phase 3, if any)
3. Commit with the message:

   > chore(repo): cleanup pass — drop dead overlay module, archive handoff-review, update ADR 0005

   Body summarizes the disk reclaim and points at the design + plan docs.

4. Append a "Done" log section to this file recording:
   - Disk size before / after
   - Sessions deleted by `fixthis clean`
   - Test result
   - Final commit hash

**Verification:** Test suite green, working tree clean, commit on `main`.

**Rollback:** `git revert <commit>` is safe; the only durable side-effects (gradle build dirs, deleted feedback sessions, deleted overlay dir) are all regenerable or were already regenerable / untracked.

---

## Done log

- **Disk before:** 1.2 GB total, of which `.fixthis/` 982 MB, `fixthis-compose-overlay/` 57 MB, `*/build/` 168 MB.
- **Disk after:** 108 MB total, of which `.fixthis/` 22 MB, `*/build/` 13.7 MB (just `fixthis-cli` reinstall + gradle plugin cache).
- **Net reclaim:** ~1.1 GB.
- **Notable surprise:** the `.fixthis/` size was almost entirely `.fixthis/preview-cache/` (961 MB), not session JSON / artifacts. Sessions themselves were 22 MB across 85 sessions. The `fixthis clean` CLI is directory-granular and skipped `preview-cache` because its mtime was within 7 days, so we deleted that directory directly via `rm -rf`. `feedback-sessions/` was preserved verbatim — none of its 85 sessions met the closed-AND-≥7-days criterion (active iteration over the past 2.5 days kept everything within window). Future-direction note: a per-session age filter inside `fixthis clean` would let us prune closed sessions individually instead of all-or-nothing on the parent dir.
- **Phase notes:**
  - Phase 1: zero live code references found for `fixthis-compose-overlay`. The only live `.md` reference was inside `docs/design-target-evidence-handoff-review.md`, which was itself moved into superpowers/specs/ in Phase 3 — frozen historical context, no action needed.
  - Phase 3: cross-references inside `docs/superpowers/plans/2026-05-07-stable-target-evidence-v1-implementation.md` were left as-is. Those lines are part of a frozen, executed plan and rewriting them would falsify history. README/AGENTS/CONTRIBUTING/CLAUDE.md don't link to the moved doc, so no top-level breakage.
  - Phase 4: `./gradlew clean` removed the installed CLI binary at `fixthis-cli/build/install/`; rebuilt with `./gradlew :fixthis-cli:installDist` before Phase 5.
- **Tests:** `./gradlew :fixthis-mcp:test` — BUILD SUCCESSFUL (340 tests).
- **README link audit:** zero broken links.
- **Commit:** `_<filled in by commit step>_`
