#!/usr/bin/env bash
# Mirror the required GitHub Actions gates before a push.
set -euo pipefail

print_usage() {
    cat <<'USAGE'
Usage: scripts/verify-ci-local.sh [--changed-only|--fast|--full] [--base <ref>] [--list]

Modes:
  --changed-only  Run fast checks, then run Gradle only for Kotlin/Android/Gradle changes. Default.
  --fast          Run quick CI gates only.
  --full          Run quick CI gates plus the full Gradle verification command.

Options:
  --base <ref>    Base ref for committed whitespace checks. Defaults to the upstream merge-base.
  --list          Print the commands that would run without executing them.
  -h, --help      Show this help.

Environment:
  FIXTHIS_VERIFY_BASE           Test/override base ref.
  FIXTHIS_VERIFY_CHANGED_FILES  Test/override changed file list, newline-separated.
USAGE
}

cd "$(dirname "$0")/.."

MODE="changed-only"
BASE_OVERRIDE="${FIXTHIS_VERIFY_BASE:-}"
LIST_ONLY=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --changed-only)
            MODE="changed-only"; shift ;;
        --fast)
            MODE="fast"; shift ;;
        --full)
            MODE="full"; shift ;;
        --base)
            [[ $# -ge 2 ]] || { echo "[verify-ci-local] --base requires a value" >&2; exit 2; }
            BASE_OVERRIDE="$2"; shift 2 ;;
        --list)
            LIST_ONLY=1; shift ;;
        -h|--help)
            print_usage; exit 0 ;;
        *)
            echo "[verify-ci-local] unknown flag: $1" >&2
            print_usage >&2
            exit 2 ;;
    esac
done

resolve_base() {
    if [[ -n "$BASE_OVERRIDE" ]]; then
        printf '%s\n' "$BASE_OVERRIDE"
        return
    fi

    local upstream
    if upstream="$(git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}' 2>/dev/null)"; then
        git merge-base HEAD "$upstream"
        return
    fi

    if git rev-parse --verify HEAD^ >/dev/null 2>&1; then
        git rev-parse HEAD^
        return
    fi

    git rev-parse HEAD
}

changed_files() {
    if [[ -n "${FIXTHIS_VERIFY_CHANGED_FILES:-}" ]]; then
        printf '%s' "$FIXTHIS_VERIFY_CHANGED_FILES"
        return
    fi

    {
        git diff --name-only "$BASE..HEAD"
        git diff --name-only --cached
        git diff --name-only
    } | sed '/^$/d' | sort -u
}

needs_gradle() {
    local files="$1"
    grep -Eq '(^|/)(build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties|gradlew)$|^gradle/|^(app|sample|fixthis-(compose-core|compose-sidekick|gradle-plugin|cli|mcp))/src/' <<<"$files"
}

run_step() {
    local command="$1"
    if [[ "$LIST_ONLY" -eq 1 ]]; then
        printf 'RUN %s\n' "$command"
        return
    fi

    printf '\n[verify-ci-local] %s\n' "$command"
    bash -lc "$command"
}

BASE="$(resolve_base)"
CHANGED_FILES="$(changed_files)"
RUN_GRADLE=0

case "$MODE" in
    fast)
        RUN_GRADLE=0 ;;
    full)
        RUN_GRADLE=1 ;;
    changed-only)
        if needs_gradle "$CHANGED_FILES"; then
            RUN_GRADLE=1
        fi ;;
    *)
        echo "[verify-ci-local] unsupported mode: $MODE" >&2
        exit 2 ;;
esac

run_step "node scripts/check-doc-consistency.mjs"
run_step "node scripts/check-release-readiness.mjs"
run_step "npm run docs:agent-bootstrap:test"
run_step "npm run first-run:smoke:test"
run_step "npm run detekt:baseline:check"
run_step "npm run checks:observation:test"
run_step "node scripts/build-console-assets.mjs --check"
run_step "node --check fixthis-mcp/src/main/resources/console/app.js"
run_step "npm run console:test:all"
run_step "node --test scripts/fixthis-smoke-test.mjs"
run_step "npm run release:package:test"
run_step "npm run perf:test"
if [[ "$RUN_GRADLE" -eq 1 ]]; then
    run_step "./gradlew spotlessCheck detekt :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest :fixthis-gradle-plugin:test :app:assembleDebug :fixthis-cli:installDist :fixthis-mcp:installDist --no-daemon"
fi
run_step "git diff --check ${BASE}..HEAD"
run_step "git diff --check"

if [[ "$LIST_ONLY" -eq 0 ]]; then
    printf '\n[verify-ci-local] complete\n'
fi
