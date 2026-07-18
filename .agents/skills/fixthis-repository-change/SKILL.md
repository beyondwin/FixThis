---
name: fixthis-repository-change
description: Use when implementing, diagnosing, reviewing, or verifying source, test, docs, architecture, console, CLI, sidekick, or Gradle-plugin work inside the FixThis source checkout.
---

# FixThis Repository Change

Use for FixThis source checkout work. Do not use for installing FixThis into an external app.

1. Read root AGENTS.md and run its Git/worktree preflight.
2. Choose a matching task id from `scripts/agent-route-registry.mjs`; for console work run `npm run agent:route -- --task console --json`. Once paths are known use `npm run agent:route -- --changed --base origin/main`.
3. Read only returned maintained docs and first source files, then inspect current implementation.
4. Preserve unrelated dirty changes and make the smallest compatible change.
5. Write the failing focused test before behavior changes.
6. Run returned focused checks from cheapest to broadest.
7. Rerun `npm run agent:route -- --changed --json` against the final diff.
8. Run the returned broad gate and connected proof when required.
9. Report PASS, FAIL, DEFERRED, or SKIPPED with evidence, reason, and residual risk.
10. Recheck branch, upstream, dirty state, and worktrees.

Never modify personal or project Codex configuration in this workflow. Commit, merge, push, publish, resolve feedback, or stop a process only when requested.
