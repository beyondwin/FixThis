#!/usr/bin/env bash
set -euo pipefail

PACKAGE_NAME="io.beyondwin.fixthis.sample"
HOST_ONLY="false"
NO_BUILD="false"
DEVICE_SERIAL="${ANDROID_SERIAL:-}"

usage() {
  cat <<'EOF'
Usage: scripts/fixthis-smoke.sh [--package APPLICATION_ID] [--host-only] [--no-build] [--device SERIAL]

Runs FixThis host and connected-device smoke checks and writes local reports to
.fixthis/smoke-reports/.

Options:
  --package APPLICATION_ID  Android application id to diagnose.
  --host-only              Build host artifacts, then skip connected checks.
  --no-build               Skip Gradle build/installDist checks.
  --device SERIAL          Select an adb device serial. Defaults to ANDROID_SERIAL.
  -h, --help               Show this help.
EOF
}

require_value() {
  local option="$1"
  local value="${2:-}"
  if [[ -z "${value}" || "${value}" == --* ]]; then
    echo "Missing value for ${option}" >&2
    exit 2
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --package)
      require_value "$1" "${2:-}"
      PACKAGE_NAME="$2"
      shift 2
      ;;
    --host-only)
      HOST_ONLY="true"
      shift
      ;;
    --no-build)
      NO_BUILD="true"
      shift
      ;;
    --device)
      require_value "$1" "${2:-}"
      DEVICE_SERIAL="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
REPORT_DIR="${ROOT_DIR}/.fixthis/smoke-reports"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "${REPORT_DIR}"
REPORT_ID="${TIMESTAMP}-pid$$-${RANDOM}${RANDOM}"
while [[ -e "${REPORT_DIR}/${REPORT_ID}.md" || -e "${REPORT_DIR}/${REPORT_ID}.json" ]]; do
  REPORT_ID="${TIMESTAMP}-pid$$-${RANDOM}${RANDOM}"
done
REPORT_MD="${REPORT_DIR}/${REPORT_ID}.md"
REPORT_JSON="${REPORT_DIR}/${REPORT_ID}.json"

RESULT="FAILED_INTERNAL"
EXIT_CODE=1
ACTIVE_SERIAL=""
ADB_BIN=""
ADB_OUTPUT=""
LOCK_LINES=""
STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

record() {
  printf '%s\n' "$1" | tee -a "${REPORT_MD}"
}

json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  value="${value//$'\r'/\\r}"
  value="${value//$'\t'/\\t}"
  printf '%s' "${value}"
}

write_json() {
  {
    printf '{\n'
    printf '  "startedAt": "%s",\n' "$(json_escape "${STARTED_AT}")"
    printf '  "finishedAt": "%s",\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '  "result": "%s",\n' "$(json_escape "${RESULT}")"
    printf '  "packageName": "%s",\n' "$(json_escape "${PACKAGE_NAME}")"
    printf '  "hostOnly": %s,\n' "${HOST_ONLY}"
    printf '  "noBuild": %s,\n' "${NO_BUILD}"
    printf '  "adb": "%s",\n' "$(json_escape "${ADB_BIN}")"
    printf '  "deviceSerial": "%s",\n' "$(json_escape "${ACTIVE_SERIAL:-${DEVICE_SERIAL}}")"
    printf '  "markdownReport": "%s"\n' "$(json_escape "${REPORT_MD}")"
    printf '}\n'
  } > "${REPORT_JSON}"
}

finish() {
  RESULT="$1"
  EXIT_CODE="$2"
  record ""
  record "- Result: ${RESULT}"
  record "- JSON report: ${REPORT_JSON}"
  write_json
  exit "${EXIT_CODE}"
}

