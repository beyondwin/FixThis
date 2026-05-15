# Contributor Bootstrap MCP — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the two-step contributor onboarding (gradle installDist + fixthis setup) with a single Bash wrapper `./scripts/bootstrap-mcp.sh --package <applicationId>`. Update `AGENTS.md` to lead with the wrapper while preserving the manual fallback for Windows / power users.

**Architecture:** One thin Bash script (~30-40 LoC) calls `./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist` then `fixthis-cli/build/install/fixthis/bin/fixthis setup --package … --write --target …`. No new CLI flags, no new test runner.

**Tech Stack:** Bash 4 (macOS / Linux); existing Gradle wrapper; existing `fixthis setup` command (Kotlin).

**Spec:** `docs/superpowers/specs/2026-05-10-contributor-bootstrap-mcp-design.md`

---

## Conventions

- **Test runners:** none added by this plan. Verification is `bash -n` syntax check + manual run.
- **Full regression** (sanity sweep at the end): same 5-module command from the parent staleness plan.
- **Commit style:** lowercase scope, imperative, one task = one commit.
- **Compatibility:** wrapper is additive; manual two-step path remains valid.

## File map

**Created:**

- `scripts/bootstrap-mcp.sh` — executable Bash wrapper.

**Modified:**

- `AGENTS.md` — Quick Start replaced with the wrapper; "Manual setup" sub-section preserves the two-step path.
- `CHANGELOG.md` — single bullet under Unreleased.

---

## Task 1 — Write `scripts/bootstrap-mcp.sh`

**Files:**

- Create: `scripts/bootstrap-mcp.sh`

**Goal:** A working, executable wrapper that runs build + setup in order with clear error surfaces.

- [ ] **Step 1: Verify the script directory exists**

```bash
ls -d scripts/ 2>/dev/null && echo OK || echo "scripts/ missing"
```

If missing, create with `mkdir scripts`.

- [ ] **Step 2: Write the script**

Create `scripts/bootstrap-mcp.sh` with the following exact content:

```bash
#!/usr/bin/env bash
# Bootstrap FixThis MCP for a contributor's machine.
# Combines: ./gradlew installDist + fixthis setup --write
# See docs/superpowers/specs/2026-05-10-contributor-bootstrap-mcp-design.md
set -euo pipefail

print_usage() {
    cat <<'USAGE'
Usage: ./scripts/bootstrap-mcp.sh --package <applicationId> [--target claude|codex|all] [--dry-run]

Builds :fixthis-cli and :fixthis-mcp installDist, then registers the FixThis MCP server with Claude Code, Codex, or both.

Required:
  --package <id>          Android applicationId of the project under test.

Options:
  --target <name>         claude | codex | all (default: all)
  --dry-run               Pass through to fixthis setup; previews config without writing.
  -h, --help              Show this help.
USAGE
}

# Move to repo root regardless of caller's cwd.
cd "$(dirname "$0")/.."

PACKAGE=""
TARGET="all"
EXTRA_FLAGS=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --package)
            [[ $# -ge 2 ]] || { echo "[bootstrap] --package requires a value" >&2; exit 2; }
            PACKAGE="$2"; shift 2 ;;
        --target)
            [[ $# -ge 2 ]] || { echo "[bootstrap] --target requires a value" >&2; exit 2; }
            TARGET="$2"; shift 2 ;;
        --dry-run)
            EXTRA_FLAGS+=("--dry-run"); shift ;;
        -h|--help)
            print_usage; exit 0 ;;
        *)
            echo "[bootstrap] unknown flag: $1" >&2
            print_usage >&2
            exit 2 ;;
    esac
done

if [[ -z "$PACKAGE" ]]; then
    echo "[bootstrap] --package is required" >&2
    print_usage >&2
    exit 2
fi

echo "[bootstrap] cwd → $(pwd)"
echo "[bootstrap] step 1/2 — building :fixthis-cli and :fixthis-mcp installDist"
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist

echo "[bootstrap] step 2/2 — running fixthis setup --target $TARGET"
./fixthis-cli/build/install/fixthis/bin/fixthis setup \
    --package "$PACKAGE" --write --target "$TARGET" "${EXTRA_FLAGS[@]}"

echo "[bootstrap] complete — restart Claude Code or Codex to pick up the new MCP server"
```

