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
    --package "$PACKAGE" --write --target "$TARGET" ${EXTRA_FLAGS[@]+"${EXTRA_FLAGS[@]}"}

echo "[bootstrap] complete — restart Claude Code or Codex to pick up the new MCP server"
