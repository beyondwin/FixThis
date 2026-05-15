#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/create-central-portal-bundle.sh <version> <repo-dir> <bundle-path>

Builds the Maven Central Publisher API bundle for FixThis Compose artifacts.

Environment:
  REQUIRE_SIGNATURES=1  Fail unless generated Maven files include .asc signatures.
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

version="${1:?version is required, for example 0.2.2}"
repo_dir="${2:?repo dir is required}"
bundle_path="${3:?bundle path is required}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

checksum_md5() {
  if command -v md5sum >/dev/null 2>&1; then
    md5sum "$1" | awk '{print $1}'
  else
    md5 -q "$1"
  fi
}

checksum_sha1() {
  if command -v sha1sum >/dev/null 2>&1; then
    sha1sum "$1" | awk '{print $1}'
  else
    shasum -a 1 "$1" | awk '{print $1}'
  fi
}

rm -rf "$repo_dir"
mkdir -p "$repo_dir" "$(dirname "$bundle_path")"

(
  cd "$repo_root"
  ./gradlew \
    :fixthis-compose-core:publishMavenPublicationToMavenLocal \
    :fixthis-compose-sidekick:publishDebugPublicationToMavenLocal \
    -Dmaven.repo.local="$repo_dir" \
    -PFIXTHIS_VERSION="$version" \
    --no-daemon
)

artifact_root="$repo_dir/io/github/beyondwin"
if [[ ! -d "$artifact_root" ]]; then
  echo "Expected Maven artifacts under $artifact_root" >&2
  exit 1
fi

find "$artifact_root" -name "maven-metadata-local.xml*" -delete

while IFS= read -r -d '' file; do
  case "$file" in
    *.md5|*.sha1|*.sha256|*.sha512) continue ;;
  esac
  checksum_md5 "$file" >"$file.md5"
  checksum_sha1 "$file" >"$file.sha1"
done < <(find "$artifact_root" -type f -print0)

if [[ "${REQUIRE_SIGNATURES:-0}" == "1" ]]; then
  missing=0
  while IFS= read -r -d '' file; do
    case "$file" in
      *.asc|*.md5|*.sha1|*.sha256|*.sha512) continue ;;
    esac
    if [[ ! -f "$file.asc" ]]; then
      echo "Missing signature: $file.asc" >&2
      missing=1
    fi
  done < <(find "$artifact_root" -type f -print0)
  if [[ "$missing" == "1" ]]; then
    exit 1
  fi
fi

rm -f "$bundle_path"
(
  cd "$repo_dir"
  zip -qr "$bundle_path" io
)

echo "$bundle_path"