- [ ] **Step 3: Make executable**

```bash
chmod +x scripts/bootstrap-mcp.sh
```

- [ ] **Step 4: Bash syntax check**

```bash
bash -n scripts/bootstrap-mcp.sh && echo "SYNTAX OK"
```

Expected: `SYNTAX OK`. Any error → fix the script and re-run.

- [ ] **Step 5: Smoke run — usage path**

```bash
./scripts/bootstrap-mcp.sh
```

Expected: prints usage, exits with code 2 (`echo $?` after a non-set-e shell shows 2; the script itself uses `exit 2`).

```bash
./scripts/bootstrap-mcp.sh --bogus 2>&1 | head -5
```

Expected: first line is `[bootstrap] unknown flag: --bogus`. Exit code 2.

- [ ] **Step 6: Smoke run — dry-run path**

```bash
./scripts/bootstrap-mcp.sh --package io.github.beyondwin.fixthis.sample --dry-run
```

Expected: gradle output (UP-TO-DATE if previously built), then `fixthis setup` dry-run preview (file paths it WOULD write but does not). Exit 0.

If gradle is not available or build fails, the wrapper exits non-zero with Gradle's own error — that is correct behavior; do not mask the failure.

- [ ] **Step 7: Commit**

```bash
git add scripts/bootstrap-mcp.sh
git commit -m "$(cat <<'EOF'
scripts: add bootstrap-mcp.sh contributor wrapper

Single wrapper around `./gradlew :fixthis-cli:installDist
:fixthis-mcp:installDist` + `fixthis setup --write` so new
contributors run one command instead of two.

Task: 1
Risk: low
EOF
)"
```

---

## Task 2 — Update `AGENTS.md` Quick Start

**Files:**

- Modify: `AGENTS.md`

**Goal:** Lead with the wrapper. Keep the manual two-step form documented as a fallback.

- [ ] **Step 1: Read the current Quick Start section**

```bash
sed -n '8,35p' AGENTS.md
```

Confirm the current Quick Start is the two-step block (`./gradlew installDist` then `fixthis setup`).

- [ ] **Step 2: Replace Quick Start; add Manual setup sub-section**

Edit `AGENTS.md`. Replace the current Quick Start block with:

```markdown
## Quick Start

```bash
# Bootstrap MCP integration (build + register with Claude Code / Codex)
./scripts/bootstrap-mcp.sh --package <applicationId>
```

`--package` is the Android applicationId of the app you are running FixThis against (e.g. `io.github.beyondwin.fixthis.sample`). The script writes:

- **Claude** → project-local `.claude/settings.json` (only affects this project)
- **Codex** → user-global `~/.codex/config.toml` (affects all Codex sessions)

Pass `--target claude` or `--target codex` to limit the targets, or `--dry-run` to preview without writing:

```bash
./scripts/bootstrap-mcp.sh --package <applicationId> --dry-run
```

After it completes, restart Claude Code or Codex so the new MCP server is picked up.

### Manual setup

If you cannot run the wrapper (e.g., on Windows), the equivalent two-step form is:

```bash
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist
fixthis-cli/build/install/fixthis/bin/fixthis setup \
  --package <applicationId> \
  --write \
  --target all
```
```

Match the surrounding heading levels (`##` for "Quick Start", `###` for "Manual setup"). Preserve everything outside this section verbatim.

- [ ] **Step 3: Sanity check the rendered Markdown**

```bash
sed -n '1,60p' AGENTS.md
```

Visually confirm the section reads well and the Manual setup block is preserved.

- [ ] **Step 4: Commit**

