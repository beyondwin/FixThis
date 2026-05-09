#!/usr/bin/env bash
set -euo pipefail

PACKAGE_NAME="io.beyondwin.fixthis.sample"
HOST_ONLY="false"
NO_BUILD="false"
DEVICE_SERIAL="${ANDROID_SERIAL:-}"
CHECK_STALENESS="false"

usage() {
  cat <<'EOF'
Usage: scripts/fixthis-smoke.sh [--package APPLICATION_ID] [--host-only] [--no-build] [--device SERIAL] [--check-staleness]

Runs FixThis host and connected-device smoke checks and writes local reports to
.fixthis/smoke-reports/.

Options:
  --package APPLICATION_ID  Android application id to diagnose.
  --host-only              Build host artifacts, then skip connected checks.
  --no-build               Skip Gradle build/installDist checks.
  --device SERIAL          Select an adb device serial. Defaults to ANDROID_SERIAL.
  --check-staleness        After the standard smoke flow passes, run a real-device
                           round-trip that asserts fixthis_status install-staleness
                           transitions: clean -> stale (after touching a tracked
                           source) -> clean (after reinstall). Requires jq, adb,
                           and a connected authorized device.
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
    --check-staleness)
      CHECK_STALENESS="true"
      shift
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

if [[ "${CHECK_STALENESS}" == "true" ]]; then
  if [[ "${HOST_ONLY}" == "true" ]]; then
    echo "--check-staleness is incompatible with --host-only" >&2
    exit 2
  fi
  if [[ "${NO_BUILD}" == "true" ]]; then
    echo "--check-staleness is incompatible with --no-build (requires fresh installDist binaries)" >&2
    exit 2
  fi
fi

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
    printf '  "checkStaleness": %s,\n' "${CHECK_STALENESS}"
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

staleness_target_file() {
  printf '%s\n' "${ROOT_DIR}/sample/src/main/java/io/beyondwin/fixthis/sample/MainActivity.kt"
}

staleness_get_mtime() {
  local file="$1"
  if [[ "$(uname -s)" == "Darwin" ]]; then
    stat -f "%m" "${file}"
  else
    stat -c "%Y" "${file}"
  fi
}

staleness_set_mtime() {
  local file="$1"
  local epoch="$2"
  if [[ "$(uname -s)" == "Darwin" ]]; then
    touch -t "$(date -r "${epoch}" +%Y%m%d%H%M.%S)" "${file}"
  else
    touch -d "@${epoch}" "${file}"
  fi
}

staleness_bump_mtime_one_hour() {
  local file="$1"
  if [[ "$(uname -s)" == "Darwin" ]]; then
    touch -t "$(date -v+1H +%Y%m%d%H%M.%S)" "${file}"
  else
    touch -d "+1 hour" "${file}"
  fi
}

STALENESS_ORIGINAL_MTIME=""
STALENESS_TARGET=""

staleness_restore_mtime() {
  if [[ -n "${STALENESS_TARGET}" && -n "${STALENESS_ORIGINAL_MTIME}" && -f "${STALENESS_TARGET}" ]]; then
    staleness_set_mtime "${STALENESS_TARGET}" "${STALENESS_ORIGINAL_MTIME}" || true
  fi
}

call_mcp_status() {
  local mcp_bin="${ROOT_DIR}/fixthis-mcp/build/install/fixthis-mcp/bin/fixthis-mcp"
  if [[ ! -x "${mcp_bin}" ]]; then
    echo "[staleness] MCP binary not found at ${mcp_bin}; run ./gradlew :fixthis-mcp:installDist" >&2
    return 1
  fi
  local raw
  raw="$(printf '%s\n%s\n%s\n' \
    '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","clientInfo":{"name":"fixthis-smoke","version":"0"},"capabilities":{}}}' \
    '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}' \
    "$(jq -nc --arg pkg "${PACKAGE_NAME}" '{jsonrpc:"2.0",id:2,method:"tools/call",params:{name:"fixthis_status",arguments:{packageName:$pkg}}}')" \
    | "${mcp_bin}" --project-dir "${ROOT_DIR}" --package "${PACKAGE_NAME}" 2>/dev/null)"
  # Each line is one JSON-RPC response; pick the one with id == 2.
  printf '%s\n' "${raw}" \
    | jq -c 'select(type=="object" and .id==2) | .result.content[0].text | fromjson' 2>/dev/null \
    | head -n 1
}

