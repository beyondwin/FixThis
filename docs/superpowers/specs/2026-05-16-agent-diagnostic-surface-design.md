# Agent Diagnostic Surface — Design

- **Date:** 2026-05-16
- **Status:** Draft (awaiting review)
- **Scope:** `:fixthis-cli`, `:fixthis-mcp` (doctor text path), `:fixthis-gradle-plugin` (agent-setup
  generation), `docs/`, CI workflows.
- **Out of scope:** Remote telemetry, user-facing console UX, deep heuristic improvements
  (multi-module detection logic, View-system partial support), console empty-state coaching.

## 1. Motivation

FixThis 0.2.3 is publicly installable via Homebrew, npm, Maven Central, and the MCP
Registry. The remaining onboarding friction sits almost entirely on the agent's
side: when an agent (Claude Code / Codex) runs `fixthis install-agent`, parses
`fixthis doctor`, or reads `.fixthis/agent-setup.*`, the surface returns
information that is inconsistent, partial, or unsafe. A live install-flow audit on
2026-05-16 surfaced eight concrete issues across these surfaces, three of which
are high-severity (partial-success + exit 1, unguarded global config writes,
`--dry-run` leaking the user's full `~/.codex/config.toml` to stdout).

This spec defines the **agent-facing diagnostic surface** as a single coherent
contract: every CLI command, doctor output, and agent-handoff file must be
machine-parseable, unambiguous, and side-effect-safe so the agent can decide its
next action without human input.

## 2. Live-test findings (evidence)

| # | Finding | Severity | File / Location |
|---|---------|----------|------------------|
| 1 | `fixthis --version` unsupported — common agent verification fails | M | CLI root |
| 2 | `install-agent` failure prints single-line natural language, no fix hint | H | `GradlePluginInstaller.kt:20` |
| 3 | `doctor` text output lacks `fix` field that JSON has | H | `DoctorCommand.kt:52` |
| 4 | `install-agent` and `doctor` share messages but not hints | M | cross-module |
| 5 | `install-agent` partial-success returns exit 1, no rollback, no machine summary | H | `SetupCommand.kt` + `GradlePluginInstaller.kt` composition |
| 6 | Empty / non-Android dir + `--package` silently mutates global `~/.codex/config.toml` | H | `SetupCommand.kt:70-74` |
| 7 | `--dry-run` echoes entire merged config (full global file) to stdout | H | `SetupCommand.kt:106` |
| 8 | dry-run still returns exit 1 on partial plan, semantics undocumented | L | `SetupCommand.kt:100-115` |

## 3. Goals

1. **Agent self-recovery.** When any command fails, the agent receives enough
   structured signal to choose the next action without re-prompting the human.
2. **No silent side-effects.** Global-scope writes (e.g.
   `~/.codex/config.toml`) require explicit consent or a verified project
   context. Partial application is transactional or clearly enumerated.
3. **Documentation parity.** README / AGENTS.md / CLAUDE.md / MCP.md install
   commands and CLI surface stay in sync, checked in CI.
4. **Privacy-safe diagnostics.** `--dry-run` outputs the diff, not the full
   surrounding file.

## 4. Non-goals

- Building remote telemetry, dashboards, or analytics.
- Improving the multi-module / View-system / non-Compose heuristic itself
  (only the surface message is in scope; heuristic depth is a later
  sub-project).
- Console empty-state UX (A4 — user-facing, will live in the upcoming C+D
  sub-project).

## 5. Architecture

Six deliverables, each independently testable and small enough to land as a
single PR.

### D1 — Doctor remediation hint parity (covers #3)

- Text output mirrors every `fix` field that JSON already carries.
- Format:
  ```
  FAIL <label>: <message>
    ↳ fix: <fix>
  ```
- Implementation: extend the `else` branch in `DoctorCommand.run()` (currently
  lines 51–53) to also echo `"  ↳ fix: $fix"` when `fix` is non-null.
- Tests: assert text output contains every JSON `fix` value when failures
  occur.

### D2 — Standardized CLI exit-code table (covers #1, #4, #8)

Define and document:

| Code | Meaning |
|------|---------|
| `0`  | All requested actions applied (or all checks passed). |
| `1`  | Partial: some actions skipped or some checks failed. Detail in stdout JSON or stderr. |
| `2`  | Usage / argument error (no side effects). |
| `3`  | Environment-blocker (e.g. ADB missing) — actionable via remediation. |
| `4`  | Internal error / unexpected exception. |

- Add `fixthis --version` and `fixthis version` (returns CLI version + bridge
  protocol version as JSON when `--json` is passed).
- Reference table lives at `docs/reference/cli-exit-codes.md` and is generated
  in CI.

### D3 — `install-agent` transactional safety + JSON output (covers #2, #5, #6)

- Add `--json` to `install-agent` / `setup`. Output schema:
  ```json
  {
    "schemaVersion": "1.0",
    "ok": false,
    "applied": [{ "target": "claude", "path": "...", "scope": "project-local" }],
    "skipped": [{ "target": "gradle-plugin", "reason": "no-app-module", "fix": "..." }],
    "errors": [],
    "next": ["./gradlew fixthisSetup", "fixthis doctor --json"]
  }
  ```
- **Two-phase commit:** build all `AgentConfigWritePlan`s first, validate them,
  then write all-or-none. If validation fails, exit 2 without touching disk.
- **Global-scope guard:** writing to `~/.codex/config.toml` (global) is allowed
  only when the project-dir resolves to a real Android project (has
  `settings.gradle(.kts)` AND a discoverable `applicationId`). Otherwise the
  command requires `--allow-global` and emits `skipped[].reason="no-android-context"`.
- Exit codes: `0` all applied, `1` partial (JSON detail provided), `2` usage,
  `3` env-blocker.

### D4 — Error-message template (covers #2 part, #1, A3, A7)

A single template applied to every "soft" failure surface:

```
<cause>
  verify: <one command the agent can run to confirm>
  fix:    <one or more option lines, each independently runnable>
```

Concrete rewrites:

| Case | Cause | Verify | Fix |
|------|-------|--------|-----|
| No app module found | `Multi-module project has no app module matching '<pkg>'` | `./gradlew projects` | `pass --package <correct-id>` / `apply plugin manually` |
| Release-only variant | `Detected release-only assembly; sidekick attaches debug only` | `./gradlew tasks --group=build \| grep Debug` | `add debug variant` / `use 'fixthis run --variant debug'` |
| View-system mixed | `Module contains View-based activities; sidekick supports Compose only` | `grep -r 'setContentView' <module>/src/main` | `migrate to ComponentActivity + setContent` / `target a different module` |
| No `applicationId` | `No unique applicationId in build files` | `./gradlew :app:properties \| grep applicationId` | `pass --package <id>` / `run from app module` |

### D5 — Docs cross-reference integrity (covers A5, A8)

- New CI job: `docs-cli-surface-check.sh` parses install commands out of
  `README.md`, `AGENTS.md`, `CLAUDE.md`, `MCP.md`,
  `docs/getting-started/agent-install-snippet.md`, and verifies each command
  exists in the current CLI surface and uses currently-valid flags.
- `agent-install-snippet.md` includes an explicit branching decision tree
  (brew → npm → curl, with the conditions stated) so agents pick without
  prompting.
- `.fixthis/agent-setup.{md,json}` schema is defined and enforced: every
  generator emits `state` (current observed environment), `next` (ordered
  ranked actions), `recovery` (per-failure-mode hint). Generated by
  `AgentSetupFiles.kt`; schema documented in
  `docs/reference/agent-setup-schema.md`.

### D6 — `--dry-run` diff-only output + privacy guard (covers #7, #8)

- Replace `echo(merged.trimEnd())` at `SetupCommand.kt:106` with a diff
  renderer that prints only the entries being added or changed, formatted as
  a unified diff (TOML / JSON snippet).
- Add an explicit byte budget: dry-run stdout for any single target must not
  exceed 4 KiB without the user passing `--full-diff`. Output a footer note
  describing how many bytes were elided.
- `--dry-run` exits `0` if the planned write would succeed and `1` if planned
  steps would fail (the latter mirrors real exit semantics, documented in the
  exit-code table from D2).
- Privacy test: feed a synthetic global config containing a marker token; run
  `install-agent --dry-run`; assert the token does NOT appear in stdout.

## 6. Component map

| Module | Touches |
|--------|---------|
| `:fixthis-cli` (`commands/`) | D1, D2, D3, D4, D6 |
| `:fixthis-cli` (`AgentSetupFiles.kt`) | D5 |
| `:fixthis-mcp` (none) | — |
| `:fixthis-gradle-plugin` | D5 (agent-setup payload schema only; no behavioral change to plugin) |
| `docs/` | D2 (exit-code table), D4 (templated messages catalog), D5 (snippet + schema) |
| CI `.github/workflows/` | D5 (`docs-cli-surface-check`), D6 (privacy regression test) |

Each deliverable's tests live next to the touched code; no cross-module test
coupling.

## 7. Contracts and invariants

- **install-agent JSON schema** is versioned (`schemaVersion: "1.0"`). New
  fields are additive. The field set documented above is the public contract.
- **Exit-code table** is public; changing it is a MINOR bump per
  CHANGELOG.md versioning policy.
- **`.fixthis/agent-setup.{md,json}` schema** is versioned; old agents
  reading newer files must still find `next[0]` as a runnable command string.
- **No new external API calls.** Local-first invariant preserved.

## 8. Error-handling strategy

- Every CLI exit goes through one of: `success(json?)`, `partial(json)`,
  `usageError(msg)`, `envBlocker(msg, fix)`, `internalError(throwable)`.
- A small helper in `cli/CliExit.kt` (new) implements this. Existing
  `CliktError` usages migrate per command, not big-bang.

## 9. Testing strategy

- **Unit** (Kotlin, JUnit): exit-code helper mapping, message-template
  rendering, doctor text↔JSON parity, two-phase-commit planner, dry-run diff
  renderer, global-scope guard predicate.
- **Integration** (`:fixthis-cli:test`): run `install-agent` against a
  fixture directory tree (empty / Compose-only / View-mixed / multi-module),
  assert stdout JSON, exit code, and that disk state matches `applied`
  exactly (i.e. nothing in `skipped` was written).
- **Privacy regression**: synthetic marker test for D6 (see §5).
- **CI** (`docs-cli-surface-check.sh`): documented install commands parse and
  resolve against the current CLI surface.

## 10. Open questions

1. Should `--json` flag be implied when stdout is not a TTY, or always
   explicit? (Recommendation: always explicit; implicit JSON breaks human
   debugging.)
2. `--allow-global` default: warn-only (additive) vs hard-block (breaking).
   Recommendation: **warn-only in 0.x, hard-block at 1.0** so we collect
   real-world breakage before tightening.
3. Does the bridge protocol version belong in `fixthis --version` JSON
   output, or in a separate `fixthis protocol-version`? Recommendation:
   include in `--version --json` as a sub-field; keep human `--version` to
   one line.

## 11. Phased rollout

| Phase | Deliverables | Why first |
|-------|--------------|-----------|
| 1 | D1, D2, D4 | Pure additive surface — no behavior change, low risk, immediate agent UX win |
| 2 | D6 | Privacy fix; lands independently from D3 transactional work |
| 3 | D3 | The biggest behavior change; needs D2's exit-code table in place |
| 4 | D5 | Docs + CI tightening on top of stabilized surface |

Each phase is its own PR; merge order strict (phases depend on prior exit-code
and JSON contracts).

## 12. Success criteria

- An agent that follows the steps in `agent-install-snippet.md` against an
  arbitrary Compose Android repo reaches "annotation → handoff" without
  consulting the human, OR fails with a `next` array pointing at the exact
  remediation command.
- Privacy regression test passes: no synthetic marker token from a global
  config file ever appears in dry-run stdout.
- CI doc-surface check is required on PRs and prevents drift between docs
  and CLI flags.
- All eight live-test findings (§2) have a corresponding regression test
  asserting the new behavior.