```bash
git add AGENTS.md
git commit -m "$(cat <<'EOF'
docs: lead AGENTS Quick Start with bootstrap-mcp.sh wrapper

The wrapper replaces the two-step manual flow. Manual setup remains
documented as a Windows / power-user fallback.

Task: 2
Risk: low
EOF
)"
```

---

## Task 3 — Audit `README.md`; CHANGELOG; full regression

**Files:**

- Modify (conditional): `README.md`
- Modify: `CHANGELOG.md`

**Goal:** Sync user-facing documentation, then verify the full regression still passes.

- [ ] **Step 1: Audit README.md for setup duplication**

```bash
grep -n "installDist\|fixthis setup\|Quick Start" README.md 2>/dev/null | head -10
```

If README has its own Quick Start that mirrors AGENTS.md, update it the same way (wrapper as primary, manual as fallback). If it does not (likely — `AGENTS.md` is the canonical entry per the parent feature pattern), skip this step.

If a sync is needed, follow Task 2 Step 2's pattern. Only the Quick Start block changes; everything else preserved.

- [ ] **Step 2: Add CHANGELOG entry**

Edit `CHANGELOG.md`. Find the open Unreleased section. Add:

```markdown
- Added: `./scripts/bootstrap-mcp.sh --package <applicationId>` — single command that builds `:fixthis-cli` and `:fixthis-mcp` installDist and registers the MCP server with Claude Code and Codex. Replaces the two-step manual flow in `AGENTS.md`. Manual setup remains documented for Windows users.
```

Match the surrounding `Added: / Improved: / Fixed:` convention. If the recent staleness follow-ups CHANGELOG entry is the format reference, mirror it.

- [ ] **Step 3: Full 5-module regression**

```bash
./gradlew :fixthis-compose-core:test \
          :fixthis-cli:test \
          :fixthis-mcp:test \
          :fixthis-compose-sidekick:testDebugUnitTest \
          :fixthis-gradle-plugin:test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL. Test count unchanged from baseline (this plan adds no Kotlin code).

- [ ] **Step 4: Commit**

```bash
git add CHANGELOG.md $(git diff --name-only README.md 2>/dev/null | head -1)
git commit -m "$(cat <<'EOF'
docs: changelog for bootstrap-mcp.sh wrapper

Adds the single-command bootstrap entry to Unreleased and (if applicable)
syncs README Quick Start with AGENTS.md.

Task: 3
Risk: low
EOF
)"
```

If `git diff --name-only README.md` returns empty (README untouched), the bash substitution above passes only `CHANGELOG.md` to `git add` — which is correct.

---

## Out of scope (intentionally deferred)

- `fixthis doctor` post-bootstrap call (spec §3 / §10).
- Auto-detection of `--package` from `sample/build.gradle.kts`.
- Windows shell port.
- Auto-restart of running Claude Code / Codex processes.

## Risk register

- **R1: Wrong cwd assumption** — script `cd "$(dirname "$0")/.."`s before any work; moving the script breaks it loudly.
- **R2: Stale gradle cache** — Gradle is incremental; if `installDist` reports UP-TO-DATE but the binary is broken, that is a Gradle-level concern, not the wrapper's. Manual fallback (delete `fixthis-cli/build`) recovers.
- **R3: AGENTS.md / README diverge** — Task 3 Step 1 explicitly audits.

## Self-review checklist (after implementation)

- `scripts/bootstrap-mcp.sh` is executable (`ls -l` shows `+x`).
- `bash -n scripts/bootstrap-mcp.sh` passes.
- `./scripts/bootstrap-mcp.sh` (no args) prints usage + exits 2.
- `./scripts/bootstrap-mcp.sh --package <id> --dry-run` exits 0 and prints both gradle and `fixthis setup` dry-run output.
- `AGENTS.md` Quick Start is one bash block; "Manual setup" sub-section retains the two-step form.
- 5-module regression unchanged.