resolve_adb() {
  if command -v adb >/dev/null 2>&1; then
    command -v adb
    return
  fi
  if [[ -n "${ANDROID_HOME:-}" && -x "${ANDROID_HOME}/platform-tools/adb" ]]; then
    printf '%s\n' "${ANDROID_HOME}/platform-tools/adb"
    return
  fi
  if [[ -n "${ANDROID_SDK_ROOT:-}" && -x "${ANDROID_SDK_ROOT}/platform-tools/adb" ]]; then
    printf '%s\n' "${ANDROID_SDK_ROOT}/platform-tools/adb"
    return
  fi
  if [[ -f "${ROOT_DIR}/local.properties" ]]; then
    local sdk_dir
    sdk_dir="$(sed -n 's/^sdk\.dir=//p' "${ROOT_DIR}/local.properties" | head -n 1)"
    if [[ -n "${sdk_dir}" && -x "${sdk_dir}/platform-tools/adb" ]]; then
      printf '%s\n' "${sdk_dir}/platform-tools/adb"
      return
    fi
  fi
}

adb_cmd() {
  if [[ -n "${ACTIVE_SERIAL}" ]]; then
    "${ADB_BIN}" -s "${ACTIVE_SERIAL}" "$@"
  else
    "${ADB_BIN}" "$@"
  fi
}

device_state_for() {
  local serial="$1"
  printf '%s\n' "${ADB_OUTPUT}" | awk -v serial="${serial}" 'NR > 1 && $1 == serial { print $2; exit }'
}

first_ready_serial() {
  printf '%s\n' "${ADB_OUTPUT}" | awk 'NR > 1 && $2 == "device" { print $1; exit }'
}

ready_device_count() {
  printf '%s\n' "${ADB_OUTPUT}" | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }'
}

has_state() {
  local state="$1"
  printf '%s\n' "${ADB_OUTPUT}" | awk -v state="${state}" 'NR > 1 && $2 == state { found = 1 } END { exit(found ? 0 : 1) }'
}

has_wireless_offline_device() {
  printf '%s\n' "${ADB_OUTPUT}" | awk '
    NR > 1 && $2 == "offline" && ($1 ~ /:[0-9]+$/ || $1 ~ /_adb-tls-connect\._tcp$/) { found = 1 }
    END { exit(found ? 0 : 1) }
  '
}

record_command_output() {
  local label="$1"
  shift
  record ""
  record "## ${label}"
  record '```text'
  "$@" 2>&1 | tee -a "${REPORT_MD}"
  local command_exit=${PIPESTATUS[0]}
  record '```'
  return "${command_exit}"
}

cd "${ROOT_DIR}"

JAVA_VERSION="$(java -version 2>&1 | head -n 1 || true)"
GRADLE_VERSION="$("./gradlew" --version 2>/dev/null | sed -n '/^Gradle /p' | head -n 1 || true)"
ADB_BIN="$(resolve_adb || true)"

record "# FixThis Smoke Report"
record ""
record "- Started: ${STARTED_AT}"
record "- Package: ${PACKAGE_NAME}"
record "- Host only: ${HOST_ONLY}"
record "- No build: ${NO_BUILD}"
record "- Device serial: ${DEVICE_SERIAL:-"(auto)"}"
record "- OS: $(uname -a)"
record "- Java: ${JAVA_VERSION:-"(not found)"}"
record "- Gradle: ${GRADLE_VERSION:-"(not found)"}"
record "- ANDROID_HOME: ${ANDROID_HOME:-"(unset)"}"
record "- ANDROID_SDK_ROOT: ${ANDROID_SDK_ROOT:-"(unset)"}"
record "- ADB: ${ADB_BIN:-"(not found)"}"

if [[ "${NO_BUILD}" != "true" ]]; then
  if ! record_command_output "Gradle host build" ./gradlew :app:assembleDebug :fixthis-cli:installDist :fixthis-mcp:installDist; then
    finish "FAILED_BUILD" 1
  fi
else
  record ""
  record "## Gradle host build"
  record "Skipped because --no-build was set."
fi

