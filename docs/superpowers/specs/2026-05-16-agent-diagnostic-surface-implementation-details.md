# Agent Diagnostic Surface — Implementation Details

- **Date:** 2026-05-16
- **Status:** Implementation reference
- **Companion docs:** `2026-05-16-agent-diagnostic-surface-design.md`, `../../plans/agent-diagnostic-surface.md`
- **Primary modules:** `:fixthis-cli` (`commands/`, root `Main.kt`), `docs/`, `.github/workflows/`

This is the technical companion to the design and the implementation plan. It
records the *why* behind the API shapes, the edge cases the plan's bite-sized
tasks don't have room to spell out, and the alternative approaches considered
and rejected. The plan tells the implementer *what to type next*; this doc
tells them *what to do when their typing produces a surprise*.

---

## 1. Current state shape

The agent-facing CLI today emits messages from three uncoordinated layers:

| Layer | File | What it returns |
|-------|------|------------------|
| Doctor checks | `commands/DoctorCommand.kt:35-55` | Text `FAIL <label>: <msg>` only; JSON path adds `fix`. |
| MCP-config writers | `commands/SetupCommand.kt:70-115` | Per-target `Wrote ...` lines; dry-run echoes entire merged file content. |
| Gradle-plugin install | `commands/GradlePluginInstaller.kt:20-21` | Single concatenated sentence, no structure. |

The composition (`InstallAgentCommand` → `InitCommand` → `SetupCommand` →
`GradlePluginInstaller`, `SetupCommand.kt:222-249, 148-199`) means a partial
failure surfaces as: success line + failure line + exit 1, with no
machine-readable join. The agent reads "Wrote claude MCP config (...)\nCould
not find an Android app module..." and has to decide which part fired and
which next action recovers — guesswork.

`DoctorCheckResult` (`DoctorCommand.kt:115-121`) already carries `message` and
`fix` per check. The asymmetry between the JSON output (line 99–100) and the
text output (line 51–53) is the cheapest fix in the entire spec and the
single biggest agent-UX win — D1 is one extra `echo`.

---

## 2. Desired state — public surface

After this spec lands, every subcommand the agent calls has the same external
shape:

```
fixthis <sub> [--json] [...]
  → stdout: text-friendly summary, OR (with --json) a single JSON object
  → stderr: warnings (non-fatal) using SetupErrorRedactor as today
  → exit:   ExitCode.{OK|PARTIAL|USAGE_ERROR|ENV_BLOCKER|INTERNAL_ERROR}
```

The structured contract the agent reads is:

```kotlin
enum class ExitCode(val value: Int) {
    OK(0), PARTIAL(1), USAGE_ERROR(2), ENV_BLOCKER(3), INTERNAL_ERROR(4)
}
```

```kotlin
internal object InstallAgentJsonReport {
    data class Applied(val target: String, val path: String, val scope: String)
    data class Skipped(val target: String, val reason: String, val fix: String)
    data class ErrorEntry(val target: String, val message: String)

    fun render(
        applied: List<Applied>,
        skipped: List<Skipped>,
        errors: List<ErrorEntry>,
        next: List<String>,
    ): String
}
```

Renders to:

```json
{
  "schemaVersion": "1.0",
  "ok": false,
  "applied": [{"target": "claude", "path": "...", "scope": "project-local"}],
  "skipped": [{"target": "codex", "reason": "no-android-context", "fix": "..."}],
  "errors":  [],
  "next":    ["./gradlew fixthisSetup", "fixthis doctor --json"]
}
```

The `applied / skipped / errors` partition is **disjoint** — a target appears
in exactly one list. `ok = (skipped.isEmpty() && errors.isEmpty())`, mirrored
in the exit code (`OK` ↔ `ok=true`, `PARTIAL` ↔ `ok=false ∧ errors.isEmpty()`,
`INTERNAL_ERROR` ↔ `errors.isNotEmpty()`).

`schemaVersion` is a string ("1.0") not an int. Additive fields don't bump
the version. Renaming, removing, or changing the meaning of an existing
field is a MAJOR per CHANGELOG versioning policy.

---

## 3. Two-phase commit details

The plan describes Phase 1 as "stage to `*.fixthis-staging`" and Phase 2 as
"`Files.move` with `ATOMIC_MOVE`". Two edge cases the plan doesn't fully
spell out:

**Cross-filesystem moves.** `ATOMIC_MOVE` fails with `AtomicMoveNotSupportedException`
when the staging file and the target file are on different filesystems
(common when `~/.codex/config.toml` lives on a separate volume on macOS, or
when `/tmp` and `$HOME` differ). The implementer must catch this and fall
back to a copy-then-delete sequence under a lock:

