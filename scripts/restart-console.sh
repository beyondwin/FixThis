#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

WITH_APP=false
DRY_RUN=false
EXTRA_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --with-app) WITH_APP=true; shift ;;
    --dry-run)  DRY_RUN=true;  shift ;;
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

echo "→ Starting console (Ctrl-C to exit)…"
exec "$ROOT/fixthis-cli/build/install/fixthis/bin/fixthis" console \
  --package io.beyondwin.fixthis.sample \
  "${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}"
