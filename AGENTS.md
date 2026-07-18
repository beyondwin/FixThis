# FixThis — Agent Operating Contract

FixThis is a debug-only Jetpack Compose sidekick. The Android app shows connection status; selection, annotation, evidence, and handoff happen in the local desktop console.

## Read Order

1. Read this file.
2. Read [docs/index.md](docs/index.md) and [the project map](docs/guides/project-map.md).
3. Run npm run agent:route -- --task console for an explicit route or npm run agent:route -- --changed --json once paths are known.
4. Read only maintained references and first source files returned by the router.
5. Treat docs/superpowers/*, docs/specs/*, and docs/plans/* as historical context unless current source or maintained docs point there.

Current implementation wins over docs; docs/reference/* wins over historical planning.

## Start

- Run git status --short --branch, git log -5 --oneline --decorate, and git worktree list --porcelain before edits.
- Preserve unrelated dirty changes and check whether another worktree owns the task.
- Classify the request as explanation, diagnosis, implementation, review, release, connected proof, or feedback work.
- Do not turn diagnosis or review into code changes without authorization.

## Change

- Make the smallest compatible change and follow router-returned maintained references.
- Keep compose-core free of MCP, CLI, Android UI, browser DTO, and .fixthis dependencies.
- Preserve persisted field names and coordinate bridge changes through docs/reference/bridge-protocol.md.
- Use canonical generators; never hand-edit build outputs.
- Never commit .fixthis, screenshots, reports, fixture workspaces, or personal agent state.
- Do not modify ~/.codex or project .codex for repository work.

## Verify

- Run focused checks first, then rerun the router against the final diff.
- Run the returned broad gate and npm run android:proof -- --strict when required.
- Record PASS, FAIL, DEFERRED, or SKIPPED; the last two require reason and residual risk.
- Require fresh final-diff evidence before completion.

## Finish

- Re-read the final diff and run git diff --check.
- Recheck branch, upstream, dirty state, and worktrees.
- Report exact commands, results, report paths, residual risks, and local-versus-remote Git state.
- Commit, merge, push, publish, resolve feedback, or stop processes only when requested.

## Product And Tool Boundaries

- Debug builds and Jetpack Compose only; no release runtime, Views, Flutter, or remote service in V1.
- Desktop fixthis-mcp owns HTTP, MCP, console state, and .fixthis; the Android app does not.
- Runtime evidence is local, bounded, redacted, and optional.
- Full tool signatures: [MCP tools](docs/reference/mcp-tools.md).
- Canonical contributor gates: [CONTRIBUTING.md](CONTRIBUTING.md).
- Sample-to-agent flow: [README Quick Start](README.md#quick-start-sample-app-to-agent-handoff).

## Feedback Queue

Read, claim, edit, verify, and resolve in that order. Claim before code changes to avoid duplicate work. Use [the agent guide](docs/guides/agents.md) for the maintained workflow.
