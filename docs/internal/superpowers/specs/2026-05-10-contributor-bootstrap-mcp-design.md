# Contributor Bootstrap MCP — Design

**Date:** 2026-05-10
**Status:** Approved (pending implementation)
**Owner:** kws
**Related plan:** `docs/superpowers/plans/2026-05-10-contributor-bootstrap-mcp.md`

---

## 1. Problem

After cloning the repo, a new contributor who wants Claude Code / Codex to see the FixThis MCP must run two manual commands in this order (per `AGENTS.md` Quick Start):

```bash
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist
fixthis-cli/build/install/fixthis/bin/fixthis setup \
  --package <applicationId> --write --target all
```

Both steps are necessary because `fixthis setup`'s `McpExecutableLocator` (`SetupCommand.kt:187`) searches `fixthis-mcp/build/install/...`, which is gitignored. Skipping the first step makes setup fail with a binary-not-found message; users routinely hit that on first run. Beyond the inconvenience, the per-machine absolute paths written into `.claude/settings.json` (also gitignored) mean **no per-project commit can replace this** — every contributor has to do it themselves.

Symptoms observed: the `.claude/settings.json` written by `ClaudeConfigWriter.kt` contains absolute `command`, `--project-dir`, and `ANDROID_HOME` paths; `~/.claude.json` only registers user-global MCPs (`pencil` here, not `fixthis`); a fresh Claude Code session in this repo today does not have `mcp__fixthis_*` tools — the proximate cause of the recent claim/resolve regression.

## 2. Goals

- A single command (`./scripts/bootstrap-mcp.sh --package <appId>`) replaces the current two manual steps.
- The script is a thin wrapper. It performs build + setup in order and surfaces clear errors on failure.
- `AGENTS.md` Quick Start collapses to one line, with the manual two-step form preserved under a "Manual setup" sub-section as reference.
- No behavior change to `fixthis setup` itself, and no new flags on it.

## 3. Non-goals

- Automatic package-id detection from `sample/build.gradle.kts` or git-tracked metadata.
- Post-setup smoke verification (e.g., `fixthis doctor`). Intentionally deferred — the user explicitly chose the minimal scope.
- Windows support. Bash is the only target; Windows contributors continue to use the two manual commands or run them under WSL.
- Pre-commit / Git hook integration. The script is invoked manually.
- Caching, parallelism, or build-graph optimizations. Gradle handles incremental builds.
- Replacing the two-step manual path entirely — it remains supported.

## 4. Architecture

Single Bash script at `scripts/bootstrap-mcp.sh`. ~30-40 lines. No subcommands, no plugin model.

```
$ ./scripts/bootstrap-mcp.sh --package io.beyondwin.fixthis.sample
[bootstrap] cwd → /Users/kws/source/android/FixThis
[bootstrap] step 1/2 — building :fixthis-cli and :fixthis-mcp installDist
> Task :fixthis-cli:installDist UP-TO-DATE
> Task :fixthis-mcp:installDist UP-TO-DATE
[bootstrap] step 2/2 — running fixthis setup --target all
Wrote .claude/settings.json (project)
Wrote ~/.codex/config.toml (user)
[bootstrap] complete — restart Claude Code or Codex to pick up the new MCP server
```

### 4.1 Script behavior

```bash
#!/usr/bin/env bash
set -euo pipefail

# 1) cd to repo root (so gradle wrapper resolves regardless of where the user invoked the script)
cd "$(dirname "$0")/.."

# 2) parse flags via case
PACKAGE=""
TARGET="all"
EXTRA_FLAGS=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --package) PACKAGE="$2"; shift 2 ;;
        --target) TARGET="$2"; shift 2 ;;
        --dry-run) EXTRA_FLAGS+=("--dry-run"); shift ;;
        -h|--help) print_usage; exit 0 ;;
        *) echo "[bootstrap] unknown flag: $1" >&2; exit 2 ;;
    esac
done

# 3) require --package
[[ -z "$PACKAGE" ]] && { echo "[bootstrap] --package is required"; print_usage; exit 2; }

# 4) build
echo "[bootstrap] step 1/2 — building :fixthis-cli and :fixthis-mcp installDist"
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist

# 5) setup
echo "[bootstrap] step 2/2 — running fixthis setup --target $TARGET"
./fixthis-cli/build/install/fixthis/bin/fixthis setup \
    --package "$PACKAGE" --write --target "$TARGET" "${EXTRA_FLAGS[@]}"

echo "[bootstrap] complete — restart Claude Code or Codex to pick up the new MCP server"
```

`print_usage` prints a short three-line help text. No external dependencies beyond `bash`, `./gradlew`, and the `fixthis` binary that step 4 produces.

### 4.2 Failure surface

- **Wrong cwd:** the `cd` guard in step 1 makes the script invariant to invocation directory. If the cd itself fails (e.g., script moved to a non-relative location), `set -e` exits cleanly.
- **Gradle build failure:** `set -e` propagates Gradle's non-zero exit. The user sees Gradle's own error output (compile errors, missing JDK, etc.).
- **Setup binary missing:** if step 4 succeeded, the binary exists. If for some reason it does not (e.g., Gradle deleted it), the explicit binary path produces a "no such file" error from the shell, which is clearer than a `command -v` fallback.
- **Setup failure:** `fixthis setup` already prints structured errors (e.g., "Run `./gradlew :fixthis-mcp:installDist`…"). The wrapper does not add to them.
- **Unknown flag:** explicit `[bootstrap] unknown flag: <flag>` + exit 2.

