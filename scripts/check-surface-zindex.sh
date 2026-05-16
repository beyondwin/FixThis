#!/usr/bin/env bash
set -euo pipefail

STYLES="fixthis-mcp/src/main/resources/console/styles.css"

violations=$(awk '
  function is_surface_root(line) {
    return line ~ /^[[:space:]]*\.(staleness-banner|canvas-blocked|session-boundary-sheet|preview-stale-badge|toast-container|status-pill|device-state|draft-lock-bar|canvas-stale)([[:space:]]*\{|[[:space:]]*$|\[)/
  }
  /^[[:space:]]*\./ && /\{/ && !is_surface_root($0) { in_surface = 0; sel = "" }
  is_surface_root($0) { in_surface = 1; sel = $0 }
  /^[[:space:]]*z-index:[[:space:]]*[0-9]/ {
    if (in_surface) print FILENAME ":" NR ":" sel " -> " $0
  }
  /\}/ && in_surface { in_surface = 0; sel = "" }
' "$STYLES" || true)

if [ -n "$violations" ]; then
  echo "Surface z-index must use var(--z-surface-*):"
  echo "$violations"
  exit 1
fi

echo "OK: all surface z-index values use tokens."
