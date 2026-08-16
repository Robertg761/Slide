#!/usr/bin/env bash
#
# Downloads a Whisper model into the :asr module's assets, where it is packaged into the APK.
#
# Slide ships the model rather than downloading it on first run: the keyboard is offline by design,
# and voice input that needs a network round trip before it will work the first time is not
# offline. The cost is APK size, which is the trade the brief asked for.
#
#   tools/fetch_model.sh small.en-q5_1
#
# Quantised (q5_1) builds are used throughout: they are a third of the size of the float models for
# a difference in word error rate that does not show up in dictation.
set -euo pipefail

MODEL="${1:-small.en-q5_1}"
MODEL_REVISION="5359861c739e955e79d9a303bcbc70fb988958b1"
MODEL_SHA256="bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30"
BASE_URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/$MODEL_REVISION"

if [[ "$MODEL" != "small.en-q5_1" ]]; then
    echo "Unsupported model '$MODEL'; Slide ships only small.en-q5_1." >&2
    exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="$ROOT/asr/src/main/assets"
DEST="$DEST_DIR/ggml-$MODEL.bin"

mkdir -p "$DEST_DIR"

verify_model() {
    local file="$1"
    printf '%s  %s\n' "$MODEL_SHA256" "$file" | sha256sum --check --status
}

if [[ -f "$DEST" ]]; then
    if verify_model "$DEST"; then
        echo "Verified existing model: $DEST ($(du -h "$DEST" | cut -f1))"
        exit 0
    fi
    echo "Existing model failed SHA-256 verification: $DEST" >&2
    exit 1
fi

echo "Downloading ggml-$MODEL.bin"
trap 'rm -f "$DEST.part"' EXIT
curl --fail --location --proto '=https' --tlsv1.2 --retry 3 --progress-bar \
    "$BASE_URL/ggml-$MODEL.bin" -o "$DEST.part"
if ! verify_model "$DEST.part"; then
    echo "Downloaded model failed SHA-256 verification." >&2
    exit 1
fi
mv "$DEST.part" "$DEST"
trap - EXIT

echo "Wrote $DEST ($(du -h "$DEST" | cut -f1))"
