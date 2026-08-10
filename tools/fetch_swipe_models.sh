#!/usr/bin/env bash
#
# Downloads the two pinned FUTO Swipe models that Slide packages for offline gesture decoding.
# The weights are not Apache-2.0; see engine/src/main/assets/swipe/FUTO_MODEL_LICENSE.md.
set -euo pipefail

REVISION="18328c3042b066952c0936b3771d492fe2ec289a"
BASE_URL="https://huggingface.co/futo-org/futo-swipe/resolve/$REVISION"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="$ROOT/engine/src/main/assets/swipe"
mkdir -p "$DEST_DIR"

download() {
    local remote="$1"
    local name="$2"
    local expected="$3"
    local destination="$DEST_DIR/$name"

    verify() {
        printf '%s  %s\n' "$expected" "$1" | sha256sum --check --status
    }

    if [[ -f "$destination" ]]; then
        if verify "$destination"; then
            echo "Verified existing swipe model: $destination ($(du -h "$destination" | cut -f1))"
            return
        fi
        echo "Existing swipe model failed SHA-256 verification: $destination" >&2
        exit 1
    fi

    echo "Downloading $name"
    trap 'rm -f "$destination.part"' RETURN
    curl --fail --location --proto '=https' --tlsv1.2 --retry 3 --progress-bar \
        "$BASE_URL/$remote" -o "$destination.part"
    if ! verify "$destination.part"; then
        echo "Downloaded swipe model failed SHA-256 verification: $name" >&2
        exit 1
    fi
    mv "$destination.part" "$destination"
    trap - RETURN
}

download \
    "honorable_sturgeon/model_fp32.pte" \
    "encoder.pte" \
    "725242bab5d14345e96ff214e8de2bfbc1f962c232d320df9c24cb82ffd1fbaf"
download \
    "magic_macaw/model_fp32.pte" \
    "decoder.pte" \
    "01eaf16ac4bc0f1ed0698c240807f0e95e6d427bcf6de04983ffc50736744d85"
