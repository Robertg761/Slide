#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
    echo "Usage: $0 VERSION [CHANGELOG]" >&2
    exit 2
fi

VERSION="$1"
CHANGELOG="${2:-CHANGELOG.md}"
[[ -f "$CHANGELOG" ]] || { echo "Changelog not found: $CHANGELOG" >&2; exit 1; }

awk -v heading="## [$VERSION]" '
    function is_target(line) { return line == heading || index(line, heading " ") == 1 }
    is_target($0) { found=1 }
    found && /^## \[/ && !is_target($0) { exit }
    found { print }
    END { if (!found) exit 2 }
' "$CHANGELOG"