### 4.3 Documentation alignment

- `AGENTS.md` Quick Start replaces:
  ```diff
  - ./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist
  - fixthis-cli/build/install/fixthis/bin/fixthis setup --package <id> --write --target all
  + ./scripts/bootstrap-mcp.sh --package <applicationId>
  ```
- A new "Manual setup" sub-section preserves the two-step path verbatim, marked as "for users who prefer not to use the wrapper or are on Windows."
- `README.md`: if there is currently no Quick Start section that mentions setup, do not add one. If there is one that mirrors `AGENTS.md`, sync it. (Per `AGENTS.md` already being the canonical entry point, README likely needs no change.)

## 5. Components

| File | Change kind |
|---|---|
| `scripts/bootstrap-mcp.sh` | Created. ~30-40 LoC. Executable (`chmod +x`). |
| `AGENTS.md` | Quick Start collapsed to one line; "Manual setup" sub-section added. |
| `README.md` | Audit only — sync if it currently mirrors AGENTS.md Quick Start; otherwise no change. |

## 6. Testing strategy

The script is too thin to merit a custom test harness. Verification is two checks:

1. **Bash syntax:** `bash -n scripts/bootstrap-mcp.sh` exits 0.
2. **Manual end-to-end:** in a clean checkout (or after `rm -rf fixthis-cli/build fixthis-mcp/build`), running `./scripts/bootstrap-mcp.sh --package io.beyondwin.fixthis.sample` succeeds and produces `.claude/settings.json` (or `~/.codex/config.toml`).

Optional, only if `shellcheck` is in the contributor's PATH: `shellcheck scripts/bootstrap-mcp.sh`. The plan does not require this — `shellcheck` is not installed by default on macOS.

No automated CI test for the script — the existing 5-module gradle test command does not exercise shell scripts. Adding such a test for one ~30-line file is YAGNI.

## 7. Acceptance criteria

The follow-up is done when **all** of:

1. `scripts/bootstrap-mcp.sh` exists, is executable, and `bash -n` passes.
2. `./scripts/bootstrap-mcp.sh --package io.beyondwin.fixthis.sample` succeeds in a clean checkout (no pre-existing `build/install/...` directories) and writes `.claude/settings.json` with the expected `mcpServers.fixthis` entry.
3. `./scripts/bootstrap-mcp.sh` (no arguments) prints usage and exits with code 2.
4. `./scripts/bootstrap-mcp.sh --bogus` exits with code 2 and a `[bootstrap] unknown flag` message.
5. `AGENTS.md` Quick Start uses the wrapper as the primary path; manual two-step preserved as fallback.
6. Full 5-module regression continues to pass (current main baseline `tests=631`).

## 8. Compatibility

- The wrapper is **additive**. Users who have `./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist && fixthis setup …` muscle memory continue to work.
- No change to `fixthis setup` flags or behavior.
- No change to file formats produced (`.claude/settings.json`, `~/.codex/config.toml`).
- No change to the `fixthis` CLI surface.

## 9. Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Contributor on Windows runs the script under unsupported shell | low | low | `set -euo pipefail` exits non-zero; `AGENTS.md` Manual setup section calls out the alternative. |
| Script silently picks up a stale `fixthis` binary because `installDist` was UP-TO-DATE but cached state is corrupt | very low | medium | Spec §6 manual verification asserts the resulting `.claude/settings.json` content; corrupt state surfaces at first MCP call. |
| `cd` to repo root assumption breaks if `scripts/` is moved | very low | low | Path is hardcoded relative to script (`$(dirname "$0")/..`); moving the file would break it explicitly. |
| Future `fixthis setup` flag additions silently bypassed by wrapper | low | low | `--dry-run` is the only flag preserved today; new flags require a wrapper update. Acceptable maintenance overhead given small scope. |

## 10. Future work (intentionally deferred)

- `fixthis doctor` post-bootstrap call to verify the MCP server is reachable.
- Auto-detect `--package` from `sample/build.gradle.kts` (`namespace = "io.beyondwin.fixthis.sample"` is parseable). Spares one CLI argument; minor convenience.
- Cross-shell wrapper (`bootstrap-mcp.cmd` for Windows). Open if a contributor asks.
- Optional `--restart-claude-code` flag that sends a `kill -USR1` (or platform-equivalent) to running Claude Code processes so the user does not need to manually restart.

## 11. Open questions

1. Should `--target all` remain the default, or change to `--target claude` (most contributors here will be Claude Code users)? — **Decision:** keep `--target all` for parity with current `AGENTS.md` Quick Start. A contributor on Codex still benefits from the wrapper.
2. Should the wrapper accept positional `<applicationId>` to drop the `--package` flag? — **Decision:** no. `--package` is explicit and matches `fixthis setup`'s flag name. Reduces cognitive switching.
3. Should the wrapper fail loudly if `.claude/settings.json` already has a `fixthis` entry (avoid silent overwrite)? — **Decision:** no. `fixthis setup --write` already merges idempotently; failing on re-run would be hostile to repeated bootstraps.
