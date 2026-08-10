#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
    echo "Usage: $0 CURRENT_VERSION CURRENT_VERSION_CODE [HISTORY_FILE]" >&2
    exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HISTORY="${3:-$ROOT/release/versions.tsv}"

python3 - "$HISTORY" "$1" "$2" <<'PY'
import re
import sys
from pathlib import Path

history_path = Path(sys.argv[1])
expected_version = sys.argv[2]
expected_code_raw = sys.argv[3]

SEMVER = re.compile(
    r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
    r"(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"
)
POSITIVE_CODE = re.compile(r"^[1-9][0-9]*$")


def parse_semver(raw: str):
    match = SEMVER.fullmatch(raw)
    if match is None:
        raise ValueError(f"invalid SemVer {raw!r}")
    prerelease = match.group(4)
    identifiers = None if prerelease is None else prerelease.split(".")
    if identifiers is not None:
        for identifier in identifiers:
            if identifier.isdigit() and len(identifier) > 1 and identifier.startswith("0"):
                raise ValueError(
                    f"numeric prerelease identifier has a leading zero in {raw!r}",
                )
    return (int(match.group(1)), int(match.group(2)), int(match.group(3)), identifiers)


def compare(left, right):
    core_result = (left[:3] > right[:3]) - (left[:3] < right[:3])
    if core_result:
        return core_result
    left_pre, right_pre = left[3], right[3]
    if left_pre is None:
        return 0 if right_pre is None else 1
    if right_pre is None:
        return -1
    for a, b in zip(left_pre, right_pre):
        a_numeric, b_numeric = a.isdigit(), b.isdigit()
        if a_numeric and b_numeric:
            result = (int(a) > int(b)) - (int(a) < int(b))
        elif a_numeric != b_numeric:
            result = -1 if a_numeric else 1
        else:
            result = (a > b) - (a < b)
        if result:
            return result
    return (len(left_pre) > len(right_pre)) - (len(left_pre) < len(right_pre))


if not history_path.is_file():
    raise SystemExit(f"Release history not found: {history_path}")
if not POSITIVE_CODE.fullmatch(expected_code_raw):
    raise SystemExit(f"Expected versionCode must be a positive integer: {expected_code_raw}")
try:
    parse_semver(expected_version)
except ValueError as error:
    raise SystemExit(str(error)) from error

entries = []
seen = set()
for line_number, raw_line in enumerate(history_path.read_text().splitlines(), 1):
    line = raw_line.strip()
    if not line or line.startswith("#"):
        continue
    fields = line.split()
    if len(fields) != 2:
        raise SystemExit(f"Malformed {history_path} line {line_number}")
    version, code_raw = fields
    try:
        parsed = parse_semver(version)
    except ValueError as error:
        raise SystemExit(f"{history_path} line {line_number}: {error}") from error
    if not POSITIVE_CODE.fullmatch(code_raw):
        raise SystemExit(f"Invalid versionCode on {history_path} line {line_number}: {code_raw}")
    if version in seen:
        raise SystemExit(f"Duplicate version {version} on {history_path} line {line_number}")
    seen.add(version)
    code = int(code_raw)
    if entries:
        previous_version, previous_parsed, previous_code = entries[-1]
        if code <= previous_code:
            raise SystemExit(
                f"versionCode {code} is not greater than {previous_code} on line {line_number}",
            )
        if compare(parsed, previous_parsed) <= 0:
            raise SystemExit(
                f"version {version} is not newer than {previous_version} on line {line_number}",
            )
    entries.append((version, parsed, code))

if not entries:
    raise SystemExit("Release history is empty")
current_version, _, current_code = entries[-1]
expected_code = int(expected_code_raw)
if current_version != expected_version or current_code != expected_code:
    raise SystemExit(
        f"Release history ends at {current_version} ({current_code}), "
        f"expected {expected_version} ({expected_code})",
    )
PY

echo "Verified strictly increasing SemVer and versionCode history through $1 ($2)."
