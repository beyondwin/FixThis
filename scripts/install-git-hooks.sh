#!/usr/bin/env bash
# Install tracked FixThis git hooks for this checkout.
set -euo pipefail

cd "$(dirname "$0")/.."

if ! git rev-parse --git-dir >/dev/null 2>&1; then
    echo "[hooks] not inside a git repository" >&2
    exit 1
fi

git config core.hooksPath .githooks
echo "[hooks] configured core.hooksPath=.githooks"
echo "[hooks] pre-push will run npm run prepush"