assert_install_stale() {
  local label="$1"
  local expected="$2"
  local payload
  payload="$(call_mcp_status)"
  if [[ -z "${payload}" ]]; then
    record ""
    record "## staleness assertion: ${label}"
    record "Failed to obtain fixthis_status payload."
    return 1
  fi
  record ""
  record "## staleness assertion: ${label}"
  record '```json'
  record "${payload}"
  record '```'
  local actual
  actual="$(printf '%s' "${payload}" | jq -r '.installStale')"
  if [[ "${actual}" != "${expected}" ]]; then
    record "Expected installStale=${expected}, got ${actual}."
    return 1
  fi
  if [[ "${expected}" == "true" ]]; then
    local touched_basename
    touched_basename="$(basename "${STALENESS_TARGET}")"
    if ! printf '%s' "${payload}" | jq -e --arg name "${touched_basename}" '.newerSourceFiles | any(. | contains($name))' >/dev/null; then
      record "Expected newerSourceFiles to mention ${touched_basename}."
      return 1
    fi
  fi
  return 0
}

run_staleness_round_trip() {
  if ! command -v jq >/dev/null 2>&1; then
    echo "[staleness] requires jq" >&2
    return 2
  fi
  if [[ -z "${ADB_BIN}" ]]; then
    echo "[staleness] requires adb" >&2
    return 2
  fi
  if [[ -z "${ACTIVE_SERIAL}" ]]; then
    echo "[staleness] requires a connected authorized device" >&2
    return 2
  fi

  STALENESS_TARGET="$(staleness_target_file)"
  if [[ ! -f "${STALENESS_TARGET}" ]]; then
    echo "[staleness] target source file missing: ${STALENESS_TARGET}" >&2
    return 2
  fi
  STALENESS_ORIGINAL_MTIME="$(staleness_get_mtime "${STALENESS_TARGET}")"
  trap staleness_restore_mtime EXIT INT TERM

  record ""
  record "## staleness round-trip"
  record "- Target source: ${STALENESS_TARGET}"
  record "- Original mtime (epoch): ${STALENESS_ORIGINAL_MTIME}"

  # Step 1: baseline — already installed + cold-launched by main flow.
  if ! assert_install_stale "baseline" "false"; then
    return 1
  fi

  # Step 2: bump mtime one hour into the future, expect installStale=true.
  staleness_bump_mtime_one_hour "${STALENESS_TARGET}"
  record "- Bumped mtime to: $(staleness_get_mtime "${STALENESS_TARGET}")"
  if ! assert_install_stale "after-touch" "true"; then
    return 1
  fi

  # Step 3: restore original mtime BEFORE reinstall so the new install epoch
  # is fresher than every tracked source. Otherwise the touched mtime would
  # still be > install epoch and the assertion would fail.
  staleness_set_mtime "${STALENESS_TARGET}" "${STALENESS_ORIGINAL_MTIME}"
  record "- Restored mtime to: $(staleness_get_mtime "${STALENESS_TARGET}")"

  if ! record_command_output "Reinstall sample app (staleness)" env ANDROID_SERIAL="${ACTIVE_SERIAL}" ./gradlew :app:installDebug; then
    return 1
  fi
  if ! record_command_output "Re-launch sample app (staleness)" adb_cmd shell monkey -p "${PACKAGE_NAME}" -c android.intent.category.LAUNCHER 1; then
    return 1
  fi
  if ! assert_install_stale "after-reinstall" "false"; then
    return 1
  fi
  return 0
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
record "- Check staleness: ${CHECK_STALENESS}"
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

if [[ "${CHECK_STALENESS}" == "true" ]]; then
  if ! run_staleness_round_trip; then
    finish "FAILED_STALENESS" 1
  fi
fi

finish "PASS" 0
