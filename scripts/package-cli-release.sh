#!/usr/bin/env bash
# Build a GitHub Release package containing the FixThis CLI and MCP server.
set -euo pipefail

print_usage() {
    cat <<'USAGE'
Usage: ./scripts/package-cli-release.sh --version <vX.Y.Z> [--out-dir <dir>] [--skip-build]

Creates build/release/fixthis-cli-mcp-<version>.tar.gz with this layout:
  fixthis-cli-mcp-<version>/
    fixthis/
    fixthis-mcp/

The sibling layout is intentional: the fixthis CLI can locate fixthis-mcp
beside its own installation when agents run `fixthis init`.
USAGE
}

cd "$(dirname "$0")/.."

VERSION=""
OUT_DIR="build/release"
SKIP_BUILD=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --version)
            [[ $# -ge 2 ]] || { echo "[package] --version requires a value" >&2; exit 2; }
            VERSION="$2"; shift 2 ;;
        --out-dir)
            [[ $# -ge 2 ]] || { echo "[package] --out-dir requires a value" >&2; exit 2; }
            OUT_DIR="$2"; shift 2 ;;
        --skip-build)
            SKIP_BUILD=1; shift ;;
        -h|--help)
            print_usage; exit 0 ;;
        *)
            echo "[package] unknown flag: $1" >&2
            print_usage >&2
            exit 2 ;;
    esac
done

if [[ -z "$VERSION" ]]; then
    if [[ -f gradle.properties ]]; then
        VERSION="$(sed -n 's/^FIXTHIS_VERSION=//p' gradle.properties | head -n 1)"
    fi
fi

if [[ -z "$VERSION" ]]; then
    echo "[package] --version is required when FIXTHIS_VERSION is unavailable" >&2
    exit 2
fi

if [[ "$SKIP_BUILD" -eq 0 ]]; then
    ./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist --no-daemon
fi

CLI_DIR="fixthis-cli/build/install/fixthis"
MCP_DIR="fixthis-mcp/build/install/fixthis-mcp"
if [[ ! -x "$CLI_DIR/bin/fixthis" ]]; then
    echo "[package] missing CLI executable at $CLI_DIR/bin/fixthis" >&2
    exit 1
fi
if [[ ! -x "$MCP_DIR/bin/fixthis-mcp" ]]; then
    echo "[package] missing MCP executable at $MCP_DIR/bin/fixthis-mcp" >&2
    exit 1
fi

PACKAGE_NAME="fixthis-cli-mcp-$VERSION"
STAGING_DIR="$OUT_DIR/staging/$PACKAGE_NAME"
ARCHIVE="$OUT_DIR/$PACKAGE_NAME.tar.gz"

rm -rf "$STAGING_DIR" "$ARCHIVE"
mkdir -p "$STAGING_DIR" "$OUT_DIR"
cp -R "$CLI_DIR" "$STAGING_DIR/fixthis"
cp -R "$MCP_DIR" "$STAGING_DIR/fixthis-mcp"
printf '%s\n' "$VERSION" > "$STAGING_DIR/VERSION"
cat > "$STAGING_DIR/README.txt" <<'README'
FixThis CLI/MCP release package

Add fixthis/bin to PATH, then run:

  fixthis init --target codex

or:

  fixthis init --target claude

The fixthis CLI and fixthis-mcp server must stay beside each other in this
directory layout.
README

tar -czf "$ARCHIVE" -C "$OUT_DIR/staging" "$PACKAGE_NAME"
echo "$ARCHIVE"
