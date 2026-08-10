#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

CHANGELOG="$TEMP_DIR/CHANGELOG.md"
printf '%s\n' \
    '# Changelog' \
    '' \
    '## [0.3.0-alpha.1] - 2026-08-10' \
    '' \
    '- Alpha notes.' > "$CHANGELOG"

if bash "$ROOT/tools/extract_release_notes.sh" 0.3.0 "$CHANGELOG" >/dev/null 2>&1; then
    echo 'A stable version incorrectly matched a prerelease changelog heading.' >&2
    exit 1
fi

printf '%s\n' \
    '# Changelog' \
    '' \
    '## [0.3.0] - 2026-08-10' \
    '' \
    '- Stable notes.' \
    '' \
    '## [0.3.0-alpha.1] - 2026-08-09' \
    '' \
    '- Alpha notes.' > "$CHANGELOG"

NOTES="$(bash "$ROOT/tools/extract_release_notes.sh" 0.3.0 "$CHANGELOG")"
grep -q -- '- Stable notes.' <<< "$NOTES"
if grep -q -- '- Alpha notes.' <<< "$NOTES"; then
    echo 'Release-note extraction crossed into the next version.' >&2
    exit 1
fi

HISTORY="$TEMP_DIR/versions.tsv"
printf '%s\n' \
    '0.2.1 8' \
    '0.2.0 9' > "$HISTORY"
if bash "$ROOT/tools/verify_version_history.sh" 0.2.0 9 "$HISTORY" >/dev/null 2>&1; then
    echo 'A semantic version downgrade passed with a higher versionCode.' >&2
    exit 1
fi

printf '%s\n' \
    '0.2.1 8' \
    '0.3.0-alpha.1 9' \
    '0.3.0 10' > "$HISTORY"
bash "$ROOT/tools/verify_version_history.sh" 0.3.0 10 "$HISTORY" >/dev/null

echo 'Verified exact release-note matching and monotonic SemVer history.'
