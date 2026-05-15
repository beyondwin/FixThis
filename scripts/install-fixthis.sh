#!/usr/bin/env bash
# Install the FixThis CLI/MCP GitHub Release package.
set -euo pipefail

print_usage() {
    cat <<'USAGE'
Usage: ./scripts/install-fixthis.sh [--version <vX.Y.Z>|latest] [--repo owner/name]
                                    [--install-dir <dir>] [--bin-dir <dir>]
                                    [--archive <path>] [--init]
                                    [--target claude|codex|all]
                                    [--package <applicationId>] [--project-dir <dir>]

Default install:
  curl -fsSL https://raw.githubusercontent.com/beyondwin/FixThis/main/scripts/install-fixthis.sh | bash

Agent-first setup from an Android app repo:
  scripts/install-fixthis.sh --version v0.2.3 --init --target codex --project-dir .
USAGE
}

REPO="beyondwin/FixThis"
VERSION="latest"
INSTALL_DIR="${FIXTHIS_INSTALL_DIR:-$HOME/.local/share/fixthis}"
BIN_DIR="${FIXTHIS_BIN_DIR:-$HOME/.local/bin}"
ARCHIVE=""
RUN_INIT=0
TARGET="all"
PACKAGE=""
PROJECT_DIR="."

while [[ $# -gt 0 ]]; do
    case "$1" in
        --repo)
            [[ $# -ge 2 ]] || { echo "[install] --repo requires a value" >&2; exit 2; }
            REPO="$2"; shift 2 ;;
        --version)
            [[ $# -ge 2 ]] || { echo "[install] --version requires a value" >&2; exit 2; }
            VERSION="$2"; shift 2 ;;
        --install-dir)
            [[ $# -ge 2 ]] || { echo "[install] --install-dir requires a value" >&2; exit 2; }
            INSTALL_DIR="$2"; shift 2 ;;
        --bin-dir)
            [[ $# -ge 2 ]] || { echo "[install] --bin-dir requires a value" >&2; exit 2; }
            BIN_DIR="$2"; shift 2 ;;
        --archive)
            [[ $# -ge 2 ]] || { echo "[install] --archive requires a value" >&2; exit 2; }
            ARCHIVE="$2"; shift 2 ;;
        --init)
            RUN_INIT=1; shift ;;
        --target)
            [[ $# -ge 2 ]] || { echo "[install] --target requires a value" >&2; exit 2; }
            TARGET="$2"; shift 2 ;;
        --package)
            [[ $# -ge 2 ]] || { echo "[install] --package requires a value" >&2; exit 2; }
            PACKAGE="$2"; shift 2 ;;
        --project-dir)
            [[ $# -ge 2 ]] || { echo "[install] --project-dir requires a value" >&2; exit 2; }
            PROJECT_DIR="$2"; shift 2 ;;
        -h|--help)
            print_usage; exit 0 ;;
        *)
            echo "[install] unknown flag: $1" >&2
            print_usage >&2
            exit 2 ;;
    esac
done

case "$TARGET" in
    claude|codex|all) ;;
    *) echo "[install] --target must be claude, codex, or all" >&2; exit 2 ;;
esac

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "[install] required command not found: $1" >&2
        exit 1
    fi
}

require_command tar
require_command mkdir
require_command ln

WORK_DIR="$(mktemp -d)"
cleanup() {
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT

if [[ -z "$ARCHIVE" ]]; then
    require_command curl
    if [[ "$VERSION" == "latest" ]]; then
        VERSION="$(
            curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" |
                sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' |
                head -n 1
        )"
    fi
    if [[ -z "$VERSION" ]]; then
        echo "[install] could not resolve latest release for $REPO" >&2
        exit 1
    fi
    ARCHIVE="$WORK_DIR/fixthis-cli-mcp-$VERSION.tar.gz"
    curl -fL \
        "https://github.com/$REPO/releases/download/$VERSION/fixthis-cli-mcp-$VERSION.tar.gz" \
        -o "$ARCHIVE"
fi

mkdir -p "$WORK_DIR/extract"
tar -xzf "$ARCHIVE" -C "$WORK_DIR/extract"

PACKAGE_ROOT="$(find "$WORK_DIR/extract" -maxdepth 1 -type d -name 'fixthis-cli-mcp-*' | head -n 1)"
if [[ -z "$PACKAGE_ROOT" ]]; then
    echo "[install] archive does not contain a fixthis-cli-mcp-* directory" >&2
    exit 1
fi
if [[ ! -x "$PACKAGE_ROOT/fixthis/bin/fixthis" ]]; then
    echo "[install] archive is missing fixthis/bin/fixthis" >&2
    exit 1
fi
if [[ ! -x "$PACKAGE_ROOT/fixthis-mcp/bin/fixthis-mcp" ]]; then
    echo "[install] archive is missing fixthis-mcp/bin/fixthis-mcp" >&2
    exit 1
fi

INSTALLED_VERSION="$(basename "$PACKAGE_ROOT" | sed 's/^fixthis-cli-mcp-//')"
DEST_DIR="$INSTALL_DIR/$INSTALLED_VERSION"
rm -rf "$DEST_DIR"
mkdir -p "$INSTALL_DIR"
cp -R "$PACKAGE_ROOT" "$DEST_DIR"
ln -sfn "$DEST_DIR" "$INSTALL_DIR/current"

mkdir -p "$BIN_DIR"
ln -sfn "$INSTALL_DIR/current/fixthis/bin/fixthis" "$BIN_DIR/fixthis"

echo "[install] installed FixThis $INSTALLED_VERSION at $DEST_DIR"
echo "[install] linked $BIN_DIR/fixthis"

if [[ "$RUN_INIT" -eq 1 ]]; then
    INIT_ARGS=("init" "--target" "$TARGET" "--project-dir" "$PROJECT_DIR")
    if [[ -n "$PACKAGE" ]]; then
        INIT_ARGS+=("--package" "$PACKAGE")
    fi
    "$BIN_DIR/fixthis" "${INIT_ARGS[@]}"
fi
