#!/usr/bin/env bash
# Launches the FixThis console with --console-assets-dir (so JS edits hot-reload
# from source instead of the packaged JAR), parses the consoleUrl out of the
# CLI's JSON output, and opens it in the default browser.
#
# Usage:
#   scripts/fixthis-console-dev.sh                     # uses default sample package
#   scripts/fixthis-console-dev.sh com.your.app        # override package
#
# Stop with Ctrl+C. Re-running this script will kill any stale `fixthis console`
# process before starting a new one.

set -euo pipefail

cd "$(dirname "$0")/.."

PACKAGE="${1:-io.beyondwin.fixthis.sample}"
CLI=fixthis-cli/build/install/fixthis/bin/fixthis
ASSETS_DIR="$PWD/fixthis-mcp/src/main/resources/console"

if [ ! -x "$CLI" ]; then
  echo "CLI not built. Run: ./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist" >&2
  exit 1
fi
if [ ! -d "$ASSETS_DIR" ]; then
  echo "Console assets dir missing: $ASSETS_DIR" >&2
  exit 1
fi

pkill -f "fixthis console" >/dev/null 2>&1 || true
sleep 0.3

echo "Starting fixthis console for $PACKAGE…"

# Pipe output through a filter that detects the consoleUrl on the first match
# and opens it in the default browser, then keeps streaming output through.
"$CLI" console \
  --package "$PACKAGE" \
  --console-assets-dir "$ASSETS_DIR" 2>&1 | \
awk '
  BEGIN { opened = 0 }
  {
    print
    fflush()
    if (!opened && match($0, /"consoleUrl"[[:space:]]*:[[:space:]]*"[^"]+"/)) {
      url = substr($0, RSTART, RLENGTH)
      sub(/.*"consoleUrl"[[:space:]]*:[[:space:]]*"/, "", url)
      sub(/"$/, "", url)
      printf("→ Opening %s\n", url)
      fflush()
      system("open \"" url "\"")
      opened = 1
    }
  }
'