```kotlin
try {
    Files.move(staging, target, ATOMIC_MOVE, REPLACE_EXISTING)
} catch (e: AtomicMoveNotSupportedException) {
    // Best-effort non-atomic fallback. The window of risk is the duration
    // of the copy; for these <10 KiB config files it is microseconds.
    Files.copy(staging, target, REPLACE_EXISTING)
    Files.delete(staging)
}
```

**Mid-commit failure.** The plan notes "already-renamed files have no easy
backup". The implementer should: (a) save the original contents of each
target file into a sibling `*.fixthis-rollback` before the first rename, and
(b) on commit failure, restore from those rollback files for every target
that's already been renamed. Rollback files are cleaned up on successful
finish:

```kotlin
val rollbacks = mutableMapOf<Plan, File?>()
plans.forEach { plan ->
    rollbacks[plan] = if (plan.configFile.exists()) {
        val rb = File("${plan.configFile.absolutePath}.fixthis-rollback")
        plan.configFile.copyTo(rb, overwrite = true); rb
    } else null
}
try {
    // stage + commit ...
    rollbacks.values.filterNotNull().forEach { it.delete() }
} catch (e: Exception) {
    // restore
    rollbacks.forEach { (plan, rb) ->
        if (rb != null) rb.copyTo(plan.configFile, overwrite = true)
        else plan.configFile.delete()
    }
    throw CliktError("...", statusCode = ExitCode.INTERNAL_ERROR.value)
}
```

This makes the entire `install-agent` operation truly transactional from the
filesystem's point of view, not just append-safe.

---

## 4. `GlobalScopeGuard` heuristic edge cases

`isAndroidProject(root)` returns true iff both:

1. `settings.gradle{.kts}` exists at `root`, AND
2. Some `build.gradle{.kts}` within `maxDepth=3` contains the literal token
   `applicationId`.

The plan's `findApplicationIdInTree` walks the tree. Edge cases:

- **`applicationId` in a comment.** A line like `// applicationId = "x"` passes
  the `.contains("applicationId")` check. False-positive risk is *acceptable*
  here: the guard's purpose is to refuse `~/.codex` writes from an *empty*
  directory, not to validate Android project quality. A commented-out
  `applicationId` still strongly implies the user is inside an Android repo.

- **Convention-plugin projects** that put `applicationId` inside
  `buildSrc/` or `build-logic/` rather than the app module's `build.gradle.kts`.
  These will fail the predicate. Workaround: pass `--allow-global` explicitly
  or run from within the app module subdirectory. Document this in the
  `recovery.no-android-context` hint.

- **Symlinked project roots.** `walkTopDown()` follows symlinks by default in
  `kotlin.io.FileTreeWalk`. The implementer should call
  `.maxDepth(3).onEnter { ... }` to skip `.git`, `node_modules`, `build`, and
  any directory whose canonical path lies outside `root.canonicalFile`. This
  also bounds the scan time.

- **Read-only filesystems.** The predicate only reads; it does not mutate.
  No issue here, but the `*.fixthis-rollback` write in §3 must be guarded
  by a "is the parent writable?" check before the first rename — otherwise
  rollback file creation fails and the user gets a confusing error after
  the first config has already been written.

---

## 5. `DryRunDiff` algorithm

The plan's JSON diff is keyed by top-level entries plus one level of nesting
into `mcpServers`. That covers both today's writer shapes:

- Claude (`commands/ClaudeConfigWriter.kt`): `{ "mcpServers": { "<name>": {...} } }`
- Codex (`commands/CodexConfigWriter.kt`): TOML, `[mcp_servers.<name>]` sections

Why one level of nesting and not arbitrary depth? Because the **only** path
we ever write into for Claude is `mcpServers.<serverName>`, and for Codex it
is the `[mcp_servers.<serverName>]` and `[mcp_servers.<serverName>.env]`
sections. Diffing the whole tree would just give an attacker (or careless
`--full-diff` user) a free dump of unrelated entries. The narrow diff path
*is* the privacy guarantee — any rendering bug that widens the diff also
widens the leak.

**TOML diff** (plan Task 2.1, `renderToml`) uses a naive line-set difference.
This is acceptable because: (a) we always *append* sections (the writer's
`merge()` semantics preserve existing content), so `after.lines() - before.lines()`
is exactly the set of new lines; (b) any line that happens to appear in both
files is by definition already known to the agent, so omitting it from the
diff cannot hide new information.

