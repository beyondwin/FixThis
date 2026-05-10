#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Fixed dev port so the browser tab stays bookmarkable across restarts.
# Override with FIXTHIS_CONSOLE_PORT or --port.
CONSOLE_PORT="${FIXTHIS_CONSOLE_PORT:-9876}"
WITH_APP=false
DRY_RUN=false
NO_OPEN=false
EXTRA_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --with-app) WITH_APP=true; shift ;;
    --dry-run)  DRY_RUN=true;  shift ;;
    --no-open)  NO_OPEN=true;  shift ;;
    --port)     CONSOLE_PORT="$2"; shift 2 ;;
    *)          EXTRA_ARGS+=("$1"); shift ;;
  esac
done

run() {
  if $DRY_RUN; then
    echo "DRY: $*"
  else
    "$@"
  fi
}

echo "→ Killing existing fixthis-mcp / fixthis-cli console processes…"
run pkill -f 'fixthis-mcp.*--console' 2>/dev/null || true
run pkill -f 'fixthis-cli.*console'   2>/dev/null || true

# Free the chosen port if anything else is holding it (stray dev server,
# crashed Kotlin process, unrelated tool, …). TERM first, then KILL.
port_holders="$(lsof -ti tcp:"$CONSOLE_PORT" 2>/dev/null || true)"
if [[ -n "$port_holders" ]]; then
  echo "→ Freeing port $CONSOLE_PORT (PIDs: $port_holders)…"
  run kill $port_holders 2>/dev/null || true
  sleep 0.5
  port_holders="$(lsof -ti tcp:"$CONSOLE_PORT" 2>/dev/null || true)"
  if [[ -n "$port_holders" ]]; then
    run kill -9 $port_holders 2>/dev/null || true
  fi
fi

# 명명된 screen 세션 정리 (이름 패턴: fixthis-console-*)
if command -v screen >/dev/null 2>&1; then
  (screen -ls 2>/dev/null \
    | awk '/[0-9]+\.fixthis-console-[0-9]+/ {print $1}' \
    | while IFS= read -r session; do
        [[ -n "$session" ]] && run screen -S "$session" -X quit 2>/dev/null || true
      done) || true
fi

echo "→ Building distributions (incremental gradle)…"
cd "$ROOT"
run ./gradlew :fixthis-mcp:installDist :fixthis-cli:installDist

if $WITH_APP; then
  echo "→ Rebuilding + reinstalling sample APK…"
  run ./gradlew :app:assembleDebug :app:installDebug
fi

MCP_BIN="$ROOT/fixthis-mcp/build/install/fixthis-mcp/bin/fixthis-mcp"
if [[ ! -x "$MCP_BIN" ]] && ! $DRY_RUN; then
  echo "✗ fixthis-mcp binary not found at $MCP_BIN" >&2
  exit 1
fi

# Cache-bust query forces /index.html to revalidate after a server restart so
# the browser does not stick on the previous build's HTML/JS.
CONSOLE_URL="http://localhost:${CONSOLE_PORT}/?cb=$(date +%s)"

if ! $NO_OPEN && ! $DRY_RUN; then
  (
    for _ in $(seq 1 100); do
      if nc -z localhost "$CONSOLE_PORT" 2>/dev/null; then
        open "$CONSOLE_URL" 2>/dev/null || true
        exit 0
      fi
      sleep 0.1
    done
    echo "⚠️  Console did not start listening on :$CONSOLE_PORT within 10s; not opening browser." >&2
  ) &
fi

echo "→ Starting console on :$CONSOLE_PORT (Ctrl-C to exit)…"
echo "   URL: $CONSOLE_URL"
exec "$MCP_BIN" \
  --console \
  --console-port "$CONSOLE_PORT" \
  --project-dir "$ROOT" \
  --package io.beyondwin.fixthis.sample \
  "${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}"