if [[ "${HOST_ONLY}" == "true" ]]; then
  finish "SKIPPED_HOST_ONLY" 0
fi

if [[ -z "${ADB_BIN}" ]]; then
  finish "SKIPPED_ADB_NOT_FOUND" 0
fi

ADB_OUTPUT="$("${ADB_BIN}" devices -l 2>&1 || true)"
record ""
record "## adb devices -l"
record '```text'
record "${ADB_OUTPUT}"
record '```'

if [[ -n "${DEVICE_SERIAL}" ]]; then
  DEVICE_STATE="$(device_state_for "${DEVICE_SERIAL}")"
  case "${DEVICE_STATE}" in
    device)
      ACTIVE_SERIAL="${DEVICE_SERIAL}"
      ;;
    unauthorized)
      ACTIVE_SERIAL="${DEVICE_SERIAL}"
      finish "SKIPPED_UNAUTHORIZED_DEVICE" 0
      ;;
    offline)
      ACTIVE_SERIAL="${DEVICE_SERIAL}"
      if [[ "${DEVICE_SERIAL}" == *._adb-tls-connect._tcp || "${DEVICE_SERIAL}" == *:* ]]; then
        finish "SKIPPED_WIRELESS_ADB_LOST" 0
      fi
      finish "SKIPPED_OFFLINE_DEVICE" 0
      ;;
    "")
      finish "SKIPPED_NO_DEVICE" 0
      ;;
    *)
      ACTIVE_SERIAL="${DEVICE_SERIAL}"
      finish "SKIPPED_NO_DEVICE" 0
      ;;
  esac
else
  READY_COUNT="$(ready_device_count)"
  if [[ "${READY_COUNT}" -eq 0 ]]; then
    if has_wireless_offline_device; then
      finish "SKIPPED_WIRELESS_ADB_LOST" 0
    elif has_state "unauthorized"; then
      finish "SKIPPED_UNAUTHORIZED_DEVICE" 0
    elif has_state "offline"; then
      finish "SKIPPED_OFFLINE_DEVICE" 0
    else
      finish "SKIPPED_NO_DEVICE" 0
    fi
  elif [[ "${READY_COUNT}" -gt 1 ]]; then
    finish "SKIPPED_MULTIPLE_DEVICES" 0
  else
    ACTIVE_SERIAL="$(first_ready_serial)"
  fi
fi

LOCK_OUTPUT="$(adb_cmd shell dumpsys window 2>&1 || true)"
LOCK_LINES="$(printf '%s\n' "${LOCK_OUTPUT}" | grep -E 'mDreamingLockscreen|mShowingLockscreen|isStatusBarKeyguard|mInputRestricted|Keyguard' | head -n 25 || true)"
record ""
record "## Lock state excerpt"
record '```text'
record "${LOCK_LINES:-"(no lockscreen signals found)"}"
record '```'

if printf '%s\n' "${LOCK_LINES}" | grep -Eq 'mDreamingLockscreen=true|mShowingLockscreen=true|isStatusBarKeyguard=true|mInputRestricted=true'; then
  finish "SKIPPED_LOCKED_DEVICE" 0
fi

if ! record_command_output "Install sample app" env ANDROID_SERIAL="${ACTIVE_SERIAL}" ./gradlew :app:installDebug; then
  finish "FAILED_INSTALL" 1
fi

if ! record_command_output "Launch sample app" adb_cmd shell monkey -p "${PACKAGE_NAME}" -c android.intent.category.LAUNCHER 1; then
  finish "FAILED_LAUNCH" 1
fi

if ! record_command_output "fixthis doctor" env ANDROID_SERIAL="${ACTIVE_SERIAL}" fixthis-cli/build/install/fixthis/bin/fixthis doctor --package "${PACKAGE_NAME}" --project-dir "${ROOT_DIR}"; then
  finish "FAILED_DOCTOR" 1
fi

finish "PASS" 0