**Byte budget enforcement** (plan Task 2.1, `enforceBudget`) truncates at
the byte boundary, which can split a multi-byte UTF-8 sequence. The
implementer should walk back to the previous codepoint boundary before
appending the elision footer. `String.toByteArray()` then `String(bytes,
Charsets.UTF_8)` already coerces malformed sequences to U+FFFD, so the
output is always valid UTF-8 — just possibly truncated at an ugly visual
boundary. For a 4 KiB budget on ASCII-dominant config files this is a
non-issue but worth documenting.

---

## 6. `AgentSurfaceMessages` template choices

The three-line template (`<cause>` / `verify: <cmd>` / `fix: <opt>`) was
chosen over JSON-structured failure objects because:

1. The text version still lands in agent context (the agent reads stdout/stderr
   as a string), so a parseable text form is sufficient.
2. JSON-structured failure objects require an agent-side parser, whereas the
   template is already line-oriented and grep-friendly (`grep '^  fix:'`).
3. The `--json` flag covers the structured-output use case at the
   command-level (D3). Per-message JSON would duplicate that channel.

The single trailing space convention in `"verify: "` and `"fix:    "`
deliberately uses padding so columns align in the rendered terminal output
without imposing a Markdown table. The verify/fix prefixes are public
contract (regression tests grep for them) — do not change them without a
MINOR bump.

---

## 7. `AgentSetupFiles` schema v1.0

`docs/reference/agent-setup-schema.md` (created in plan Task 4.1) defines the
contract. Two implementation notes the plan doesn't dwell on:

**`state.detectedAt` is RFC 3339.** Use `Instant.now().toString()` from
`java.time` — its default rendering is RFC 3339 in UTC with `Z` suffix. Do
not depend on the system locale.

**`recovery` is keyed by failure-mode id, not human prose.** The keys
(`"no-android-context"`, `"no-app-module"`, `"release-only-variant"`,
`"view-system-mixed"`, `"missing-application-id"`) form a closed set
documented in the schema. The agent matches `skipped[].reason` against this
keyset to look up the corresponding recovery hint. New failure modes added
in later versions append to the keyset and to the doc — that's an additive
change.

**The Markdown sibling (`.fixthis/agent-setup.md`) is a rendering, not a
source.** Generation order: build the JSON in memory → write `agent-setup.json`
→ stringify into markdown → write `agent-setup.md`. If only one is needed,
JSON is canonical.

---

## 8. CI doc-surface check resilience

`scripts/check-docs-cli-surface.sh` (plan Task 4.3) has three known
brittleness vectors:

**Code-fence false positives.** The grep `'fixthis [a-z][a-z-]+'` matches
inside fenced code blocks intended as *examples*, not as canonical commands.
This is acceptable initially; if a doc accumulates many "wrong on purpose"
examples (e.g. troubleshooting docs that show what *not* to type), the
script should be taught about a magic comment marker like
`<!-- check-docs-cli-surface:ignore-next -->`.

**Flag-value confusion.** `fixthis doctor --package my.app.id` contains both
a flag (`--package`) and a value (`my.app.id`). The grep
`'fixthis [a-z][a-z-]+ --[a-z][a-z-]+'` correctly extracts `--package` but
will miss flags after positional arguments. Today no fixthis subcommand
takes positional args, so this is safe; revisit if that changes.

**CLI hot-rebuild.** The workflow runs `./gradlew :fixthis-cli:installDist
--no-daemon` on every PR. For PRs that only touch docs, this is wasteful
(~30s). Optimization deferred: add a path filter on the workflow + a
gradle build cache key. Out of scope for the initial landing.

---

## 9. Backward compatibility & migration

| Change | Compatibility class | Notes |
|--------|---------------------|-------|
| New `ExitCode` table | Additive | Existing exit codes (0/1) preserve their meaning; 2/3/4 are *new* failure paths previously reported as 1. Agents that bucket "non-zero" as failure are unaffected. Agents that special-case `1` for "partial" get a more precise signal. |
| `--json` flag on `install-agent` / `setup` / `doctor` | Additive | Opt-in. Text mode unchanged except for D1's `↳ fix:` line addition. |
| `--allow-global` flag | Additive but behavior-breaking by default | Before: empty dir + `--package` silently writes `~/.codex/config.toml`. After: same invocation skips codex and exits PARTIAL. Recommended rollout: 0.x = warn + skip (current plan); 1.0 = hard refusal. Spec §10 open question #2 — confirm at PR review time. |
| Templated error messages | Behavior-breaking for regex consumers | A user grepping for the literal "Could not find an Android app module" will miss the new templated form. Mitigate by keeping the cause substring stable: `"has no app module matching '<pkg>'"` still contains `"app module"`. CHANGELOG note required. |
| `.fixthis/agent-setup.{md,json}` schema v1.0 | Additive | Existing files are regenerated on next run. Old fields preserved; new ones added. No reader breaks. |
| `--full-diff` flag | Additive | Opt-in escape hatch. Default behavior tightens (privacy-preserving diff). |
| `--version` / `version` subcommand | Additive | Pure addition, no existing command renamed. |

