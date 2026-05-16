#!/usr/bin/env bash
# scripts/check-docs-cli-surface.sh
# Verifies that every `fixthis <subcommand>` invocation referenced in
# README.md / AGENTS.md / CLAUDE.md / MCP.md / docs/getting-started/agent-install-snippet.md
# matches a real subcommand and uses currently-valid flags.
set -euo pipefail

DOCS=(
    "README.md"
    "AGENTS.md"
    "CLAUDE.md"
    "MCP.md"
    "docs/getting-started/agent-install-snippet.md"
)

# Build the CLI if not present.
CLI_BIN="fixthis-cli/build/install/fixthis/bin/fixthis"
if [ ! -x "$CLI_BIN" ]; then
    ./gradlew :fixthis-cli:installDist --quiet
fi

# Extract subcommand names.
SUBCOMMANDS=$("$CLI_BIN" --help | awk '/^Commands:/ { found=1; next } found && /^[[:space:]]+[a-z]/ { print $1 }')

# For each doc, find `fixthis <token>` patterns.
EXIT=0
for doc in "${DOCS[@]}"; do
    if [ ! -f "$doc" ]; then
        echo "warn: $doc not found, skipping"
        continue
    fi
    while IFS= read -r token; do
        case "$token" in
            -*|install-agent|setup|init|run|doctor|status|mcp|console|clean|version)
                ;;
            *)
                if ! echo "$SUBCOMMANDS" | grep -qx "$token"; then
                    echo "$doc: unknown subcommand 'fixthis $token'"
                    EXIT=1
                fi
                ;;
        esac
    done < <(grep -oE 'fixthis [a-z][a-z-]+' "$doc" | awk '{print $2}' | sort -u)
done

# For each --flag mentioned in `fixthis <sub> --flag`, verify it exists in `--help`.
for doc in "${DOCS[@]}"; do
    [ -f "$doc" ] || continue
    while IFS= read -r line; do
        sub=$(echo "$line" | awk '{print $2}')
        flag=$(echo "$line" | awk '{print $3}')
        if echo "$SUBCOMMANDS" | grep -qx "$sub"; then
            if ! "$CLI_BIN" "$sub" --help 2>/dev/null | grep -qE "(^|\s)$flag(=|,| )"; then
                echo "$doc: '$sub' has no flag '$flag'"
                EXIT=1
            fi
        fi
    done < <(grep -oE 'fixthis [a-z][a-z-]+ --[a-z][a-z-]+' "$doc" | sort -u)
done

exit "$EXIT"