`BridgeProtocol.VERSION` is **not** touched by this spec — no wire changes.
The console JS bundle is **not** touched — no `console-bundle-meta` update
needed.

---

## 10. Alternatives considered (and rejected)

**A. Use `--format=json` instead of `--json`.** Rejected: Clikt's flag idiom
favors `--json` boolean over `--format=<enum>` and existing `doctor --json`
sets the precedent. Consistency wins.

**B. Emit JSON to a sibling file rather than stdout.** Rejected: agents
parsing stdout don't need to know a path; writing to a side file requires
either a deterministic location (introduces a new file contract) or a
flag (boilerplate). Stdout is the universal channel.

**C. Make `--json` implicit when `!isatty(stdout)`.** Rejected (with note in
spec §10 #1): breaks `fixthis doctor | less` and `fixthis doctor > out.txt`
for human users who want the prettier text form even when piping. Explicit
flag is cheaper.

**D. Reject all `~/.codex/config.toml` writes by default; require
`--target=claude` if not in Android project.** Rejected as too aggressive:
some agents legitimately bootstrap codex from a non-Android directory
(e.g. before clone-and-cd). `--allow-global` opt-in respects that workflow.

**E. Use Clikt's built-in `versionOption` only.** Partially used: Clikt's
`versionOption` handles the `--version` flag idiomatically. We add the
`version` subcommand for the `--json` form because attaching a flag to the
top-level `--version` would conflict with Clikt's handling.

**F. Per-failure-mode subclasses of `CliktError`.** Rejected: bloats the
type system for a flat enum's worth of information. The `ExitCode` enum
gives us the partition we need; the message templating gives us the
content.

---

## 11. Likely follow-ups (out of scope for this spec)

- **Multi-module heuristic depth.** This spec only standardizes the
  *message* when the heuristic fails. Improving the heuristic (e.g.
  prompting the agent with module candidates and asking which to patch)
  belongs in a later sub-project — likely paired with `superpowers:writing-plans`
  for the C+D track noted in the brainstorming.
- **`fixthis report` (manual diagnostic bundle).** Mentioned in design §5 as
  part of D-future; explicitly omitted from this spec to keep the scope
  containable.
- **View-system partial support.** Today the message just says "Compose
  only". A future spec could add detection of `androidx.compose.ui.platform.ComposeView`
  embedded inside View hierarchies and surface a "partial Compose detected,
  X% of activities supported" diagnostic.
- **Remote telemetry.** Permanently out — see local-first invariant in
  AGENTS.md.

---

## 12. File-by-file diff summary (for reviewers)

| File | LOC delta (est.) | Risk |
|------|-------------------|------|
| `cli/CliExit.kt` (new) | +25 | Low — pure enum |
| `cli/CliVersion.kt` (new) | +50 | Low — additive |
| `cli/Main.kt` | +5 | Low — wiring |
| `cli/commands/DoctorCommand.kt` | +2 / -0 | Trivial |
| `cli/commands/AgentSurfaceMessages.kt` (new) | +60 | Low |
| `cli/commands/GradlePluginInstaller.kt` | +3 / -2 | Low |
| `cli/commands/SetupCommand.kt` | +180 / -25 | **High** — two-phase commit, JSON output, guard wiring all converge here. Single-author landing recommended. |
| `cli/commands/InstallAgentJsonReport.kt` (new) | +70 | Low |
| `cli/commands/DryRunDiff.kt` (new) | +90 | Medium — naive TOML parser; cover with the privacy-marker test |
| `cli/commands/GlobalScopeGuard.kt` (new) | +50 | Low |
| `cli/commands/AgentSetupFiles.kt` | +40 / -10 | Medium — schema change touches existing generator |
| Tests (8 new files / 5 modified) | +900 | Low — tests fail loudly |
| `docs/reference/cli-exit-codes.md` (new) | +25 | None |
| `docs/reference/agent-setup-schema.md` (new) | +35 | None |
| `docs/getting-started/agent-install-snippet.md` | +30 / -5 | Low |
| `scripts/check-docs-cli-surface.sh` (new) | +60 | Medium — bash; review for `set -euo pipefail` corner cases |
| `.github/workflows/docs-cli-surface.yml` (new) | +25 | Low |
| `CHANGELOG.md` | +20 | None |

Estimated total: ~1900 LOC across ~17 files. Within a single feature branch
this is large but tractable; the four-phase rollout keeps each PR around
500 LOC, which is the review-friendly target observed in this repo's
history.
